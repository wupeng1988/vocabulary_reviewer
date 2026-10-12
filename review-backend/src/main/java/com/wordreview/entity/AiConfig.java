package com.wordreview.entity;

import lombok.Data;
import javax.persistence.*;

/**
 * AI 服务配置（单例行）。
 *
 * 仅保存第三方大模型的接入信息，用于「批量AI解析添加」。
 * 该表独立于词库数据：JSON 备份导出/导入都不包含它，
 * 因此导出备份不会泄漏 API Key，导入备份也不会覆盖本机配置。
 */
@Data
@Entity
@Table(name = "ai_config")
public class AiConfig {

    /** 单例行固定 ID */
    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    /** 服务商标识：deepseek | qwen | custom */
    @Column(nullable = false, length = 32)
    private String provider = "deepseek";

    /** API Key */
    @Column(length = 500)
    private String apiKey;

    /** OpenAI 兼容接口地址（不含 /chat/completions 后缀） */
    @Column(length = 500)
    private String baseUrl;

    /** 模型名，如 deepseek-chat / qwen-plus */
    @Column(length = 100)
    private String model;
}
