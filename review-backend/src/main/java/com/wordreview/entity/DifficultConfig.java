package com.wordreview.entity;

import lombok.Data;
import javax.persistence.*;

/**
 * 易错词配置（单例行）。
 *
 * 三个数字分别控制：
 *   1. windowSize         —— 「最近 N 次拼写」的统计窗口长度（词库管理那一列显示的 N）
 *   2. consecutiveWrongLimit —— 连续拼错多少次就自动加入易错词组
 *   3. recentWrongLimit   —— 最近 N 次拼写里累计拼错多少次就自动加入易错词组
 *
 * 与词库数据放在一起（参与 JSON 备份的导出与导入）：它是纯数字、无任何隐私，
 * 用户换了机器或重装后应该保留自己调好的阈值。
 */
@Data
@Entity
@Table(name = "difficult_config")
public class DifficultConfig {

    /** 单例行固定 ID */
    public static final Long SINGLETON_ID = 1L;

    /** 统计窗口长度上限，同时限制 spell_recent_history 列长度 */
    public static final int MAX_WINDOW_SIZE = 100;

    @Id
    private Long id = SINGLETON_ID;

    /** 最近 N 次拼写的统计窗口 */
    @Column
    private Integer windowSize = 30;

    /** 连续拼错多少次自动加入易错词组 */
    @Column
    private Integer consecutiveWrongLimit = 3;

    /** 最近 N 次拼写里累计拼错多少次自动加入易错词组 */
    @Column
    private Integer recentWrongLimit = 5;
}
