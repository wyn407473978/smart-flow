package com.example.smartflow.node;

import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.flow.TaskFrame.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskStackEditTest {

    private static FlowState stateWith(TaskFrame... frames) {
        return new FlowState(Map.of(FlowState.TASK_STACK, List.of(frames)));
    }

    @Test
    void editsACopyNotTheState() {
        TaskFrame original = TaskFrame.start("ACTIVITY_CREATE", "collect_target");
        FlowState state = stateWith(original);

        TaskStackEdit edit = TaskStackEdit.of(state);
        edit.requireActiveTask("collect_target").getSlots().put("region", "阳光社区");
        edit.advanceTo("choose_template");

        assertTrue(original.getSlots().isEmpty(), "状态里的帧不应被修改");
        assertEquals("collect_target", original.getCurrentNode());
        assertEquals("choose_template", edit.activeTask().orElseThrow().getCurrentNode());
    }

    @Test
    void activeTaskSkipsEphemeralAndClosedFrames() {
        TaskFrame outer = TaskFrame.start("A", "a1");
        TaskFrame done = TaskFrame.start("B", "b1");
        done.setStatus(TaskStatus.DONE);
        TaskFrame asking = TaskFrame.awaitingAnswer("BIZ_QUERY", "biz_query", "查哪个城市？");

        TaskStackEdit edit = TaskStackEdit.of(stateWith(outer, done, asking));
        assertEquals("A", edit.activeTask().orElseThrow().getTaskType());
    }

    @Test
    void advanceClearsAwaitingAndCompleteCloses() {
        TaskFrame task = TaskFrame.start("A", "confirm");
        task.setAwaiting("确认提交吗？");
        TaskStackEdit edit = TaskStackEdit.of(stateWith(task));

        edit.advanceTo("collect");
        assertNull(edit.activeTask().orElseThrow().getAwaiting());

        TaskFrame active = edit.activeTask().orElseThrow();
        edit.complete();
        assertEquals(TaskStatus.DONE, active.getStatus());
        assertFalse(edit.activeTask().isPresent(), "完成后不再是进行中的任务");

        Map<String, Object> updates = edit.writeTo(new HashMap<>());
        @SuppressWarnings("unchecked")
        List<TaskFrame> written = (List<TaskFrame>) updates.get(FlowState.TASK_STACK);
        assertSame(active, written.get(0));
    }

    @Test
    void cancelOnlyTouchesActiveTask() {
        TaskFrame outer = TaskFrame.start("A", "a1");
        TaskFrame inner = TaskFrame.start("B", "b1");
        TaskStackEdit edit = TaskStackEdit.of(stateWith(outer, inner));

        edit.cancel();
        assertEquals(TaskStatus.CANCELLED, edit.frames().get(1).getStatus());
        assertEquals(TaskStatus.ACTIVE, edit.frames().get(0).getStatus());
        assertEquals("A", edit.activeTask().orElseThrow().getTaskType(), "取消内层任务后回到外层任务");
    }

    @Test
    void requireActiveTaskFailsWithoutTask() {
        TaskStackEdit edit = TaskStackEdit.of(stateWith());
        assertThrows(IllegalStateException.class, () -> edit.requireActiveTask("x"));
        assertThrows(IllegalStateException.class, () -> edit.advanceTo("x"));
    }
}
