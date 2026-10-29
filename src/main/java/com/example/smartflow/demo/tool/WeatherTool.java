package com.example.smartflow.demo.tool;

import com.example.smartflow.tool.FlowTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询天气。演示用：返回假数据，同一个城市同一天的结果固定，不同城市结果不同。
 * 接真实天气接口时只需要改 {@link #execute}。
 */
@Component
public class WeatherTool implements FlowTool {

    private static final List<String> CONDITIONS = List.of("晴", "多云", "阴", "小雨", "阵雨", "晴转多云");
    private static final List<String> WINDS = List.of("东南风 2 级", "北风 3 级", "西南风 1 级", "东风 3 级");

    private static final ToolSpecification SPEC = ToolSpecification.builder()
            .name("get_weather")
            .description("查询指定城市某一天的天气情况，包括天气状况、气温、湿度、风力")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("city", "城市名称，例如：北京、杭州。用户没说城市时先询问用户，不要自己假设")
                    .addStringProperty("date", "日期，格式 yyyy-MM-dd；用户说今天、明天时换算成具体日期；不传默认今天")
                    .required("city")
                    .build())
            .build();

    private final ObjectMapper mapper;

    public WeatherTool(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ToolSpecification specification() {
        return SPEC;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext context) throws Exception {
        Object cityArg = arguments.get("city");
        if (cityArg == null || cityArg.toString().isBlank()) {
            return "{\"error\":\"缺少参数 city，请先询问用户要查询哪个城市\"}";
        }
        String city = cityArg.toString().trim();
        Object dateArg = arguments.get("date");
        String date = dateArg == null || dateArg.toString().isBlank() ? LocalDate.now().toString() : dateArg.toString().trim();

        // 用城市 + 日期算一个稳定的伪随机数，保证同样的问题每次结果一样
        int seed = Math.abs((city + date).hashCode());
        int low = 8 + seed % 15;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("city", city);
        result.put("date", date);
        result.put("condition", CONDITIONS.get(seed % CONDITIONS.size()));
        result.put("temperature", low + "~" + (low + 6 + seed % 5) + "℃");
        result.put("humidity", (40 + seed % 45) + "%");
        result.put("wind", WINDS.get(seed % WINDS.size()));
        result.put("source", "演示数据");
        return mapper.writeValueAsString(result);
    }
}
