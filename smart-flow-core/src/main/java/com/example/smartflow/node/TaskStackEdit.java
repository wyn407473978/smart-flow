package com.example.smartflow.node;

import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.flow.TaskFrame.TaskStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 对任务栈的一次修改。状态里的 {@link TaskFrame} 不能直接改（LangGraph4j 节点之间共享同一份状态），
 * 所以先复制一份栈，改完再整体写回状态更新：
 *
 * <pre>
 * TaskStackEdit edit = editTasks(state);
 * TaskFrame task = edit.requireActiveTask(id());
 * task.getSlots().put("region", "阳光社区");
 * edit.advanceTo("choose_template");      // 任务推进到下一阶段
 * edit.writeTo(updates);
 * goTo(updates, "choose_template");        // 本轮内继续执行下一阶段
 * </pre>
 *
 * 任务的生命周期：路由开启任务（压栈，ACTIVE）→ 各阶段节点 {@link #advanceTo} 推进 →
 * 最后一个阶段 {@link #complete} 完成，或者用户取消（{@link #cancel}）。
 * 完成 / 取消的帧在本轮结束时由引擎从栈里去掉，栈下面如果还有被打断的任务，下一轮会回到它。
 */
public final class TaskStackEdit {

    private final List<TaskFrame> frames;

    private TaskStackEdit(List<TaskFrame> frames) {
        this.frames = frames;
    }

    /** 复制状态里的任务栈 */
    public static TaskStackEdit of(FlowState state) {
        List<TaskFrame> copy = new ArrayList<>();
        for (TaskFrame f : state.taskStack()) {
            copy.add(f.copy());
        }
        return new TaskStackEdit(copy);
    }

    /** 最靠近栈顶的进行中任务（不含临时追问帧），可以直接修改。完成 / 取消之后再调用会返回下面一层的任务 */
    public Optional<TaskFrame> activeTask() {
        for (int i = frames.size() - 1; i >= 0; i--) {
            TaskFrame f = frames.get(i);
            if (f.isOpen() && !f.isEphemeral()) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    /** 任务阶段节点用：没有进行中的任务说明流转出了问题，直接报错 */
    public TaskFrame requireActiveTask(String nodeId) {
        return activeTask().orElseThrow(() -> new IllegalStateException("节点 " + nodeId + " 没有找到进行中的任务"));
    }

    /** 任务推进到下一阶段：下一轮路由判定 CONTINUE 时会回到 nodeId。本轮内要继续执行它，还需要 goTo */
    public TaskStackEdit advanceTo(String nodeId) {
        TaskFrame task = activeTask()
                .orElseThrow(() -> new IllegalStateException("没有进行中的任务，无法推进到节点 " + nodeId));
        task.setCurrentNode(nodeId);
        task.setAwaiting(null);
        return this;
    }

    /** 任务完成 */
    public TaskStackEdit complete() {
        activeTask().ifPresent(t -> {
            t.setStatus(TaskStatus.DONE);
            t.setAwaiting(null);
        });
        return this;
    }

    /** 任务取消 */
    public TaskStackEdit cancel() {
        activeTask().ifPresent(t -> {
            t.setStatus(TaskStatus.CANCELLED);
            t.setAwaiting(null);
        });
        return this;
    }

    /** 修改后的整个栈（含已完成 / 已取消的帧，引擎在轮末统一清理） */
    public List<TaskFrame> frames() {
        return frames;
    }

    /** 写回状态更新 */
    public Map<String, Object> writeTo(Map<String, Object> updates) {
        updates.put(FlowState.TASK_STACK, new ArrayList<>(frames));
        return updates;
    }
}
