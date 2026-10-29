package com.example.smartflow.web;

import com.example.smartflow.config.FlowProperties;
import com.example.smartflow.engine.TurnEvent;
import com.example.smartflow.engine.WorkflowEngine;
import com.example.smartflow.engine.WorkflowEngine.TurnFailedException;
import com.example.smartflow.engine.WorkflowEngine.TurnResult;
import com.example.smartflow.session.SessionRepository;
import com.example.smartflow.trace.TraceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final WorkflowEngine engine;
    private final SessionRepository sessions;
    private final TraceRepository trace;
    private final FlowProperties props;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(WorkflowEngine engine, SessionRepository sessions, TraceRepository trace,
                          FlowProperties props) {
        this.engine = engine;
        this.sessions = sessions;
        this.trace = trace;
        this.props = props;
    }

    public record ChatRequestBody(String sessionId, String message) {
        void validate() {
            if (sessionId == null || sessionId.isBlank() || message == null || message.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId 和 message 不能为空");
            }
        }
    }

    /** 流式对话（测试页面用） */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequestBody body) {
        body.validate();
        SseEmitter emitter = new SseEmitter(props.sse().timeoutSeconds() * 1000L);
        AtomicBoolean clientGone = new AtomicBoolean(false);
        emitter.onCompletion(() -> clientGone.set(true));
        emitter.onTimeout(() -> clientGone.set(true));

        executor.submit(() -> {
            try {
                engine.handle(body.sessionId(), body.message(), event -> send(emitter, event, clientGone));
            } catch (TurnFailedException e) {
                // error 事件已经由引擎推送过
            } catch (Exception e) {
                // 例如会话锁冲突，这时引擎还没开始推事件
                send(emitter, TurnEvent.of("error", "message", e.getMessage()), clientGone);
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    /** 非流式对话，方便 curl / 自动化评测调用 */
    @PostMapping("/chat")
    public TurnResult chat(@RequestBody ChatRequestBody body) {
        body.validate();
        return engine.handle(body.sessionId(), body.message(), event -> { });
    }

    // ------------------------------------------------------------------ 调试查询

    @GetMapping("/sessions")
    public Object sessions() {
        return sessions.listRecent(50);
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> session(@PathVariable String sessionId) {
        var record = sessions.find(sessionId);
        if (record.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("turnCount", record.get().turnCount());
        out.put("state", record.get().state());
        out.put("messages", sessions.listMessages(sessionId));
        out.put("turns", trace.listTurns(sessionId));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/turns/{turnId}")
    public ResponseEntity<Map<String, Object>> turn(@PathVariable String turnId) {
        Map<String, Object> turn = trace.getTurn(turnId);
        if (turn == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("turn", turn);
        out.put("nodes", trace.listNodeExecs(turnId));
        out.put("llmCalls", trace.listLlmCalls(turnId));
        out.put("toolCalls", trace.listToolCalls(turnId));
        return ResponseEntity.ok(out);
    }

    // ------------------------------------------------------------------ sse

    private void send(SseEmitter emitter, TurnEvent event, AtomicBoolean clientGone) {
        if (clientGone.get()) {
            return;
        }
        synchronized (emitter) {
            try {
                emitter.send(SseEmitter.event().name(event.type()).data(event.data(), MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                // 客户端断开不影响本轮继续执行和落库
                clientGone.set(true);
                log.debug("SSE 推送失败，客户端可能已断开: {}", e.getMessage());
            }
        }
    }
}
