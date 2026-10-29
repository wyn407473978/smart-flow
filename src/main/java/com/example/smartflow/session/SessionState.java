package com.example.smartflow.session;

import com.example.smartflow.flow.TaskFrame;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨轮持久化的会话状态，存在 flow_session.state（JSONB）。
 * <p>
 * 早期对话摘要已经移到 flow_memory_summary 表（由后台任务单独更新，避免和每轮保存的会话状态互相覆盖），
 * 旧数据里的 summary 字段读取时忽略。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionState {

    private List<TaskFrame> taskStack = new ArrayList<>();

    public List<TaskFrame> getTaskStack() { return taskStack; }
    public void setTaskStack(List<TaskFrame> taskStack) {
        this.taskStack = taskStack == null ? new ArrayList<>() : new ArrayList<>(taskStack);
    }
}
