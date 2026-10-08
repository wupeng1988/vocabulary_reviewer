package com.wordreview.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.EnumSet;

import javax.servlet.DispatcherType;

/**
 * 前端单页应用 (SPA) 回退过滤器。
 *
 * 打包发布时前端构建产物放在 classpath:/static/ 下，由后端直接对外提供，
 * 不再需要 Vite dev server。前端使用 history 路由（如 /words、/review），
 * 刷新或直接访问深链接时后端找不到对应静态文件，这里统一回退到 index.html，
 * 由 Vue Router 接管路由。
 *
 * 只处理 GET/HEAD 的「看起来像页面」的路径，/api、/h2-console、带扩展名的
 * 静态资源、错误页等一律放行，避免影响正常接口。
 */
@Slf4j
@Configuration
public class SpaFallbackConfig {

    /** index.html 只读一次缓存，避免每次刷新都走 classpath IO */
    private volatile byte[] indexHtml;

    @Bean
    public FilterRegistrationBean<Filter> spaForwardFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new Filter() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                HttpServletRequest req = (HttpServletRequest) request;
                HttpServletResponse res = (HttpServletResponse) response;

                if (shouldFallback(req)) {
                    byte[] html = indexHtml();
                    // 尚未执行前端构建时没有 index.html，按原样交给后续处理器（404）
                    if (html != null) {
                        writeIndex(res, html);
                        return;
                    }
                }
                chain.doFilter(request, response);
            }
        });
        // 只在 REQUEST 分发时生效，转发/包含/错误分发不再经过，天然防止死循环
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        registration.addUrlPatterns("/*");
        registration.setName("spaForwardFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    private boolean shouldFallback(HttpServletRequest req) {
        String method = req.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }

        String uri = req.getRequestURI();
        String context = req.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }

        // 后端接口与 H2 控制台由各自的处理器负责
        if (uri.startsWith("/api") || uri.startsWith("/h2-console") || uri.startsWith("/error")) {
            return false;
        }

        // 带扩展名的一律按静态资源处理（找不到时由 Boot 返回 404）
        String lastSegment = uri;
        int slash = uri.lastIndexOf('/');
        if (slash >= 0) {
            lastSegment = uri.substring(slash + 1);
        }
        return !lastSegment.contains(".");
    }

    private void writeIndex(HttpServletResponse res, byte[] html) throws IOException {
        res.setStatus(HttpServletResponse.SC_OK);
        res.setContentType("text/html;charset=UTF-8");
        // index.html 引用的是带哈希的静态资源，不能被浏览器缓存，否则升级后深链接会拿到旧页面
        res.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        res.setContentLength(html.length);
        res.getOutputStream().write(html);
        res.getOutputStream().flush();
    }

    /**
     * 读取并缓存 index.html；文件不存在（未跑前端构建的开发环境）返回 null。
     */
    private byte[] indexHtml() {
        byte[] cached = indexHtml;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (indexHtml != null) {
                return indexHtml;
            }
            ClassPathResource resource = new ClassPathResource("static/index.html");
            if (!resource.exists()) {
                return null;
            }
            try {
                indexHtml = StreamUtils.copyToByteArray(resource.getInputStream());
            } catch (IOException e) {
                log.warn("读取 index.html 失败: {}", e.getMessage());
                return null;
            }
            return indexHtml;
        }
    }
}
