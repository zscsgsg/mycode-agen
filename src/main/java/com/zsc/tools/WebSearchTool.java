package com.zsc.tools;


import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class WebSearchTool implements Function<WebSearchTool.Request, String> {

    private static final String SEARCH_API_URL = "https://www.searchapi.io/api/v1/search";
    private final String apiKey;

    public WebSearchTool(@Value("${searchapi.api-key}") String apiKey) {
        this.apiKey = apiKey;
    }

    public static class Request {
        private String query;
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
    }

    @Override
    public String apply(Request request) {
        Map<String, Object> params = new HashMap<>();
        params.put("q", request.getQuery());
        params.put("api_key", apiKey);
        params.put("engine", "baidu");
        try {
            String response = HttpUtil.get(SEARCH_API_URL, params);
            JSONObject json = JSONUtil.parseObj(response);
            JSONArray results = json.getJSONArray("organic_results");
            if (results == null || results.isEmpty()) {
                return "未找到相关结果";
            }
            int limit = Math.min(results.size(), 5);
            return results.subList(0, limit).stream()
                    .map(obj -> ((JSONObject) obj).toString())
                    .collect(Collectors.joining("\n---\n"));
        } catch (Exception e) {
            return "搜索失败: " + e.getMessage();
        }
    }
}