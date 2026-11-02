package com.example.smartflow.tool;

import com.example.smartflow.engine.TurnEvent;
import com.example.smartflow.llm.NodeModelClient;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.trace.TraceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/** 带工具的流式对话循环，负责内置工具挂载、调用落库和追问识别；任务栈由节点管理。 */
public final class ToolLoopRunner {

    private static final Logger log = LoggerFactory.getLogger(ToolLoopRunner.class);

    private final NodeModelClient modelClient;
    private final TraceRepository trace;
    private final ObjectMapper jsonMapper;

    public ToolLoopRunner(NodeModelClient modelClient, TraceRepository trace, ObjectMapper jsonMapper) {
        this.modelClient = modelClient;
        this.trace = trace;
        this.jsonMapper = jsonMapper;
    }

    /** text 与本节点推送的 token 一致；question 为 null 表示已经给出最终回答。 */
    public record Result(String text, String question) {
    }

    /** traceOptions 按每轮用途和调用 ID 生成，保留节点定制及工具记录的模型调用关联。 */
    public Result run(NodeScope scope, String purpose, List<ChatMessage> messages,
                      List<FlowTool> tools, int maxRounds,
                      BiFunction<String, String, ChatRequestOptions> traceOptions) throws Exception {
        Map<String, FlowTool> byName = new LinkedHashMap<>();
        for (FlowTool tool : tools) {
            byName.put(tool.name(), tool);
        }
        byName.put(AskUserTool.NAME, AskUserTool.INSTANCE);
        List<ToolSpecification> specs = byName.values().stream().map(FlowTool::specification).toList();

        List<ChatMessage> msgs = new ArrayList<>(messages);
        StringBuilder text = new StringBuilder();
        for (int round = 1; round <= maxRounds; round++) {
            String callId = UUID.randomUUID().toString();
            int roundStart = text.length();
            ChatRequest request = ChatRequest.builder().messages(msgs).toolSpecifications(specs).build();
            AiMessage ai = modelClient.streamChat(scope, request,
                    traceOptions.apply(round == 1 ? purpose : purpose + "#" + round, callId), text)
                    .aiMessage();
            if (!ai.hasToolExecutionRequests()) {
                return new Result(text.toString(), null);
            }

            Optional<ToolExecutionRequest> ask = ai.toolExecutionRequests().stream()
                    .filter(c -> AskUserTool.NAME.equals(c.name())).findFirst();
            if (ask.isPresent()) {
                if (ai.toolExecutionRequests().size() > 1) {
                    log.warn("节点 {} 在同一轮里既调用了 ask_user 又调用了其他工具，其他工具被忽略", scope.nodeId());
                }
                runTool(scope, callId, ask.get(), byName);
                String roundText = text.substring(roundStart).strip();
                String question = askUserQuestion(ask.get());
                if (roundText.isEmpty()) {
                    // 正常情况：问题在工具参数里，推给用户
                    NodeModelClient.appendRoundText(scope, text, question);
                } else if (question.isBlank()) {
                    // 模型把问题写在了正文里
                    question = roundText;
                }
                return new Result(text.toString(), question.isBlank() ? text.toString().strip() : question);
            }

            msgs.add(ai);
            for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
                msgs.add(ToolExecutionResultMessage.from(call, runTool(scope, callId, call, byName)));
            }
        }
        log.warn("节点 {} 工具调用超过 {} 轮，强制结束", scope.nodeId(), maxRounds);
        String giveUp = "抱歉，这个问题需要的查询步骤太多，我暂时没能完成，可以换个问法再试试。";
        NodeModelClient.appendRoundText(scope, text, giveUp);
        return new Result(text.toString(), null);
    }

    /** 执行一次工具调用并落库；异常转成工具结果交给模型处理。 */
    private String runTool(NodeScope scope, String llmCallId, ToolExecutionRequest call, Map<String, FlowTool> tools) {
        String toolCallId = UUID.randomUUID().toString();
        long start = System.nanoTime();
        trace.insertToolCall(toolCallId, scope.turn().turnId(), scope.turn().sessionId(), scope.execId(),
                llmCallId, scope.nodeId(), call.name(), blankToEmptyJson(call.arguments()));
        String status = "SUCCESS";
        String error = null;
        String result;
        try {
            FlowTool tool = tools.get(call.name());
            if (tool == null) {
                throw new IllegalArgumentException("工具不存在: " + call.name());
            }
            Map<String, Object> args = jsonMapper.readValue(blankToEmptyJson(call.arguments()),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            result = tool.execute(args == null ? Map.of() : args,
                    new FlowTool.ToolContext(scope.turn().sessionId(), scope.turn().turnId()));
        } catch (Exception e) {
            log.warn("工具执行失败 node={} tool={} args={}", scope.nodeId(), call.name(), call.arguments(), e);
            status = "ERROR";
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            result = "{\"error\":\"工具执行失败：" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        trace.finishToolCall(toolCallId, result, status, error, ms);
        scope.turn().emit(TurnEvent.of("tool", "node", scope.nodeId(), "name", call.name(),
                "arguments", call.arguments(), "result", result, "status", status, "latencyMs", ms));
        return result;
    }

    private String askUserQuestion(ToolExecutionRequest call) {
        try {
            Map<String, Object> args = jsonMapper.readValue(blankToEmptyJson(call.arguments()),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            Object q = args == null ? null : args.get("question");
            return q == null ? "" : q.toString().strip();
        } catch (Exception e) {
            return "";
        }
    }

    private static String blankToEmptyJson(String json) {
        return json == null || json.isBlank() ? "{}" : json;
    }
}
