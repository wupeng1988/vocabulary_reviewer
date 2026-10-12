package com.wordreview.service;

import com.wordreview.entity.AiConfig;
import com.wordreview.repository.AiConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 服务配置（单例读写）。
 * 与词库数据完全隔离：不参与 JSON 备份的导出与导入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConfigService {

    private final AiConfigRepository repository;

    /** 读取配置；未初始化过则返回带默认值的新对象（不落库） */
    public AiConfig get() {
        return repository.findById(AiConfig.SINGLETON_ID).orElseGet(AiConfig::new);
    }

    /** 保存配置（固定写入单例行） */
    @Transactional
    public AiConfig save(AiConfig config) {
        config.setId(AiConfig.SINGLETON_ID);
        config.setProvider(trimToNull(config.getProvider(), "deepseek"));
        config.setApiKey(trimToNull(config.getApiKey(), null));
        config.setBaseUrl(trimToNull(config.getBaseUrl(), null));
        config.setModel(trimToNull(config.getModel(), null));
        AiConfig saved = repository.save(config);
        log.info("AI 配置已更新: provider={}, baseUrl={}, model={}, apiKey={}",
                saved.getProvider(), saved.getBaseUrl(), saved.getModel(),
                saved.getApiKey() == null ? "空" : "已设置");
        return saved;
    }

    /** 是否已配置齐全（provider + key + url + model 都有值） */
    public boolean isConfigured() {
        AiConfig c = get();
        return notBlank(c.getProvider())
                && notBlank(c.getApiKey())
                && notBlank(c.getBaseUrl())
                && notBlank(c.getModel());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String trimToNull(String s, String fallback) {
        if (s == null) return fallback;
        String t = s.trim();
        return t.isEmpty() ? fallback : t;
    }
}
