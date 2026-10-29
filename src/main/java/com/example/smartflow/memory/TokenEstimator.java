package com.example.smartflow.memory;

/**
 * token 数估算。只用于决定历史窗口截到哪里，不需要精确。
 * 默认实现是 {@link SimpleTokenEstimator}；需要更准时可以提供自己的实现（例如按模型的分词器计算），
 * 声明成 Spring Bean 并加上 @Primary 即可替换。
 */
public interface TokenEstimator {

    int estimate(String text);
}
