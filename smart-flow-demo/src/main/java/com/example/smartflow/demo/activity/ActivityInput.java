package com.example.smartflow.demo.activity;

import java.io.Serializable;
import java.util.List;

/**
 * 本轮输入的结构化表示。null 表示没有提到，不能覆盖已保存的信息。
 * ageOpenEnded：只给了一端年龄时，用户说的是"20岁以上"这类单边范围（另一端不限，清掉旧的另一端）为 true；
 * 只是调整一端（"上限改成70"）为 false 或 null，保留旧的另一端。
 */
public record ActivityInput(String region, Boolean allAudience, String gender, Integer ageMin, Integer ageMax,
                            String sendTime, String templateBrief, Integer templateChoice,
                            List<String> editSections, Boolean ageOpenEnded) implements Serializable {
    public static ActivityInput empty() {
        return new ActivityInput(null, null, null, null, null, null, null, null, List.of(), null);
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
