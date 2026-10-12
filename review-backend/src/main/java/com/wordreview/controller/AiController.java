package com.wordreview.controller;

import com.wordreview.entity.AiConfig;
import com.wordreview.service.AiConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 服务配置接口。
 *
 * 仅服务于「批量AI解析添加」，配置存放在独立的 ai_config 表，
 * 与词库数据隔离：JSON 备份导出不含它，导入也不会覆盖它。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiConfigService aiConfigService;

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        AiConfig config = aiConfigService.get();
        Map<String, Object> result = new HashMap<>();
        result.put("provider", config.getProvider());
        result.put("apiKey", config.getApiKey());
        result.put("baseUrl", config.getBaseUrl());
        result.put("model", config.getModel());
        result.put("configured", aiConfigService.isConfigured());
        return ResponseEntity.ok(result);
    }

    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody AiConfig config) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (config == null || config.getProvider() == null || config.getProvider().trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "请选择 AI 服务商");
                return ResponseEntity.badRequest().body(result);
            }
            AiConfig saved = aiConfigService.save(config);
            result.put("success", true);
            result.put("message", "保存成功");
            result.put("configured", aiConfigService.isConfigured());
            result.put("provider", saved.getProvider());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }
}
