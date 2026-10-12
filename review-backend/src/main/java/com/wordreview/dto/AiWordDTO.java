package com.wordreview.dto;

import lombok.Data;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 解析单个单词的返回结果。
 *
 * input        — 用户原始输入（可能带 s/ed/ing 等变形，也可能拼错）
 * lemma        — AI 归一化后的词典原形，入库只用这个
 * phonetic     — 音标
 * meanings     — 该词的全部中文释义
 * example      — 按雅思常见义项造的例句
 * exampleMeaning — 例句中文翻译
 */
@Data
public class AiWordDTO {

    private String input;
    private String lemma;
    private String phonetic;
    private List<String> meanings = Collections.emptyList();
    private String example;
    private String exampleMeaning;

    /** 多个含义合并成一列存储用的分隔符 */
    public static final String MEANING_SEPARATOR = "；";

    /** 把含义列表拼成 C 列要的单行文本 */
    public String meaningText() {
        if (meanings == null || meanings.isEmpty()) return null;
        return meanings.stream()
                .filter(m -> m != null && !m.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.joining(MEANING_SEPARATOR));
    }
}
