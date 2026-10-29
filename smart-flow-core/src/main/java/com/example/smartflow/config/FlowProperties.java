package com.example.smartflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "flow")
public record FlowProperties(Model model, Memory memory, Sse sse, Assistant assistant) {

    @ConstructorBinding
    public FlowProperties {
        if (assistant == null) {
            assistant = new Assistant(null, null);
        }
    }

    /** 不配置助手人设时用默认值（测试里常用） */
    public FlowProperties(Model model, Memory memory, Sse sse) {
        this(model, memory, sse, null);
    }

    /**
     * 助手人设。各节点的提示词都从这里取名字和说话风格，换一个业务只改配置，不用改节点代码。
     *
     * @param name    助手名字，出现在各节点提示词的第一句，例如"智慧民生助手"
     * @param persona 身份和语气的补充说明，例如"语气亲切、简洁、专业，用中文回答"
     */
    public record Assistant(String name, String persona) {
        public Assistant {
            if (name == null || name.isBlank()) {
                name = "智能助手";
            }
            if (persona == null || persona.isBlank()) {
                persona = "语气亲切、简洁、专业，用中文回答";
            }
        }
    }


    /**
     * @param memoryModel 后台记忆任务（滚动摘要、长期记忆抽取）用的模型，不配置时用 routerModel
     */
    public record Model(String baseUrl,
                        String apiKey,
                        String chatModel,
                        String routerModel,
                        Double temperature,
                        Integer timeoutSeconds,
                        Boolean logHttp,
                        String memoryModel) {
        /** record 有多个构造器时，必须标明配置绑定用哪个，否则 Spring Boot 不会绑定，整个配置为 null */
        @ConstructorBinding
        public Model {
            if (memoryModel == null || memoryModel.isBlank()) {
                memoryModel = routerModel;
            }
        }

        public Model(String baseUrl, String apiKey, String chatModel, String routerModel,
                     Double temperature, Integer timeoutSeconds, Boolean logHttp) {
            this(baseUrl, apiKey, chatModel, routerModel, temperature, timeoutSeconds, logHttp, null);
        }
    }

    /**
     * 记忆配置。各工作流可以在自己的 application.yml 里调整，不用改代码。
     *
     * @param recentMessages   节点默认最多带多少条历史消息（按条数，user / assistant 各算一条）
     * @param maxHistoryTokens 节点默认历史消息的 token 上限（估算值），和条数限制同时生效，先到先截
     * @param loadMessages     每轮最多从库里读多少条"还没被摘要"的消息
     * @param summary          滚动摘要
     * @param longTerm         长期记忆
     */
    public record Memory(Integer recentMessages,
                         Integer maxHistoryTokens,
                         Integer loadMessages,
                         Summary summary,
                         LongTerm longTerm) {
        @ConstructorBinding
        public Memory {
            if (recentMessages == null || recentMessages <= 0) {
                recentMessages = 12;
            }
            if (maxHistoryTokens == null || maxHistoryTokens <= 0) {
                maxHistoryTokens = 3000;
            }
            if (loadMessages == null || loadMessages <= 0) {
                loadMessages = 60;
            }
            if (summary == null) {
                summary = new Summary(null, null, null, null);
            }
            if (longTerm == null) {
                longTerm = new LongTerm(null, null);
            }
        }

        /** 只指定历史条数，其余用默认值 */
        public Memory(Integer recentMessages) {
            this(recentMessages, null, null, null, null);
        }
    }

    /**
     * 滚动摘要：未摘要的消息超过 triggerMessages 条时，在后台把最早的那部分压缩进摘要，只保留最近 keepRecent 条原文。
     *
     * @param maxChars 摘要的最大字数，写在给摘要模型的提示词里
     */
    public record Summary(Boolean enabled, Integer triggerMessages, Integer keepRecent, Integer maxChars) {
        public Summary {
            if (enabled == null) {
                enabled = true;
            }
            if (triggerMessages == null || triggerMessages <= 0) {
                triggerMessages = 24;
            }
            if (keepRecent == null || keepRecent < 0) {
                keepRecent = 12;
            }
            if (keepRecent >= triggerMessages) {
                throw new IllegalArgumentException("flow.memory.summary.keep-recent 必须小于 trigger-messages");
            }
            if (maxChars == null || maxChars <= 0) {
                maxChars = 400;
            }
        }
    }

    /**
     * 长期记忆：按用户保存的稳定信息（例如常住城市、称呼偏好），跨会话生效。请求里没有 userId 时不启用。
     *
     * @param maxItems 每个用户最多保存多少条，读取时也最多带这么多条
     */
    public record LongTerm(Boolean enabled, Integer maxItems) {
        public LongTerm {
            if (enabled == null) {
                enabled = true;
            }
            if (maxItems == null || maxItems <= 0) {
                maxItems = 50;
            }
        }
    }

    public record Sse(Integer timeoutSeconds) {
        public Sse {
            if (timeoutSeconds == null || timeoutSeconds <= 0) {
                timeoutSeconds = 180;
            }
        }
    }
}
