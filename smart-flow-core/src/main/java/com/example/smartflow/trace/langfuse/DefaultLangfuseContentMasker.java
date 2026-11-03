package com.example.smartflow.trace.langfuse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 基础防护；任意自然语言里的姓名、住址等不能靠通用正则可靠识别，生产环境应提供业务策略。 */
public final class DefaultLangfuseContentMasker implements LangfuseContentMasker {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> PRIVATE_KEYS = Set.of("apikey", "secretkey", "password", "authorization",
            "token", "accesstoken", "refreshtoken", "secret", "email", "phone", "mobile", "idcard",
            "userid", "sessionid", "name", "username", "fullname", "address", "age", "姓名", "年龄", "身份证", "手机号");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern SECRET = Pattern.compile("(?i)(?:sk|pk)-(?:lf-)?[A-Za-z0-9_-]{8,}|Bearer\\s+[^\\s\"',}]+");

    @Override
    public JsonNode mask(JsonNode content) {
        if (content == null || content.isNull()) {
            return content;
        }
        if (content.isObject()) {
            var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            content.fields().forEachRemaining(entry -> {
                String key = entry.getKey().replaceAll("[_-]", "").toLowerCase(Locale.ROOT);
                boolean toolName = "name".equals(key) && (content.has("arguments") || content.has("description")
                        || content.has("parameters"));
                result.set(entry.getKey(), PRIVATE_KEYS.contains(key) && !toolName
                        ? TextNode.valueOf("[REDACTED]") : mask(entry.getValue()));
            });
            return result;
        }
        if (content.isArray()) {
            var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            content.forEach(value -> result.add(mask(value)));
            return result;
        }
        if (content.isTextual()) {
            String original = content.textValue().strip();
            // 工具参数、工具结果和记忆块可能被封装成 JSON 字符串，不能只脱敏最外层。
            if (original.startsWith("{") || original.startsWith("[")) {
                try { return TextNode.valueOf(mask(JSON.readTree(original)).toString()); }
                catch (Exception ignored) { /* 普通文本继续使用基础脱敏 */ }
            }
            String value = SECRET.matcher(content.textValue()).replaceAll("[REDACTED]");
            value = EMAIL.matcher(value).replaceAll("[EMAIL]");
            return TextNode.valueOf(PHONE.matcher(value).replaceAll("[PHONE]"));
        }
        return content.deepCopy();
    }
}
