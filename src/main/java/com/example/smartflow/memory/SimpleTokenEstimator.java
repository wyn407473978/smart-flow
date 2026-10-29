package com.example.smartflow.memory;

import org.springframework.stereotype.Component;

/**
 * 粗略估算：中日韩文字按 1 字 ≈ 1 token，其余字符按 4 个 ≈ 1 token。
 * 对国内常见模型（Qwen、DeepSeek）的中文对话来说偏保守，宁可少带一点历史也不要超长。
 */
@Component
public class SimpleTokenEstimator implements TokenEstimator {

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) {
                cjk++;
            } else {
                other++;
            }
            i += Character.charCount(cp);
        }
        return cjk + (other + 3) / 4;
    }

    private static boolean isCjk(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL
                // 全角标点
                || (cp >= 0x3000 && cp <= 0x303F) || (cp >= 0xFF00 && cp <= 0xFFEF);
    }
}
