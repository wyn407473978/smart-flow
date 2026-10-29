package com.example.smartflow.memory;

import com.example.smartflow.config.FlowProperties;
import com.example.smartflow.flow.HistoryMessage;
import com.example.smartflow.flow.MemoryFact;
import com.example.smartflow.flow.ToolFact;
import com.example.smartflow.llm.ModelRegistry;
import com.example.smartflow.memory.LongTermMemoryExtractor.Extraction;
import com.example.smartflow.memory.LongTermMemoryExtractor.Item;
import com.example.smartflow.memory.MemoryRepository.SummaryRecord;
import com.example.smartflow.session.SessionRepository;
import com.example.smartflow.trace.TraceKeys;
import com.example.smartflow.trace.TraceRepository;
import dev.langchain4j.model.chat.ChatRequestOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 记忆的统一入口，引擎只和它打交道：
 * <ul>
 *   <li>{@link #load}：每轮开始时取出 摘要 + 未摘要的历史消息 + 这些消息里的工具调用结果 + 用户长期记忆</li>
 *   <li>{@link #afterTurn}：每轮成功结束后在后台执行 滚动摘要 和 长期记忆抽取，不占用户等待时间</li>
 * </ul>
 * 后台任务失败只记日志，不影响对话；下一轮会再次尝试。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** 后台记忆任务在 flow_llm_call 里的 node_id */
    public static final String TRACE_NODE_ID = "memory";

    /**
     * 每轮开始时读到的记忆。
     *
     * @param summary      早期对话摘要
     * @param history      摘要之后的对话原文（按时间正序，最多 load-messages 条）
     * @param toolFacts    history 覆盖的轮次里成功的工具调用
     * @param userMemories 用户长期记忆
     */
    public record MemoryContext(String summary, List<HistoryMessage> history,
                                List<ToolFact> toolFacts, List<MemoryFact> userMemories) {
    }

    private final SessionRepository sessions;
    private final TraceRepository trace;
    private final MemoryRepository repo;
    private final ConversationSummarizer summarizer;
    private final LongTermMemoryExtractor extractor;
    private final ModelRegistry models;
    private final FlowProperties props;
    private final Executor executor;
    /** 同一会话同时只跑一个摘要任务 */
    private final Set<String> summarizing = ConcurrentHashMap.newKeySet();

    @Autowired
    public MemoryService(SessionRepository sessions, TraceRepository trace, MemoryRepository repo,
                         ConversationSummarizer summarizer, LongTermMemoryExtractor extractor,
                         ModelRegistry models, FlowProperties props) {
        this(sessions, trace, repo, summarizer, extractor, models, props, Executors.newVirtualThreadPerTaskExecutor());
    }

    /** 测试时传入同步执行的 Executor（Runnable::run），结果可以立刻断言 */
    public MemoryService(SessionRepository sessions, TraceRepository trace, MemoryRepository repo,
                         ConversationSummarizer summarizer, LongTermMemoryExtractor extractor,
                         ModelRegistry models, FlowProperties props, Executor executor) {
        this.sessions = sessions;
        this.trace = trace;
        this.repo = repo;
        this.summarizer = summarizer;
        this.extractor = extractor;
        this.models = models;
        this.props = props;
        this.executor = executor;
    }

    // =====================================================================
    // 读
    // =====================================================================

    public MemoryContext load(String sessionId, String userId) {
        FlowProperties.Memory cfg = props.memory();
        SummaryRecord summary = cfg.summary().enabled() ? repo.findSummary(sessionId) : SummaryRecord.EMPTY;
        List<HistoryMessage> history = sessions.messagesAfter(sessionId, summary.upToMessageId(), cfg.loadMessages());

        Set<String> turnIds = new LinkedHashSet<>();
        for (HistoryMessage m : history) {
            if (m.turnId() != null) {
                turnIds.add(m.turnId());
            }
        }
        List<ToolFact> toolFacts = trace.listToolFacts(sessionId, turnIds);

        List<MemoryFact> memories = longTermEnabled(userId)
                ? repo.listUserMemories(userId, cfg.longTerm().maxItems())
                : List.of();
        return new MemoryContext(summary.summary(), history, toolFacts, memories);
    }

    public List<MemoryFact> userMemories(String userId) {
        return userId == null || userId.isBlank() ? List.of()
                : repo.listUserMemories(userId, props.memory().longTerm().maxItems());
    }

    public SummaryRecord summary(String sessionId) {
        return repo.findSummary(sessionId);
    }

    // =====================================================================
    // 写（后台）
    // =====================================================================

    /** 每轮成功结束后调用，立即返回，记忆任务在后台执行 */
    public void afterTurn(String sessionId, String userId, String turnId, String userInput, String reply) {
        executor.execute(() -> {
            try {
                summarizeIfNeeded(sessionId, turnId);
            } catch (Exception e) {
                log.warn("滚动摘要失败 session={} turn={}", sessionId, turnId, e);
            }
            try {
                extractLongTerm(sessionId, userId, turnId, userInput, reply);
            } catch (Exception e) {
                log.warn("长期记忆抽取失败 session={} turn={}", sessionId, turnId, e);
            }
        });
    }

    /**
     * 未摘要的消息超过 trigger-messages 条时，把最早的那部分（只留最近 keep-recent 条）压缩进摘要。
     */
    void summarizeIfNeeded(String sessionId, String turnId) {
        FlowProperties.Summary cfg = props.memory().summary();
        if (!cfg.enabled() || !summarizing.add(sessionId)) {
            return;
        }
        try {
            SummaryRecord current = repo.findSummary(sessionId);
            // 正常情况下未摘要的消息不会远超 trigger-messages，这里的上限只是防御
            List<HistoryMessage> pending = sessions.messagesAfter(sessionId, current.upToMessageId(),
                    cfg.triggerMessages() * 10);
            if (pending.size() <= cfg.triggerMessages()) {
                return;
            }
            List<HistoryMessage> fold = pending.subList(0, pending.size() - cfg.keepRecent());
            Long lastId = fold.get(fold.size() - 1).id();
            if (lastId == null) {
                return;
            }
            String newSummary = summarizer.summarize(models.memory(), current.summary(), fold, cfg.maxChars(),
                    traceOptions(sessionId, turnId, "summarize"));
            if (newSummary.isBlank()) {
                log.warn("摘要模型返回空，本次不推进摘要 session={}", sessionId);
                return;
            }
            repo.saveSummary(sessionId, new SummaryRecord(newSummary, lastId, current.summarizedCount() + fold.size()));
            log.debug("滚动摘要完成 session={} 压缩 {} 条，up_to={}", sessionId, fold.size(), lastId);
        } finally {
            summarizing.remove(sessionId);
        }
    }

    void extractLongTerm(String sessionId, String userId, String turnId, String userInput, String reply) {
        if (!longTermEnabled(userId)) {
            return;
        }
        int max = props.memory().longTerm().maxItems();
        List<MemoryFact> existing = repo.listUserMemories(userId, max);
        Extraction ex = extractor.extract(models.memory(), existing, userInput, reply,
                traceOptions(sessionId, turnId, "extract_memory"));
        if (ex.isEmpty()) {
            return;
        }
        for (String key : ex.deletes()) {
            repo.deleteUserMemoryByKey(userId, key);
        }
        Set<String> keys = new LinkedHashSet<>();
        existing.forEach(f -> keys.add(f.key()));
        ex.deletes().forEach(keys::remove);
        for (Item item : ex.upserts()) {
            if (!keys.contains(item.key()) && keys.size() >= max) {
                log.warn("用户 {} 的长期记忆已达上限 {} 条，忽略新增的 {}", userId, max, item.key());
                continue;
            }
            repo.upsertUserMemory(userId, item.key(), item.value(), sessionId, turnId);
            keys.add(item.key());
        }
    }

    private boolean longTermEnabled(String userId) {
        return props.memory().longTerm().enabled() && userId != null && !userId.isBlank();
    }

    /** 后台任务也落 flow_llm_call，关联到触发它的轮次，测试页轮次详情里能看到 */
    private static ChatRequestOptions traceOptions(String sessionId, String turnId, String purpose) {
        return ChatRequestOptions.builder()
                .addListenerAttribute(TraceKeys.TURN_ID, turnId)
                .addListenerAttribute(TraceKeys.SESSION_ID, sessionId)
                .addListenerAttribute(TraceKeys.NODE_ID, TRACE_NODE_ID)
                .addListenerAttribute(TraceKeys.PURPOSE, purpose)
                .addListenerAttribute(TraceKeys.STREAMING, false)
                .build();
    }
}
