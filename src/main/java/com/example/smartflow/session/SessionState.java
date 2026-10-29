package com.example.smartflow.session;

import com.example.smartflow.flow.TaskFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨轮持久化的会话状态，存在 flow_session.state（JSONB）。
 */
public class SessionState {

    private List<TaskFrame> taskStack = new ArrayList<>();
    private String summary = "";

    public List<TaskFrame> getTaskStack() { return taskStack; }
    public void setTaskStack(List<TaskFrame> taskStack) {
        this.taskStack = taskStack == null ? new ArrayList<>() : new ArrayList<>(taskStack);
    }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary == null ? "" : summary; }
}
