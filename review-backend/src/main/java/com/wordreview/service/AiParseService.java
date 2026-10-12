package com.wordreview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordreview.dto.AiWordDTO;
import com.wordreview.entity.AiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 调用大模型批量解析单词。
 *
 * 走 OpenAI 兼容的 /chat/completions 协议，因此 DeepSeek 与通义千问都能用，
 * 也允许用户自填任意兼容网关（自定义 baseUrl / model）。
 *
 * 两个关键点：
 * 1. 输入可能带 s / ed / ing 等变形甚至拼错，要求模型返回词典原形 lemma，入库只用 lemma
 * 2. 返回的自然语言 JSON 不完全可靠，所以解析要宽容：去代码块围栏、定位首个 JSON、
 *    按 input 对齐；对不上或字段全空的一律判定为解析失败，宁可不入库也不写脏数据
 */
@Slf4j
@Service
public class AiParseService {

    /** 每次请求给模型的单词数：过多会让返回 JSON 变得不稳，也拉长单次耗时 */
    private static final int CHUNK_SIZE = 10;

    /** 单次请求超时：一批 10 个词通常 5~20 秒 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final String SYSTEM_PROMPT = String.join("\n",
            "你是英语词典编纂助手，为雅思备考用户整理词条。",
            "用户会给你一批英文单词，它们可能是原型，也可能是复数、过去式、-ing 等变形，甚至有轻微拼写错误。",
            "请为每个输入单词给出词典信息，并且只输出 JSON，不要输出任何 JSON 以外的文字或解释。",
            "",
            "输出结构：",
            "{\"words\":[{\"input\":\"...\",\"lemma\":\"...\",\"phonetic\":\"...\",\"meanings\":[\"...\"],\"example\":\"...\",\"exampleMeaning\":\"...\"}]}",
            "",
            "要求：",
            "1. lemma 必须是词典原形，例如 running→run、went→go、children→child、studies→study、better→good；本身就是原型则原样返回",
            "2. input 字段原样回填输入的单词，不要改动",
            "3. phonetic 给出音标，形如 /dɪˈtelɪdʒəns/",
            "4. meanings 给出该词在雅思考试中常见的全部中文释义，按常用度从高到低排列",
            "5. example 用 lemma 的雅思常见义项写一句地道、简短的英文例句",
            "6. exampleMeaning 是该例句的中文翻译",
            "7. 输入有几个就返回几项，顺序保持一致",
            "8. 若某个词确实无法识别，lemma 直接填该输入原词，并照常给出你最有把握的信息");

    private final AiConfigService aiConfigService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiParseService(AiConfigService aiConfigService) {
        this.aiConfigService = aiConfigService;
    }

    /**
     * 批量解析。逐批调用，任一批次失败直接抛出（调用方此时尚未入库，不会产生半截数据）。
     *
     * @param words 已去重、已排除词库重复的待解析单词
     * @return 与入参等长、顺序一致的结果；对不上的位置不会出现，调用方按 input 自行比对
     */
    public List<AiWordDTO> parse(List<String> words) {
        if (words == null || words.isEmpty()) {
            return new ArrayList<>();
        }
        if (!aiConfigService.isConfigured()) {
            throw new IllegalStateException("尚未配置 AI 服务，请先到「AI 设置」填写服务商、API Key 和接口地址");
        }

        AiConfig config = aiConfigService.get();
        String url = buildChatUrl(config.getBaseUrl());
        String body;

        List<AiWordDTO> results = new ArrayList<>();
        for (int i = 0; i < words.size(); i += CHUNK_SIZE) {
            List<String> chunk = words.subList(i, Math.min(i + CHUNK_SIZE, words.size()));
            log.info("AI 解析批次 {}/{}，共 {} 个词", (i / CHUNK_SIZE) + 1,
                    (words.size() + CHUNK_SIZE - 1) / CHUNK_SIZE, chunk.size());

            body = buildRequestBody(config, chunk);
            String content = callApi(url, config.getApiKey(), body);
            results.addAll(parseContent(content, chunk));
        }

        log.info("AI 解析完成：请求 {} 个，有效返回 {} 个", words.size(), results.size());
        return results;
    }

