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
│       ├── entity/WordGroup.java         # 词组实体（编号/名称/parentId/sortOrder/isDefault，树形）
│       ├── entity/WordGroupMember.java   # 单词-词组多对多关联
│       ├── repository/WordRepository.java
│       ├── repository/ReviewConfigRepository.java
│       ├── repository/WordGroupRepository.java
│       ├── repository/WordGroupMemberRepository.java
│       ├── service/ExcelImportService.java       # Excel 导入（Upsert 逻辑 + 指定词组）
│       ├── service/ExcelExportService.java       # 按词组 Sheet 导出 Excel
│       ├── service/ReviewService.java            # 核心调度算法 + 记录结果 + 指定ID复习
│       ├── service/StatsService.java             # 统计看板
│       ├── service/EdgeTtsService.java           # Edge TTS Java 原生实现（WebSocket 协议）
│       ├── service/AudioGenerationTaskService.java # 音频批量生成任务管理
│       ├── service/DataExportImportService.java   # JSON 数据导入导出（含词组关系）
│       ├── service/WordGroupService.java         # 词组树管理 + 单词-词组关联
│       ├── controller/WordController.java        # /api/words/*（含 Excel 导出、词组筛选、批量调组）
│       ├── controller/ReviewController.java      # /api/review/*（含指定ID复习）
│       ├── controller/StatsController.java       # /api/stats/*
│       ├── controller/AudioController.java       # /api/audio/*
│       ├── controller/WordGroupController.java   # /api/groups/*
│       └── dto/                                  # 请求/响应 DTO
├── review-frontend/                   # Vue 3 前端
│   ├── vite.config.js                 # 含 /api、/audio 代理到 localhost:8080
│   └── src/
│       ├── main.js                    # 注册 ElementPlus + Router
│       ├── api/index.js               # Axios 封装（含 exportData、exportExcel）
│       ├── router/index.js            # 路由: /words, /groups, /review, /stats, /data
│       ├── components/Layout.vue      # 左菜单+右内容布局
│       ├── utils/group.js             # 词组树加载/树选项转换/默认词组查找
│       └── views/
│           ├── WordManage.vue         # 词库管理（词组筛选+表格+编辑+删除+调整词组+批量生成音频+复查选框+复习按钮）
│           ├── GroupManage.vue        # 词组管理（树形表格，增删改查，子词组）
│           ├── ReviewCenter.vue       # 复习中心（认读+拼写练习+默写+听音默写四阶段）
│           ├── DataManage.vue         # 数据管理（JSON导入导出+Excel导入需选词组+按Sheet导出）
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
| POST | `/api/words/import` | 上传 Excel (MultipartFile)，需指定 ?groupId= 导入目标词组 |
| GET | `/api/words/page` | 分页查询 (?page=&size=&keyword=&sortField=&sortOrder=&groupId=，groupId 含子孙词组) |
| PUT | `/api/words/{id}` | 更新单词内容（生词/音标/含义/例句/例句释义） |
| DELETE | `/api/words/{id}` | 删除单词（同时清理词组关联）；`?mode=group` 则只移出当前词组、保留单词 |
| DELETE | `/api/words/{id}/group/{groupId}` | 把单词移出指定词组（单词保留；若移出后无任何归属则落到默认词组） |
| PUT | `/api/words/{id}/recog-only` | 更新仅认读标记 |
| PUT | `/api/words/{id}/groups` | 覆盖式设置单词所属词组 {groupIds:[...]}（传空回落到默认词组） |
| POST | `/api/words/batch-groups` | 批量调整选中单词的词组 {wordIds, groupIds, mode: replace/add/remove} |
| GET | `/api/words/export` | 导出全部数据为 JSON version 2（含词组树和单词词组关系） |
| GET | `/api/words/export-excel` | 按词组导出 Excel，每个词组一个 Sheet（含「未分组」Sheet）|
| GET | `/api/review/config` | 获取复习配置 |
| PUT | `/api/review/config` | 更新复习配置 |
| GET | `/api/review/generate` | 生成复习列表（核心，返回含 recogOnly 字段） |
| GET | `/api/review/generate-by-ids` | 根据指定单词ID生成复习列表 (?ids=1,2,3) |
| POST | `/api/review/record` | 上报复习结果 {wordId, mode, result} |
| GET | `/api/stats/dashboard` | 统计看板数据 |
| GET | `/api/audio/{word}` | 获取单词音频（已存在返回 MP3，否则 202 触发异步生成） |
| GET | `/api/audio/{word}/status` | 查询音频生成状态（ready/generating） |
| POST | `/api/audio/batch` | 触发批量音频生成（运行中则不重复） |
| GET | `/api/audio/batch/status` | 查询批量生成状态（running/idle） |
| GET | `/api/groups/tree` | 词组树（含每个词组的直接单词数 wordCount） |
| GET | `/api/groups/default-id` | 内置默认词组ID |
| POST | `/api/groups` | 新增词组 {code?, name, parentId?, sortOrder?}（code 留空自动生成 Gxxx） |
| PUT | `/api/groups/{id}` | 更新词组 {code, name, parentId, sortOrder}（防成环校验） |
| DELETE | `/api/groups/{id}` | 删除词组（默认词组/有子词组不可删） |

## 核心算法：复习调度（ReviewService.generateReviewList）
1. **强制召回**: last_review_date IS NULL 或距今 > maxDormantDays 天 → 直接入围
2. **综合评分**: Score = E×0.7 + normalized_H×0.3
   - E = (recog_wrong + spell_wrong + 1) / (review_total + 2)
   - H = 天数×0.5 - review_total×0.1（Min-Max 归一化）
