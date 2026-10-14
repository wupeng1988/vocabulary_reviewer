package com.wordreview.service;

import com.wordreview.entity.DifficultConfig;
import com.wordreview.repository.DifficultConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 易错词配置（单例读写）。
 *
 * 三个阈值都是普通数字、不含任何隐私，所以参与 JSON 备份的导出与导入，
 * 用户换机器后能保留自己调好的值。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DifficultConfigService {

    /** 窗口长度上限，同步限制另外两个阈值的上限 */
    public static final int MAX_WINDOW_SIZE = DifficultConfig.MAX_WINDOW_SIZE;

    private final DifficultConfigRepository repository;

    /** 读取配置；还没初始化过则用默认值落库后返回 */
    @Transactional
    public DifficultConfig get() {
        return repository.findById(DifficultConfig.SINGLETON_ID).orElseGet(() -> {
            DifficultConfig created = repository.save(new DifficultConfig());
            log.info("初始化易错词配置: 窗口 {} 次，连续错误 {} 次，窗口内累计错误 {} 次",
                    created.getWindowSize(), created.getConsecutiveWrongLimit(), created.getRecentWrongLimit());
            return created;
        });
    }

    /**
     * 保存配置，参数不合法直接抛 IllegalArgumentException（由 Controller 转 400）。
     *
     * 约束：
     *   - 窗口长度 1..{MAX_WINDOW_SIZE}
     *   - 两个阈值都必须 ≥1 且 ≤ 窗口长度（超过窗口就永远触发不了，属于配置错误）
     */
    @Transactional
    public DifficultConfig save(DifficultConfig newConfig) {
        DifficultConfig config = get();

        Integer windowSize = newConfig.getWindowSize();
        if (windowSize == null) throw new IllegalArgumentException("统计窗口次数不能为空");
        if (windowSize < 1 || windowSize > MAX_WINDOW_SIZE) {
            throw new IllegalArgumentException("统计窗口次数需在 1 ~ " + MAX_WINDOW_SIZE + " 之间");
        }

        Integer consecutive = newConfig.getConsecutiveWrongLimit();
        if (consecutive == null) throw new IllegalArgumentException("连续错误次数不能为空");
        if (consecutive < 1) throw new IllegalArgumentException("连续错误次数至少为 1");
        if (consecutive > windowSize) {
            throw new IllegalArgumentException("连续错误次数不能大于统计窗口次数（" + windowSize + "）");
        }

        Integer recent = newConfig.getRecentWrongLimit();
        if (recent == null) throw new IllegalArgumentException("窗口内累计错误次数不能为空");
        if (recent < 1) throw new IllegalArgumentException("窗口内累计错误次数至少为 1");
        if (recent > windowSize) {
            throw new IllegalArgumentException("窗口内累计错误次数不能大于统计窗口次数（" + windowSize + "）");
        }

        config.setWindowSize(windowSize);
        config.setConsecutiveWrongLimit(consecutive);
        config.setRecentWrongLimit(recent);
        DifficultConfig saved = repository.save(config);
        log.info("易错词配置已更新: 窗口 {} 次，连续错误 {} 次，窗口内累计错误 {} 次",
                saved.getWindowSize(), saved.getConsecutiveWrongLimit(), saved.getRecentWrongLimit());
        return saved;
    }

    /** 窗口长度（配置异常时兜底 30，避免把历史串截错） */
    public int windowSize() {
        Integer n = get().getWindowSize();
        return (n == null || n < 1) ? 30 : Math.min(n, MAX_WINDOW_SIZE);
    }
}
