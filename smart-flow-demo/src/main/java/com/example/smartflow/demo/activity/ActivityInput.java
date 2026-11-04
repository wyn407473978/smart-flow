package com.example.smartflow.demo.activity;

import java.io.Serializable;
import java.util.List;

/** 本轮输入的结构化表示。null 表示没有提到，不能覆盖已保存的信息。 */
public record ActivityInput(String region, Boolean allAudience, String gender, Integer ageMin, Integer ageMax,
                            String sendTime, String templateBrief, Integer templateChoice,
                            List<String> editSections) implements Serializable {
    public static ActivityInput empty() {
        return new ActivityInput(null, null, null, null, null, null, null, null, List.of());
    }

    boolean touches(String section) {
        return switch (section) {
            case "region" -> region != null;
            case "audience" -> allAudience != null || gender != null || ageMin != null || ageMax != null;
            case "time" -> sendTime != null;
            case "template" -> templateBrief != null || templateChoice != null;
            default -> false;
        };
    }
}
