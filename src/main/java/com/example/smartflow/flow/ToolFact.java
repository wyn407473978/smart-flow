package com.example.smartflow.flow;

import java.io.Serializable;

/**
 * 历史窗口内某一轮的一次成功工具调用。节点的 HistoryPolicy 打开 includeToolResults 时，
 * 会以"最近查询到的数据"的形式放进提示词，模型回答追问（"那明天呢"）时能用上原始数据。
 */
public record ToolFact(String turnId, String toolName, String arguments, String result) implements Serializable {
}