    /** 拼接 OpenAI 兼容接口地址：容忍用户填不填 /v1 或 /chat/completions */
    private String buildChatUrl(String baseUrl) {
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/chat/completions")) {
            return url;
        }
        return url + "/chat/completions";
    }

    private String buildRequestBody(AiConfig config, List<String> chunk) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", config.getModel());
            payload.put("temperature", 0.2);

            Map<String, Object> system = new LinkedHashMap<>();
            system.put("role", "system");
            system.put("content", SYSTEM_PROMPT);

            Map<String, Object> user = new LinkedHashMap<>();
            user.put("role", "user");
            user.put("content", "单词列表：\n" + String.join("\n", chunk));

            payload.put("messages", List.of(system, user));
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("构造 AI 请求失败: " + e.getMessage(), e);
        }
    }

    private String callApi(String url, String apiKey, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IllegalStateException("AI 请求失败 (HTTP " + response.statusCode() + "): "
                        + shorten(response.body()));
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode message = root.path("choices").path(0).path("message");
            String content = message.path("content").asText(null);
            if (content == null || content.trim().isEmpty()) {
                throw new IllegalStateException("AI 返回内容为空: " + shorten(response.body()));
            }
            return content;
        } catch (IllegalStateException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IllegalStateException("AI 请求超时（" + REQUEST_TIMEOUT.getSeconds()
                    + " 秒），请稍后重试或减少单词数量");
        } catch (java.net.ConnectException e) {
            // 连接被拒/无法解析：ConnectException 的 message 常为 null，直接给可读提示
            throw new IllegalStateException("连不上 AI 服务，请检查接口地址是否正确：" + url);
        } catch (Exception e) {
            String detail = e.getMessage() == null || e.getMessage().trim().isEmpty()
                    ? e.getClass().getSimpleName() : e.getMessage();
            throw new IllegalStateException("AI 请求异常（" + url + "）: " + detail, e);
        }
    }

    /**
     * 解析模型返回内容，按 input 与本批输入对齐。
     * 返回值只包含「信息可用」的条目，解析不出来的词不出现在结果里。
     */
    private List<AiWordDTO> parseContent(String content, List<String> chunk) {
        List<AiWordDTO> parsed = new ArrayList<>();
        try {
            JsonNode root = extractJsonNode(content);

            JsonNode arrayNode = null;
            if (root.isArray()) {
                arrayNode = root;
            } else if (root.has("words") && root.get("words").isArray()) {
                arrayNode = root.get("words");
            } else {
                // 兜底：对象里第一个数组字段
                var fields = root.fieldNames();
                while (fields.hasNext()) {
                    JsonNode candidate = root.get(fields.next());
                    if (candidate.isArray()) {
                        arrayNode = candidate;
                        break;
                    }
                }
            }

            if (arrayNode == null) {
                throw new IllegalStateException("AI 返回的 JSON 中找不到单词数组: " + shorten(content));
            }

            for (JsonNode node : arrayNode) {
                AiWordDTO dto = toDto(node);
                if (dto != null && isUsable(dto)) {
                    parsed.add(dto);
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AI 返回内容解析失败: " + shorten(content), e);
        }

        // 按 input 对齐本批输入，保证顺序与去向可控
        Map<String, AiWordDTO> byInput = new LinkedHashMap<>();
        for (AiWordDTO dto : parsed) {
            if (dto.getInput() != null && !dto.getInput().trim().isEmpty()) {
                byInput.putIfAbsent(dto.getInput().trim().toLowerCase(Locale.ROOT), dto);
            }
        }

        List<AiWordDTO> aligned = new ArrayList<>();
        for (String w : chunk) {
            AiWordDTO hit = byInput.get(w.toLowerCase(Locale.ROOT));
            if (hit != null) {
                aligned.add(hit);
            }
            // 对不上的词直接丢弃，由调用方计入「解析失败」
        }
        return aligned;
    }

    /** 从模型输出中抠出 JSON：容忍代码块围栏和前后多余文字 */
    private JsonNode extractJsonNode(String content) throws Exception {
        String text = content.trim();

        // 去掉 ```json ... ``` 或 ``` ... ```
        if (text.startsWith("```")) {
            int firstLineBreak = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstLineBreak > 0 && lastFence > firstLineBreak) {
                text = text.substring(firstLineBreak + 1, lastFence).trim();
            }
        }

        // 定位首个 { 或 [
        int start = indexOfFirstJson(text);
        if (start < 0) {
            throw new IllegalStateException("AI 返回内容中没有 JSON: " + shorten(content));
        }
        // 从后往前找收尾
        int end = Math.max(text.lastIndexOf('}'), text.lastIndexOf(']'));
        if (end <= start) {
            throw new IllegalStateException("AI 返回的 JSON 不完整: " + shorten(content));
        }
        return objectMapper.readTree(text.substring(start, end + 1));
    }

    private int indexOfFirstJson(String text) {
        int brace = text.indexOf('{');
        int bracket = text.indexOf('[');
        if (brace < 0) return bracket;
        if (bracket < 0) return brace;
        return Math.min(brace, bracket);
    }

    private AiWordDTO toDto(JsonNode node) {
        try {
            AiWordDTO dto = new AiWordDTO();
            dto.setInput(node.path("input").asText(null));
            dto.setLemma(node.path("lemma").asText(null));
            dto.setPhonetic(node.path("phonetic").asText(null));

            JsonNode meanings = node.path("meanings");
            List<String> list = new ArrayList<>();
            if (meanings.isArray()) {
                meanings.forEach(m -> {
                    String v = m.asText("");
                    if (!v.trim().isEmpty()) list.add(v.trim());
                });
            } else if (meanings.isTextual()) {
                for (String part : meanings.asText().split("[;；、,，]")) {
                    if (!part.trim().isEmpty()) list.add(part.trim());
                }
            }
            dto.setMeanings(list);

            dto.setExample(node.path("example").asText(null));
            dto.setExampleMeaning(node.path("exampleMeaning").asText(null));
            return dto;
        } catch (Exception e) {
            log.warn("解析 AI 返回条目失败: {}", e.getMessage());
            return null;
        }
    }

    /** 至少要有音标/含义/例句中的一项，否则视为无效 */
    private boolean isUsable(AiWordDTO dto) {
        boolean hasPhonetic = dto.getPhonetic() != null && !dto.getPhonetic().trim().isEmpty();
        boolean hasMeaning = dto.meaningText() != null;
        boolean hasExample = dto.getExample() != null && !dto.getExample().trim().isEmpty();
        return hasPhonetic || hasMeaning || hasExample;
    }

    private String shorten(String s) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
    }
}
