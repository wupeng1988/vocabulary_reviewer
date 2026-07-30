# Word Review Pro — 本地智能单词复习系统

## 项目概述
基于 Spring Boot + Vue 3 的本地桌面端单词复习工具。核心价值是智能调度算法：高频打击顽固生词 + 强制唤醒长期沉睡词。

## 技术栈
- **后端**: Spring Boot 2.7.18 + Spring Data JPA + H2 (文件模式) + Apache POI 5.2.5 + Lombok
- **前端**: Vue 3 + Vite + Element Plus + Axios + ECharts + Vue Router 4
- **数据库**: H2 文件模式 `jdbc:h2:~/worddb`，单机单用户，无鉴权

## 项目结构
```
vocabulary-review/
├── review-backend/                    # Spring Boot 后端
│   ├── pom.xml
│   ├── audio/                         # TTS 音频文件缓存（运行时生成，不入库）
│   └── src/main/java/com/wordreview/
│       ├── WordReviewApplication.java    # 启动类
│       ├── entity/Word.java              # 单词实体（含 recogOnly 仅认读标记）
│       ├── entity/ReviewConfig.java      # 复习配置实体
│       ├── repository/WordRepository.java
│       ├── repository/ReviewConfigRepository.java
│       ├── service/ExcelImportService.java       # Excel 导入（Upsert 逻辑）
│       ├── service/ReviewService.java            # 核心调度算法 + 记录结果
│       ├── service/StatsService.java             # 统计看板
│       ├── service/EdgeTtsService.java           # Edge TTS Java 原生实现（WebSocket 协议）
│       ├── service/AudioGenerationTaskService.java # 音频批量生成任务管理
│       ├── service/DataExportImportService.java   # JSON 数据导入导出
│       ├── controller/WordController.java        # /api/words/*
│       ├── controller/ReviewController.java      # /api/review/*
│       ├── controller/StatsController.java       # /api/stats/*
│       ├── controller/AudioController.java       # /api/audio/*
│       └── dto/                                  # 请求/响应 DTO
├── review-frontend/                   # Vue 3 前端
│   ├── vite.config.js                 # 含 /api、/audio 代理到 localhost:8080
│   └── src/
│       ├── main.js                    # 注册 ElementPlus + Router
│       ├── api/index.js               # Axios 封装
│       ├── router/index.js            # 路由: /words, /review, /stats, /data
│       ├── components/Layout.vue      # 左菜单+右内容布局
│       └── views/
│           ├── WordManage.vue         # 词库管理（上传+表格+搜索+编辑+删除+批量生成音频）
│           ├── ReviewCenter.vue       # 复习中心（认读+拼写练习+默写三阶段）
│           ├── DataManage.vue         # 数据管理（导入导出）
│           └── StatsDashboard.vue    # 统计看板（卡片+ECharts图表）
├── dev.sh                             # 一键启停脚本 (start|stop|restart|status)
└── 本地智能单词复习系统.md             # 需求文档
```

## 启动方式
```bash
# 一键启动（推荐）
./dev.sh start

# 或分别启动
cd review-backend && mvn spring-boot:run   # 后端 (端口 8080)
cd review-frontend && npm run dev          # 前端 (端口 5173, 代理到后端)

# 其他命令
./dev.sh restart   # 一键重启
./dev.sh stop      # 一键停止
./dev.sh status    # 查看运行状态
```
访问 http://localhost:5173

