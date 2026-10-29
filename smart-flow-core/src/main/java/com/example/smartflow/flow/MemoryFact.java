package com.example.smartflow.flow;

import java.io.Serializable;

/**
 * 一条长期记忆：关于用户的稳定信息，例如 常住城市 → 杭州。
 *
 * @param id        flow_user_memory.id
 * @param key       记忆的名字，同一用户唯一
 * @param value     记忆的内容
 * @param updatedAt 最后更新时间（ISO 字符串），提示词里用来提示模型信息可能过时
 */
public record MemoryFact(Long id, String key, String value, String updatedAt) implements Serializable {
}
