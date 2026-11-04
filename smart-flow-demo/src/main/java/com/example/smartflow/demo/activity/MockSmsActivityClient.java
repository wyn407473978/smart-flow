package com.example.smartflow.demo.activity;

import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** mock 业务接口：仅创建定时短信活动，不发送真实短信；同一请求号返回同一活动。重启后清空。 */
@Service
public class MockSmsActivityClient {
    public record Request(String region, String gender, Integer ageMin, Integer ageMax, String sendTime, String smsContent) { }
    public record Result(String activityId, Request request) { }
    private final Map<String, Result> activities = new ConcurrentHashMap<>();
    public Result create(String requestId, Request request) {
        return activities.computeIfAbsent(requestId, key -> new Result("SMS-" + UUID.randomUUID().toString().substring(0, 8), request));
    }
}
