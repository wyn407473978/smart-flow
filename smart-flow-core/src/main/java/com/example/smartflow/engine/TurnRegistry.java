package com.example.smartflow.engine;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TurnRegistry {

    private final Map<String, TurnContext> turns = new ConcurrentHashMap<>();

    public void register(TurnContext ctx) {
        turns.put(ctx.turnId(), ctx);
    }

    public void unregister(String turnId) {
        turns.remove(turnId);
    }

    public TurnContext get(String turnId) {
        TurnContext ctx = turns.get(turnId);
        if (ctx == null) {
            throw new IllegalStateException("找不到轮次上下文 turnId=" + turnId);
        }
        return ctx;
    }
}
