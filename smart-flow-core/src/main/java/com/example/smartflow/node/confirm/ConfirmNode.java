package com.example.smartflow.node.confirm;

import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.AbstractNode;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.NodeType;
import com.example.smartflow.node.TaskStackEdit;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用的"二次确认 → 执行写操作"节点，一般是任务的最后一个阶段。
 * <p>
 * 流程：
 * <ol>
 *   <li>第一次进入：用固定文案展示要确认的内容（{@link #summary}，不经过模型，保证和真正提交的内容一致），
 *       在任务帧上记下"正在等用户确认"，本轮结束</li>
 *   <li>用户回复后判断（明确的"确认""好的"直接判定，其余交给模型）：
 *     <ul>
 *       <li>确认 → 调用 {@link #commit}（带幂等键）→ 成功则任务完成，失败则保持待确认，用户可以重试</li>
 *       <li>修改并给出了新内容 → 回到 {@link #editNode} 处理这句话，收齐后各阶段自动往后走，最后回到这里重新确认</li>
 *       <li>想修改但没说改成什么 → 问要改哪一项</li>
 *       <li>取消 → 任务取消</li>
 *       <li>其他（例如插话回来后说"继续"）→ 重新展示确认内容</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <b>幂等</b>：确认后先调用 commit，本轮结束时才保存会话状态。如果 commit 成功之后本轮出错，状态会回滚到"待确认"，
 * 用户再说一次"确认"会再次调用 commit。所以 commit 必须用传入的 idempotencyKey（同一个任务固定不变）去重，
 * 例如作为业务接口的请求号，或者存进业务表的唯一索引。
 */
public abstract class ConfirmNode extends AbstractNode {

    /** 用户对确认请求的回复属于哪一种 */
    public enum Decision {
        /** 确认无误 */
        CONFIRM,
        /** 要修改，并且已经说了改成什么 */
        MODIFY,
        /** 想修改，但还没说改成什么 */
        ASK_MODIFY,
        /** 不办了 */
        CANCEL,
        /** 判断不了 */
        UNCLEAR
    }

    /** 写操作的结果；message 会直接回复给用户 */
    public record CommitResult(boolean success, String message) {
        public static CommitResult ok(String message) {
            return new CommitResult(true, message);
        }

        public static CommitResult failed(String message) {
            return new CommitResult(false, message);
        }
    }

    /** 模型的判断结果 */
    public record DecisionOutput(Decision decision, String reason) {
    }

    /** 不需要问模型的明确确认 */
    private static final Set<String> PLAIN_CONFIRMATIONS = Set.of(
            "确认", "确定", "是", "是的", "对", "对的", "好", "好的", "可以", "行", "没问题", "没错",
            "确认无误", "提交", "确认提交", "就这样", "ok", "okay", "yes");

    private static final String DECIDE_PROMPT = """
            你是确认判断模块。系统刚请用户确认以下信息，确认后将%s：
            %s

            判断用户的回复属于哪一种（decision）：
            - CONFIRM：确认无误，同意%s
            - MODIFY：要修改某项信息，并且已经说了改成什么（例如"性别改成男性""时间换到周日下午"）
            - ASK_MODIFY：想修改，但还没说改成什么（例如"我要改一下""不对""有个地方错了"）
            - CANCEL：不办了、取消
            - UNCLEAR：其他情况。用户只说"继续""接着来"也属于 UNCLEAR（需要重新展示确认内容），不能当成确认

            只输出 JSON，格式：
            {"decision": "CONFIRM|MODIFY|ASK_MODIFY|CANCEL|UNCLEAR", "reason": "一句话理由"}
            """;

    protected ConfirmNode(NodeSupport support) {
        super(support);
    }

    // =====================================================================
    // 子类实现
    // =====================================================================

    /** 要用户确认的内容（可以多行），例如"- 目标人群：60 岁及以上、女性\n- 活动时间：下周六上午" */
    protected abstract String summary(TaskFrame task);

    /**
     * 执行写操作（调用业务接口、写业务表）。抛异常等同于失败，会提示用户稍后重试。
     *
     * @param idempotencyKey 幂等键，同一个任务固定不变，见类注释
     */
    protected abstract CommitResult commit(TaskFrame task, String idempotencyKey) throws Exception;

    /** 用户要修改信息时回到哪个节点，一般是任务的第一个阶段（各阶段收齐后会自动往后走，最终回到这里） */
    protected abstract String editNode(TaskFrame task);

    /** 确认后要做的事，用在提示语里，例如"创建活动""提交申请" */
    protected String actionName() {
        return "提交";
    }

    /** 展示给用户的确认请求 */
    protected String confirmText(TaskFrame task) {
        return "请确认以下信息：\n" + summary(task) + "\n\n确认无误请回复「确认」，需要修改直接告诉我要改什么。";
    }

    // =====================================================================
    // 基类行为
    // =====================================================================

    @Override
    public NodeType type() {
        return NodeType.CONFIRM;
    }

    @Override
    public String stageDescription() {
        return "等用户确认后" + actionName() + "（用户可能确认、要求修改某项信息，或者取消）";
    }

    @Override
    public Map<String, String> promptPreview() {
        Map<String, String> prompts = new LinkedHashMap<>();
        prompts.put("confirm_decision · system", DECIDE_PROMPT.formatted(actionName(), "{确认内容}", actionName()));
        prompts.put("确认文案（固定文案，不经过模型）", "请确认以下信息：\n{确认内容}\n\n确认无误请回复「确认」，需要修改直接告诉我要改什么。");
        return prompts;
    }

    @Override
    protected Map<String, Object> execute(FlowState state, NodeScope scope) throws Exception {
        TaskStackEdit edit = editTasks(state);
        TaskFrame task = edit.requireActiveTask(id());
        Map<String, Object> updates = updates();

        if (task.getAwaiting() == null || task.getAwaiting().isBlank()) {
            // 第一次进入（或者修改后回来）：展示确认内容
            askToConfirm(scope, task, updates);
            return edit.writeTo(updates);
        }

        switch (decide(state, scope, task)) {
            case CONFIRM -> doCommit(scope, edit, task, updates);
            case MODIFY -> {
                String target = editNode(task);
                edit.advanceTo(target);
                goTo(updates, target);
            }
            case ASK_MODIFY -> updates.put(FlowState.REPLIES,
                    emitText(scope, "好的，你想改哪一项？直接告诉我新的内容就行。"));
            case CANCEL -> {
                edit.cancel();
                updates.put(FlowState.REPLIES, emitText(scope, "好的，已取消，这次没有" + actionName() + "。还有其他需要可以随时告诉我。"));
            }
            // 判断不了（例如插话回来后说"继续"）：重新展示确认内容
            default -> askToConfirm(scope, task, updates);
        }
        return edit.writeTo(updates);
    }

    private void askToConfirm(NodeScope scope, TaskFrame task, Map<String, Object> updates) {
        task.setAwaiting("请用户确认是否" + actionName() + "（确认 / 修改某项信息 / 取消）");
        updates.put(FlowState.REPLIES, emitText(scope, confirmText(task)));
    }

    private void doCommit(NodeScope scope, TaskStackEdit edit, TaskFrame task, Map<String, Object> updates) {
        CommitResult result;
        try {
            result = commit(task, task.getTaskId());
        } catch (Exception e) {
            log.error("确认节点执行写操作失败 node={} task={}", id(), task.getTaskId(), e);
            result = CommitResult.failed("抱歉，" + actionName() + "时系统出了点问题。");
        }
        if (result == null) {
            result = CommitResult.failed("抱歉，" + actionName() + "没有成功。");
        }
        if (result.success()) {
            edit.complete();
            updates.put(FlowState.REPLIES, emitText(scope, result.message()));
        } else {
            // 保持待确认，用户可以直接重试
            updates.put(FlowState.REPLIES, emitText(scope,
                    result.message() + "你可以稍后回复「确认」重试，或者说「取消」。"));
        }
    }

    // ------------------------------------------------------------------ 判断用户的回复

    private Decision decide(FlowState state, NodeScope scope, TaskFrame task) {
        if (isPlainConfirmation(state.userInput())) {
            return Decision.CONFIRM;
        }
        List<ChatMessage> messages = List.of(
                system(DECIDE_PROMPT.formatted(actionName(), summary(task), actionName())),
                UserMessage.from("用户回复：" + state.userInput()));
        try {
            DecisionOutput out = chatJson(scope, "confirm_decision", support.models().router(), messages, DecisionOutput.class);
            return out == null || out.decision() == null ? Decision.UNCLEAR : out.decision();
        } catch (LlmJsonException e) {
            log.warn("确认判断输出解析失败，按无法判断处理 node={}", id(), e);
            return Decision.UNCLEAR;
        }
    }

    /** 去掉标点、空白、语气词后，是不是一句明确的确认（"确认！""好的。""嗯，可以"） */
    static boolean isPlainConfirmation(String input) {
        if (input == null) {
            return false;
        }
        String s = input.strip().toLowerCase()
                .replaceAll("[\\p{P}\\p{S}\\s]+", "")
                .replaceAll("^(嗯|恩|哦|噢|那)+", "")
                .replaceAll("(吧|啊|呀|哈|的吧|了)+$", "");
        return !s.isEmpty() && PLAIN_CONFIRMATIONS.contains(s);
    }
}
