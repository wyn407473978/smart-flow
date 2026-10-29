package com.example.smartflow.node.slot;

/**
 * 槽位的取值类型。模型抽取出来的原始值会按类型转换和校验，转换失败的值不会写入。
 */
public enum SlotType {
    /** 文本，去掉首尾空白，空串视为没提到 */
    STRING,
    /** 整数（存为 Integer），"60"、60.0 都可以，60.5 不行 */
    INTEGER,
    /** 数字（存为 Double） */
    NUMBER,
    /** 布尔，true / false，也接受"是""否" */
    BOOLEAN,
    /** 枚举：存编码，模型输出编码或中文名都可以 */
    ENUM
}