## API 接口
| 方法 | 路径 | 功能 |
|------|------|------|
| POST | `/api/words/import` | 上传 Excel (MultipartFile) |
| GET | `/api/words/page` | 分页查询 (?page=&size=&keyword=) |
| PUT | `/api/words/{id}` | 更新单词内容（生词/音标/含义/例句/例句释义） |
| DELETE | `/api/words/{id}` | 删除单词 |
| PUT | `/api/words/{id}/recog-only` | 更新仅认读标记 |
| GET | `/api/review/config` | 获取复习配置 |
| PUT | `/api/review/config` | 更新复习配置 |
| GET | `/api/review/generate` | 生成复习列表（核心，返回含 recogOnly 字段） |
| POST | `/api/review/record` | 上报复习结果 {wordId, mode, result} |
| GET | `/api/stats/dashboard` | 统计看板数据 |
| GET | `/api/audio/{word}` | 获取单词音频（已存在返回 MP3，否则 202 触发异步生成） |
| GET | `/api/audio/{word}/status` | 查询音频生成状态（ready/generating） |
| POST | `/api/audio/batch` | 触发批量音频生成（运行中则不重复） |
| GET | `/api/audio/batch/status` | 查询批量生成状态（running/idle） |

## 核心算法：复习调度（ReviewService.generateReviewList）
1. **强制召回**: last_review_date IS NULL 或距今 > maxDormantDays 天 → 直接入围
2. **综合评分**: Score = E×0.7 + normalized_H×0.3
   - E = (recog_wrong + spell_wrong + 1) / (review_total + 2)
   - H = 天数×0.5 - review_total×0.1（Min-Max 归一化）
3. **随机抽样**: 取前 2×batchSize 候选，shuffle 后取 batchSize 个

## 关键业务规则
- **Upsert**: Excel 导入时 word 已存在则更新内容字段，保留复习统计
- **三阶段复习**: Phase 1 认读(英→中) + Phase 2 拼写练习(显示全部信息+拼写框) + Phase 3 默写(中→英)
- **四种复习模式**: 完整复习(认读+拼写+默写) / 仅认读 / 仅拼写练习 / 仅默写
- **仅认读标记 (recogOnly)**: 标记为仅认读的单词不参与拼写练习和默写阶段
- **休眠判定**: consecutive_correct >= 5 → is_dormant=true
- **拼写校验**: 忽略大小写与首尾空格，Unicode 连字符统一归一化为普通连字符 (U+002D)
- **默写正确率统计**: 仅统计第一轮默写首次尝试结果，重复尝试不覆盖
- **防重入**: 拼写练习和默写阶段，正确后等待自动跳转期间忽略后续回车
- **H2 Console**: http://localhost:8080/h2-console (JDBC URL: jdbc:h2:~/worddb, user: sa, password: 空)

## 音频发音系统
- **实现方式**: Java 原生实现 Edge TTS WebSocket 协议（EdgeTtsService），无需 Python 依赖
- **协议细节**: WSS 连接微软 Edge 在线 TTS 服务，DRM token 生成 (SHA-256)，SSML 语音合成请求，二进制 MP3 音频接收
- **默认语音**: en-US-EmmaMultilingualNeural
- **音频缓存**: 文件存储在 `review-backend/audio/` 目录，按单词小写命名（如 `hello.mp3`）
- **异步生成**: 首次访问时异步生成，前端轮询等待就绪
- **批量生成**: 应用启动、Excel 导入、数据导入后自动扫描缺失音频并依次生成，间隔 1s + 随机(1-2)s
- **防重复**: 批量任务运行中不会重复启动
- **自动播放**: 认读和拼写练习阶段切换单词时自动播放，默写阶段不自动播放（拼写提交后播放一次）
- **手动播放**: 三个阶段卡片均有 🔊 播放按钮可手动触发

## 开发注意事项
- 前端仅适配 1920×1080 及以上分辨率，不做移动端
- H2 数据文件在用户目录 ~/worddb.mv.db，重启不丢失
- Excel A-E 列严格映射: 生词/音标/含义/例句/例句释义
- CORS 已全局开放，Vite 通过 proxy 转发 /api 和 /audio 请求
- 音频目录 `review-backend/audio/` 已加入 .gitignore，不入版本库
- 词库管理页面支持编辑单词（生词、音标、含义、例句、例句释义），修改生词时后端检测重名冲突
