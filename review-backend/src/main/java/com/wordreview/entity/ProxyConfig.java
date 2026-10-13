package com.wordreview.entity;

import lombok.Data;
import javax.persistence.*;

/**
 * 网络代理配置（单例行）。
 *
 * 只服务于后端出站的微软 Edge TTS 语音合成请求（WSS），
 * 让无法直连 speech.platform.bing.com 的网络环境也能生成发音。
 *
 * 与词库数据完全隔离：JSON 备份导出不含它，导入也不覆盖它，
 * 不会把本机代理地址泄漏到备份文件里。
 *
 * 只有地址和端口：JDK 的 java.net.http.HttpClient 在 CONNECT 隧道上
 * 不会补发 Proxy-Authorization（Exchange 层不走认证过滤器），
 * 因此需要账号密码的代理用不了，留字段只会让人误填。
 */
@Data
@Entity
@Table(name = "proxy_config")
public class ProxyConfig {

    /** 单例行固定 ID */
    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    /** 是否启用代理 */
    @Column(nullable = false)
    private Boolean enabled = false;

    /** 代理主机地址（仅支持免认证的 HTTP 代理，如 127.0.0.1 / proxy.example.com） */
    @Column(length = 255)
    private String host;

    /** 代理端口（1-65535） */
    private Integer port;
}
