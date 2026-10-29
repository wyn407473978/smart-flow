package com.example.smartflow.flow;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 任务栈中的一帧：一个进行中的多轮任务（例如"活动创建"）。
 * <p>
 * 需要同时满足两种序列化：
 * <ul>
 *   <li>Java 序列化：LangGraph4j 在节点之间会用 ObjectStream 克隆状态</li>
 *   <li>Jackson：会话状态以 JSONB 存进 flow_session.state</li>
 * </ul>
 * 节点里不要直接修改从状态里取出来的对象，先 {@link #copy()} 再改，然后把新列表写回状态。
 */
public class TaskFrame implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;
    private String taskType;
    /** 该任务当前停留的节点，路由判定 CONTINUE 时会回到这里 */
    private String currentNode;
    private TaskStatus status;
    private LinkedHashMap<String, Object> slots = new LinkedHashMap<>();
    /**
     * 临时帧：一次性节点（例如业务查询）向用户追问时压栈，表示"这个节点在等用户回答"。
     * 只存活一轮：下一轮用户回答了就回到该节点，没回答（换了话题）路由就把它丢掉。
     */
    private boolean ephemeral;
    /** 正在等用户回答什么（追问的原话），路由判断用户回复是否在回答它时使用 */
    private String awaiting;

    public TaskFrame() {
    }

    public static TaskFrame start(String taskType, String firstNode) {
        TaskFrame f = new TaskFrame();
        f.taskId = UUID.randomUUID().toString();
        f.taskType = taskType;
        f.currentNode = firstNode;
        f.status = TaskStatus.ACTIVE;
        return f;
    }

    /** 一次性节点追问用户时的临时帧 */
    public static TaskFrame awaitingAnswer(String intent, String nodeId, String question) {
        TaskFrame f = start(intent, nodeId);
        f.ephemeral = true;
        f.awaiting = question;
        return f;
    }

    public TaskFrame copy() {
        TaskFrame f = new TaskFrame();
        f.ephemeral = ephemeral;
        f.awaiting = awaiting;
        f.taskId = taskId;
        f.taskType = taskType;
        f.currentNode = currentNode;
        f.status = status;
        f.slots = new LinkedHashMap<>(slots);
        return f;
    }

    @JsonIgnore
    public boolean isOpen() {
        return status == TaskStatus.ACTIVE;
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }

    public String getCurrentNode() { return currentNode; }
    public void setCurrentNode(String currentNode) { this.currentNode = currentNode; }

    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }

    public boolean isEphemeral() { return ephemeral; }
    public void setEphemeral(boolean ephemeral) { this.ephemeral = ephemeral; }

    public String getAwaiting() { return awaiting; }
    public void setAwaiting(String awaiting) { this.awaiting = awaiting; }

    public Map<String, Object> getSlots() { return slots; }
    public void setSlots(Map<String, Object> slots) {
        this.slots = slots == null ? new LinkedHashMap<>() : new LinkedHashMap<>(slots);
    }

    public enum TaskStatus {
        ACTIVE, DONE, CANCELLED
    }
}