3. **随机抽样**: 取前 2×batchSize 候选，shuffle 后取 batchSize 个

## 关键业务规则
- **Upsert**: Excel/JSON 导入时 word 已存在则更新内容字段，保留复习统计
- **词组树**: 词组支持父子树形结构（parentId），编号 code 全局唯一；内置一个「默认词组」(G001, isDefault)
- **词组排序**: 词组 sortOrder 数值越小越靠前，相同则按 code 升序；全局生效（词组管理树、词组筛选下拉、数据管理导入词组下拉、按词组导出 Excel 的 Sheet 顺序、单词行的词组标签顺序）。旧数据启动时统一补 0
- **默认归属**: 应用启动时创建默认词组，并把所有无词组归属的单词自动补进默认词组；新增/无目标词组导入的单词默认进默认词组
- **多对多**: 一个单词可归属于多个词组；词库管理编辑单词或「调整词组」可批量修改归属（覆盖/追加/移除三种模式）
- **按词组筛选**: 词库管理选择词组后，分页查询包含该词组所有子孙词组的单词
- **删除单词二选一**: 词库管理删除按钮弹二选一——「从本词组移除」只解除该词组归属、单词保留；若该单词仅属该词组则自动落到默认词组；「彻底删除」清除单词及其全部词组关联和复习统计（有二次确认）
- **词组删除限制**: 默认词组不可删除；有子词组的词组需先删除子词组；删除成环防护（上级不能是自身子孙）
- **Excel 导入需选词组**: 数据管理导入 Excel 前必须选择目标词组；已存在的单词只新增关联、不重复创建
- **Excel 按 Sheet 导出**: 每个词组一个 Sheet（A-E 列同导入格式），未归类单词导出到「未分组」Sheet，空词组不生成 Sheet
- **JSON 备份 v2**: 导出含词组树（parentCode 引用、sortOrder）和每个单词的 groups(code 数组)；老版本 v1（无词组关系）导入时仅补充默认词组归属，不破坏现有词组
- **四阶段复习**: Phase 1 认读(英→中) + Phase 2 拼写练习(显示全部信息+拼写框) + Phase 3 默写(中→英) + Phase 4 听音默写(仅播放音频+拼写)
- **五种复习模式**: 完整复习(认读+拼写+默写) / 仅认读 / 仅拼写练习 / 仅默写 / 仅听音默写
- **自定义复习**: 词库管理页面选中单词后可自定义阶段组合（认读/拼写练习/默写/听音默写可复选），跳转到复习中心执行
- **仅认读标记 (recogOnly)**: 标记为仅认读的单词不参与拼写练习、默写和听音默写阶段
- **休眠判定**: consecutive_correct >= 5 → is_dormant=true
- **拼写校验**: 忽略大小写与首尾空格，Unicode 连字符统一归一化为普通连字符 (U+002D)
- **默写正确率统计**: 仅统计第一轮默写首次尝试结果，重复尝试不覆盖
- **防重入**: 拼写练习、默写和听音默写阶段，正确后等待自动跳转期间忽略后续回车
- **H2 Console**: http://localhost:8080/h2-console (JDBC URL: jdbc:h2:~/worddb, user: sa, password: 空)

## 音频发音系统
- **实现方式**: Java 原生实现 Edge TTS WebSocket 协议（EdgeTtsService），无需 Python 依赖
- **协议细节**: WSS 连接微软 Edge 在线 TTS 服务，DRM token 生成 (SHA-256)，SSML 语音合成请求，二进制 MP3 音频接收
- **默认语音**: en-US-EmmaMultilingualNeural
- **音频缓存**: 文件存储在 `review-backend/audio/` 目录，按单词小写命名（如 `hello.mp3`）
- **异步生成**: 首次访问时异步生成，前端轮询等待就绪
- **批量生成**: 应用启动、Excel 导入、数据导入后自动扫描缺失音频并依次生成，间隔 1s + 随机(1-2)s
- **防重复**: 批量任务运行中不会重复启动
- **自动播放**: 认读、拼写练习和听音默写阶段切换单词时自动播放，默写阶段不自动播放（拼写提交后播放一次）
- **手动播放**: 四个阶段卡片均有 🔊 播放按钮可手动触发

## 开发注意事项
- 前端仅适配 1920×1080 及以上分辨率，不做移动端
- H2 数据文件在用户目录 ~/worddb.mv.db，重启不丢失
- Excel A-E 列严格映射: 生词/音标/含义/例句/例句释义
- Excel 导出格式与导入一致，A-E 列 + 标题行，按 ID 正序
- CORS 已全局开放，Vite 通过 proxy 转发 /api 和 /audio 请求
- 音频目录 `review-backend/audio/` 已加入 .gitignore，不入版本库
- 词库管理页面支持编辑单词（生词、音标、含义、例句、例句释义、所属词组），修改生词时后端检测重名冲突
- 词库管理表格支持 ID 列服务端排序（sortable="custom"，sortField/sortOrder 参数传后端）
- 词库管理表格左侧有复选框，选中后可「调整词组」或点击复习按钮自定义阶段复习
- 词组树状下拉使用 el-tree-select（check-strictly 独立勾选，含子词组），词组选项用 `utils/group.js` 的 toTreeOptions 转换
- 分页查询接口支持 sortField（默认 id）和 sortOrder（默认 asc）参数
