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
│   ├── audio/                         # TTS 音频缓存（约 4187 个 mp3 / 49MB，已入版本库）
│   └── src/main/java/com/wordreview/
│       ├── WordReviewApplication.java    # 启动类
│       ├── entity/Word.java              # 单词实体（含 recogOnly 仅认读标记 + 最近N次拼写错误追踪）
│       ├── entity/DifficultConfig.java   # 易错词阈值单例行（窗口次数/连续错误/窗口内累计错误）
│       ├── entity/AiConfig.java          # AI 配置单例行（provider/apiKey/baseUrl/model，独立于词库数据）
│       ├── entity/ProxyConfig.java       # 网络代理配置单例行（enabled/host/port，独立于词库数据）
│       ├── entity/ReviewConfig.java      # 复习配置实体
│       ├── entity/WordGroup.java         # 词组实体（编号/名称/parentId/sortOrder/isDefault/isDifficult，树形）
│       ├── entity/WordGroupMember.java   # 单词-词组多对多关联
│       ├── repository/WordRepository.java
│       ├── repository/AiConfigRepository.java
│       ├── repository/ProxyConfigRepository.java
│       ├── repository/ReviewConfigRepository.java
│       ├── repository/WordGroupRepository.java
│       ├── repository/WordGroupMemberRepository.java
│       ├── repository/DifficultConfigRepository.java
│       ├── service/ExcelImportService.java       # Excel 导入（Upsert 逻辑 + 指定词组）
│       ├── service/ExcelExportService.java       # 按词组 Sheet 导出 Excel
│       ├── service/ReviewService.java            # 核心调度算法 + 记录结果 + 指定ID复习
│       ├── service/StatsService.java             # 统计看板
│       ├── service/EdgeTtsService.java           # Edge TTS Java 原生实现（WebSocket 协议）
│       ├── service/EdgeTtsDrm.java               # Sec-MS-GEC v2 token（魔改 SHA-256 + CS64，逐行移植上游 drm.py）
│       ├── service/AudioGenerationTaskService.java # 音频批量生成任务管理
│       ├── service/DataExportImportService.java   # JSON 数据导入导出（含词组关系）
│       ├── service/WordGroupService.java         # 词组树管理 + 单词-词组关联 + 内置默认/易错词组
│       ├── service/DifficultConfigService.java   # 易错词阈值单例读写（两个阈值不得大于窗口次数）
│       ├── service/DifficultWordService.java     # 最近N次拼写历史维护 + 自动/手动加进易错词组
│       ├── service/AiConfigService.java          # AI 配置单例读写
│       ├── service/ProxyConfigService.java       # 代理配置单例读写 + 按配置构造 HttpClient（按签名缓存）
│       ├── service/AiParseService.java           # 调大模型批量解析（OpenAI 兼容 /chat/completions）
│       ├── controller/WordController.java        # /api/words/*（含 Excel 导出、词组筛选、批量调组）
│       ├── controller/ReviewController.java      # /api/review/*（含指定ID复习）
│       ├── controller/StatsController.java       # /api/stats/*
│       ├── controller/AudioController.java       # /api/audio/*
│       ├── controller/WordGroupController.java   # /api/groups/*
│       ├── controller/DifficultWordController.java # /api/difficult/*（阈值配置 + 手动加易错词组）
│       ├── controller/AiController.java          # /api/ai/config
│       ├── controller/ProxyController.java       # /api/proxy/*（配置读写 + 代理/直连连通性测试）
│       ├── config/SpaFallbackConfig.java          # 发布包 SPA 路由回退过滤器（dev 环境自动跳过）
│       └── dto/                                  # 请求/响应 DTO（含 AiWordDTO：AI 解析单词条）
├── review-frontend/                   # Vue 3 前端
│   ├── vite.config.js                 # 含 /api、/audio 代理到 localhost:8080
│   └── src/
│       ├── main.js                    # 注册 ElementPlus + Router
│       ├── api/index.js               # Axios 封装（含 exportData、exportExcel）
│       ├── router/index.js            # 路由: /words, /difficult-words, /groups, /review, /stats, /data, /ai, /proxy, /difficult
│       ├── components/Layout.vue      # 左菜单+右内容布局
│       ├── utils/group.js             # 词组树加载/树选项转换/默认词组查找
│       └── views/
│           ├── WordManage.vue         # 词库管理（词组筛选+表格+编辑+删除+调整词组+批量生成音频+复查选框+复习按钮）；
│           │                          # 也承载「易错词库」页：路由传 difficultMode=true，固定查易错词组、
│           │                          # 隐藏词组筛选与新增/AI解析/生成音频/调整词组/批量删除，换成「移出易错词组」
│           ├── GroupManage.vue        # 词组管理（树形表格，增删改查，子词组）
│           ├── ReviewCenter.vue       # 复习中心（认读+拼写练习+默写+听音默写四阶段）
│           ├── DataManage.vue         # 数据管理（JSON导入导出+Excel导入需选词组+按Sheet导出）
│           ├── AiSettings.vue         # AI 设置（服务商/API Key/接口地址/模型，支持 DeepSeek 与通义千问）
│           ├── DifficultSettings.vue  # 易错词配置（统计窗口次数/连续错误次数/窗口内累计错误次数）
│           ├── ProxySettings.vue      # 网络代理设置（启用开关/地址端口，测试代理与测试直连）
│           └── StatsDashboard.vue    # 统计看板（卡片+ECharts图表）
├── dev.sh                             # 一键启停脚本 (start|stop|restart|status)
├── release.sh                         # 打包分发脚本 (|--no-jre|--no-zip)，产物在 release/
├── packaging/                         # 发布包内的启动脚本与说明文档（start.sh/start.bat/README.txt/README.md/application.properties）
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

## 发布打包（分发给无开发环境的用户）
```bash
./release.sh              # 完整包（含内置 JRE）→ release/WordReviewPro-<版本>-<系统>-<架构>.zip
./release.sh --no-jre     # 只有 jar + 启动脚本，对方需自装 Java 11+
./release.sh --no-zip     # 只出目录不压缩
```
包结构：`WordReviewPro.jar`（后端 + 前端静态页）+ `jre/`（jlink 裁剪运行时）+ `Start.command`/`start.sh`/`start.bat` + `README.txt`（速查）+ `README.md`（小白上手指南）。对方解压后双击即可，浏览器自动打开 http://localhost:8080。

打包链路与约束：
- `npm run build` → `review-frontend/dist`，由 **pom 的 `release` profile** 作为额外资源并入 `BOOT-INF/classes/static/`
- **该 profile 不激活就完全不生效**：`./dev.sh start` 依旧是后端 8080 + 前端 5173 两服务，行为不变
- `SpaFallbackConfig`（`config/` 包）：非 `/api`、非 `/h2-console`、无扩展名的 GET 请求回退到 `index.html`，支撑 history 路由刷新；**没有 `static/index.html` 时直接放行**，开发环境等同改动前。类名不能与 `@Bean` 方法同名，否则 `BeanDefinitionOverrideException` 启动失败
- `release.sh` 结束时执行 `mvn clean`，避免 `target/classes/static` 残留污染后续 `spring-boot:run`
- jlink 模块列表见 `release.sh`，只在同架构系统上可生成 → **在谁的机器上打包就只能给谁用**
- 单机单用户：`~/worddb` 为 H2 文件库，**同一时间只能跑一个实例**，与 dev 实例并存会报 `The file is locked`
- 发布包不内置 `audio/` 缓存（49MB 且是用户自己的词），首次运行按需联网生成
- **端口可改**：包根目录 `application.properties`（`server.port=`，默认 8080）。启动脚本解析它做端口占用检测/就绪探测/开浏览器，并传 `--server.port`（**必须在 `-jar` 之后**，放前面 JVM 会报 `Unrecognized option`）；Spring 自身也读 `file:./application.properties` 覆盖 jar 内 `application.yml`。脚本内**不硬编码端口**；dev 侧 `vite.config.js` proxy 仍写死 8080
- 包内文件名一律 ASCII（`Start.command`/`README.txt`/`README.md`）：macOS 的 `zip` 不设 UTF-8 文件名标志，中文名在 Windows 解压会变乱码；`release.sh` 构建后用 python3 校验非 ASCII 文件名并失败退出

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
| POST | `/api/words/batch-ai` | 批量AI解析添加 {words:[...], groupId?}，返回 added/existing/duplicated/failed/failedWords/message |
| GET | `/api/words/export` | 导出全部数据为 JSON version 2（含词组树和单词词组关系） |
| GET | `/api/words/export-excel` | 按词组导出 Excel，每个词组一个 Sheet（含「未分组」Sheet）|
| GET | `/api/review/config` | 获取复习配置 |
| PUT | `/api/review/config` | 更新复习配置 |
| GET | `/api/review/generate` | 生成复习列表（核心，返回含 recogOnly 字段） |
| GET | `/api/review/generate-by-ids` | 根据指定单词ID生成复习列表 (?ids=1,2,3) |
| POST | `/api/review/record` | 上报复习结果 {wordId, mode, result}，mode 为 RECOG/PRACTICE/SPELL；响应含 addedToDifficult、difficultReason |
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
| GET | `/api/difficult/config` | 读取易错词阈值（windowSize/consecutiveWrongLimit/recentWrongLimit + 内置易错词组 name/code/id） |
| PUT | `/api/difficult/config` | 保存易错词阈值（两个阈值必须 ≤ 窗口次数，否则 400） |
| POST | `/api/difficult/words/{wordId}/add` | 手动把单词加入内置易错词组，added=false 表示本来就在里面 |
| GET | `/api/difficult/words/{wordId}` | 单词易错词状态（inDifficultGroup + recentSpellWrong + windowSize） |
| GET | `/api/ai/config` | 读取 AI 配置（provider/apiKey/baseUrl/model/configured） |
| PUT | `/api/ai/config` | 保存 AI 配置 |
| GET | `/api/proxy/config` | 读取代理配置（enabled/host/port/configured） |
| PUT | `/api/proxy/config` | 保存代理配置（启用时校验地址与端口），保存即生效无需重启 |
| POST | `/api/proxy/test` | 测试代理连通性 {host, port}，返回 success/latencyMs/message |
| POST | `/api/proxy/test-direct` | 测试直连（绕开代理与系统代理属性） |

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
- **最近 N 次拼写错误次数 (recentSpellWrong)**: 拼写练习 / 默写 / 听音默写 三个阶段的**每一次提交**都算一次拼写。
  Word 上存 `spell_recent_history`（'1'=拼错，'0'=拼对，末尾最新，长度恒 ≤ 窗口次数）+ 冗余列 `recent_spell_wrong`。
  冗余一列是因为要在 SQL 里按它排序，JPA 统计不了字符串里 '1' 的个数。窗口次数可在「易错词配置」改，
  改小会立刻把历史串截断到新长度并重算错误次数。词库管理的「最近拼错」列排在「复习次数」之前，
  可服务端排序（`sortField=recentSpellWrong`，已进白名单），表头 tooltip 里写当前窗口次数
- **易错词库页面** (`/difficult-words`，词库管理正下方菜单): 复用 `WordManage.vue`，路由传 `props: { difficultMode: true }`，
  做的减法是——固定传 `groupId=<易错词组ID>`（含其子词组）、隐藏「按词组筛选」下拉、
  表格隐藏 词组/复习次数/状态/仅认读 四列、隐藏 批量AI解析添加/新增/生成音频/调整词组/批量删除 五个按钮，
  换成「➖ 移出易错词组」（勾选后可用，走既有的 `POST /api/words/batch-delete` `mode=group` + `groupId`，
  单词保留；若它是唯一归属则自动落到默认词组）。搜索、排序、编辑、删除、自定义复习按钮照常可用。
  **必须先 await 拿到 difficultGroupId 再查列表**，否则 groupId 为空会查成整个词库。
  默认排序是「最近拼错」倒序（错得多的排最前），表头通过 `:default-sort` 同步显示箭头；
  取消排序时也回落到这个默认值，而不是词库管理的 ID 正序
- **内置易错词组**: 系统启动时自动创建一个根词组「易错词组」（`WordGroup.isDifficult=true`），
  和默认词组一样**不可删除**（词组管理里删除按钮置灰，后端也拦），但可以改名、排序、挂上级、删单词归属。
  词组标签在词库管理里是橙色（warning），默认词组仍是红色
- **自动加入易错词组**: 拼写类阶段（PRACTICE/SPELL）上报结果后判断，满足任一即自动加入并弹提示：
  1. 连续拼写错误 ≥ consecutiveWrongLimit（默认 3，拼对一次清零）
  2. 最近 windowSize 次拼写累计错误 ≥ recentWrongLimit（默认 5，不要求连续）
  已在组里则不重复提示；`/api/review/record` 响应返回 `addedToDifficult`/`difficultReason` 由前端弹警告
- **手动加入易错词组**: 拼写练习 / 默写 / 听音默写 三张卡片上都有「➕ 加入易错词组」按钮
  （调 `POST /api/difficult/words/{id}/add`，已在组内返回 added=false 并提示「本来就在」）。
  词库管理里也可以走既有的「调整词组 → 追加 → 易错词组」
- **卡片上的易错词状态**: `/api/review/generate` 和 `/generate-by-ids` 的每条都带 `inDifficultGroup`
  （`ReviewController.toDisplayList` 用一次 `filterWordIdsInGroup` 批量回填，不逐词查库）。
  前端据此在 `!inDifficultGroup` 时显示按钮、否则换成「✅ 已加入易错词组」标签；
  自动加入或手动加入成功后就地把 `currentWord.inDifficultGroup` 置 true，不用等下一轮生成列表
- **易错词配置页面**: 左侧「⚙️ 系统设置」菜单组下的「🎯 易错词配置」（`/difficult`）。
  三个数字：统计窗口次数（默认 30，上限 100）、连续错误次数（默认 3）、窗口内累计错误次数（默认 5），
  后两个都必须 ≤ 窗口次数。配置存在 `difficult_config` 单例行，**参与 JSON 备份的导出与导入**
  （纯数字、无隐私，换机器要保留）
- **四阶段复习**: Phase 1 认读(英→中) + Phase 2 拼写练习(显示全部信息+拼写框) + Phase 3 默写(中→英) + Phase 4 听音默写(仅播放音频+拼写)。
  听音默写拼错时的「❌ 错误，请重新输入」标签和下方单词详情块必须各自包一层 `<div>`：
  外层卡片是 `text-align:center`，两者都是 inline-block 会挤到同一行。详情块给定 `width:480px; margin:0 auto` 居中加宽
- **五种复习模式**: 完整复习(认读+拼写+默写) / 仅认读 / 仅拼写练习 / 仅默写 / 仅听音默写
- **recordResult 的三种 mode 口径**（`ReviewService.recordResult`）:
  - `RECOG` 认读 —— 只更新认读统计 + 复习次数 + 连续正确数
  - `PRACTICE` 拼写练习 —— **只**维护拼写错误追踪和易错词组判断，不动 `reviewTotal`/`lastReviewDate`/
    `consecutiveCorrect`/`spellCorrect`/`spellWrong`。拼写练习是看着释义写，比默写简单，
    混进调度统计会打乱原有排序，所以刻意保持它「不计入复习统计」的老行为
  - `SPELL` 默写 + 听音默写 —— 沿用原有默写统计口径，再叠加上面的拼写错误追踪
  三个阶段的前端上报都走 `ReviewCenter.reportSpellingResult()`，自动加易错词组的提示也从它的响应里取
- **自定义复习**: 词库管理页面选中单词后可自定义阶段组合（认读/拼写练习/默写/听音默写可复选），跳转到复习中心执行
- **仅认读标记 (recogOnly)**: 标记为仅认读的单词不参与拼写练习、默写和听音默写阶段
- **休眠判定**: consecutive_correct >= 5 → is_dormant=true
- **拼写校验**: 忽略大小写与首尾空格，Unicode 连字符统一归一化为普通连字符 (U+002D)
- **默写正确率统计**: 仅统计第一轮默写首次尝试结果，重复尝试不覆盖
- **防重入**: 拼写练习、默写和听音默写阶段，正确后等待自动跳转期间忽略后续回车
- **键盘快捷键**: 拼写练习/默写/听音默写 输入框 Enter 提交，**Shift+Enter 播放当前单词发音且不提交**
  （Vue 的 `@keyup.enter` 只看按键不看修饰键，Shift+Enter 也会命中，必须在处理器里用 `e.shiftKey` 分流，
  见 `onPracticeEnter` / `onSpellEnter` / `onDictationEnter`）；认读阶段 ← 上一个、→ 下一个（已作答后生效）、
  Enter = 认识 / 下一个。三个阶段的输入框正下方都用 `.shortcut-hint` 常驻一行小字提示（kbd 键帽样式）
- **批量AI解析添加**: 词库管理页「批量AI解析添加」按钮（在「➕ 新增」左侧）。流程为
  **输入内部去重（大小写不敏感，保留首次原样）→ 排除词库已有词 → 交给 AI → 只用返回的原型 lemma 入库**；
  原型若已存在（含本批次内重复）计入「词库已包含」。AI 全部解析成功后才开始入库，中途失败不留半截数据。
  返回 message 形如「本次添加了 N 个，词库已包含 M 个（输入已去重 K 条）」
- **AI 配置独立于词库数据**: 存在单独的 `ai_config` 表，**JSON 备份导出不含它**（避免泄漏 API Key），
  **导入也不覆盖它**；未配置时点按钮直接提示并引导跳转「AI 设置」
- **AI / 代理配置都能清空**: 两个设置页都有「清空配置」按钮。
  AI 侧规则是「API Key / 接口地址 / 模型三项要么都填、要么都清空」，只填一半返回 400；
  全清空后 `configured=false`，批量AI解析会提示「尚未配置 AI 服务」。
  代理侧清空即关闭开关并抹掉地址，保存后自动回落直连
- **网络代理配置**: 存在单独的 `proxy_config` 表，同样**不进 JSON 备份的导出与导入**（避免泄漏代理地址）；
  只作用于后端出站的 Edge TTS 合成请求（见「音频发音系统」）。左侧菜单「🌐 网络代理」页面配置，
  保存后立即重建并热替换进程内唯一的 HttpClient，**无需重启**，
  正在跑的批量生成从下一个单词开始走新代理
- **代理的两个硬限制（都是 JDK `java.net.http.HttpClient` 的行为，实测确认）**:
  - **只支持 HTTP 代理**：`Proxy.Type.SOCKS` 会被静默忽略（`OpeningHandshake.proxyFor` 直接返回 null），
    所以界面上不提供 SOCKS 选项
  - **只支持免认证代理**：CONNECT 隧道的请求走 `PlainTunnelingConnection` 里的 `Exchange` 而不是
    `MultiExchange`，`AuthenticationFilter` 压根不参与，收到 407 也不会补发 `Proxy-Authorization`。
    实测带账号密码的代理必返 407，所以配置里**不设账号密码字段**（留着只会让人误填），
    提示语也直接告诉用户改用免认证的本地代理（如 Clash/V2Ray 端口、SSH -D 转发）
- **AI 接入协议**: OpenAI 兼容 `/chat/completions`，因此 DeepSeek（`https://api.deepseek.com` + `deepseek-chat`）
  和通义千问（`https://dashscope.aliyuncs.com/compatible-mode/v1` + `qwen-plus`）都能用，也支持自定义网关；
  每批 10 个词、超时 90s，返回内容要宽容解析（去代码块围栏、按 input 对齐），信息全空的条目判为解析失败不入库
- **H2 Console**: http://localhost:8080/h2-console (JDBC URL: jdbc:h2:~/worddb, user: sa, password: 空)

## 音频发音系统
- **实现方式**: Java 原生实现 Edge TTS WebSocket 协议（EdgeTtsService），无需 Python 依赖
- **协议细节**: WSS 连接微软 Edge 在线 TTS 服务，DRM token 生成 (SHA-256)，SSML 语音合成请求，二进制 MP3 音频接收
- **Sec-MS-GEC v2 token（2026-10 微软强制）**: 微软把 token 从 v1 换成 v2，**版本前缀也从 `1-` 改成 `2-`**，
  两者必须配套，否则握手直接 403。算法在 `EdgeTtsDrm`，逐行移植 rany2/edge-tts 的 `drm.py`：
  - 格式 `<CS64 16位hex>-<SHA256 64位hex>`，两段共用同一条盐串 = ticks（Windows 文件时间，向下取整到 5 分钟）+ trustedClientToken
  - **SHA256 段不是标准 SHA-256**：第一块的调度表、第 14 轮轮常量都被换成上游预置的魔数，
    再用修正项补回数据相关部分。写成「标准 SHA256(57个0 + 盐串)」会算出错误 token 并 403
  - Σ0/Σ1 是三个**循环右移**的异或，必须带左移回绕项；移植时最容易漏的就是这部分
  - 32 位无符号运算直接用 Java int 自然溢出，右移一律 `>>>`；`%.0f` 用 Locale.ROOT
- **时钟校准**: 握手 403 时取服务器 `Date` 头算出偏差写入 `clockSkewMillis` 后重试一次
  （对应 edge-tts 的 handle_client_response_error），每个进程最多校时一次
- **默认语音**: en-US-EmmaMultilingualNeural
- **音频缓存**: 文件存储在 `review-backend/audio/` 目录，按单词小写命名（如 `hello.mp3`）
- **异步生成**: 首次访问时异步生成，前端轮询等待就绪
- **批量生成**: 应用启动、Excel 导入、数据导入后自动扫描缺失音频并依次生成，间隔 1s + 随机(1-2)s
- **防重复**: 批量任务运行中不会重复启动
- **自动播放**: 认读、拼写练习和听音默写阶段切换单词时自动播放，默写阶段不自动播放（拼写提交后播放一次）
- **手动播放**: 四个阶段卡片均有 🔊 播放按钮可手动触发
- **走代理**: 合成请求用 `ProxyConfigService.getHttpClient()`（进程内**共用一个** HttpClient，
  只在首次取用和保存配置时重建；批量生成几百个单词全程复用，合成路径上只是一次 volatile 读，
  既不查库也不做字符串比较）。未启用代理时沿用 JDK 默认（读 `http.proxyHost` 等系统属性）；
  「测试直连」则强制 NO_PROXY，用来判断本机到底能不能直连。探测地址为
  `https://speech.platform.bing.com/`，收到任意 HTTP 状态码即视为链路通
  （407 除外：说明代理需要账号密码认证，JDK 不支持，见上文限制）

## 开发注意事项
- 前端仅适配 1920×1080 及以上分辨率，不做移动端
- H2 数据文件在用户目录 ~/worddb.mv.db，重启不丢失
- Excel A-E 列严格映射: 生词/音标/含义/例句/例句释义
- Excel 导出格式与导入一致，A-E 列 + 标题行，按 ID 正序
- CORS 已全局开放，Vite 通过 proxy 转发 /api 和 /audio 请求
- 音频目录 `review-backend/audio/` **已入版本库**（4187 个 mp3 / 49MB，无 ignore 规则）；发布包不内置 audio，用户首次运行按需联网生成
- 词库管理页面支持编辑单词（生词、音标、含义、例句、例句释义、所属词组），修改生词时后端检测重名冲突
- 词库管理表格支持服务端排序（`sortable="custom"`，`sortField`/`sortOrder` 参数传后端），
  当前可排序列：**ID / 生词 / 复习次数**
- **排序字段走白名单**：`WordController.SORT_FIELD_WHITELIST` 把 UI 字段名映射到实体属性，
  非法值返回 400（由 `@ExceptionHandler(IllegalArgumentException)` 统一兜底，避免变成 500）；
  新增可排序列必须同步补白名单
- **生词排序不区分大小写**：H2 默认 `COLLATION=OFF`，按 `String.compareTo` 会把 `Apple` 排在 `apple` 前面。
  Word 实体上加了 `@Formula("LOWER(word)") wordLower`（只读、不落库），`sortField=word` 映射到它，
  因此任何查询变体（含词组筛选、关键词搜索）都能直接用 `Sort.by` 排序，无需为每种组合再写 JPQL
- 词库管理表格左侧有复选框，选中后可「调整词组」或点击复习按钮自定义阶段复习
- 词库管理表格 **ID 列可点击**（`.id-link` 加下划线+主色提示），点开只读的「查看单词」弹窗：
  字段与布局跟编辑弹窗一致，所有输入框 `disabled`，底部只有「关闭」
- **同一组件挂多个路由时，`<router-view>` 必须绑 `:key`**（`Layout.vue` 里是 `:key="route.fullPath"`）。
  `/words` 和 `/difficult-words` 挂的都是 `WordManage`，Vue Router 在「同组件不同路由」之间跳转会
  **复用组件实例、不重新挂载**，`onMounted` 不执行，`words`/`keyword`/`filterGroupId` 原样留着——
  表现是换个菜单表格还是旧数据、在新页面点完搜索再切回去整页空掉。
  以后再加共用组件的路由不用改这里，但别把这个 `:key` 去掉
- **给已有表加列时不要写 `nullable = false`**：Hibernate 增量 DDL 会生成 `alter table xxx add column yyy integer not null`，
  H2 对已有数据直接报「NULL not allowed」，**该列整条 DDL 失败**，表里没有这列，之后所有查询都炸
  （实测 `word_group.is_difficult` 就踩过）。列声明写普通 `@Column`，启动时用 `findByXxxIsNull()` 补默认值，
  代码里再做 null 兜底
- 词组树状下拉使用 el-tree-select（check-strictly 独立勾选，含子词组），词组选项用 `utils/group.js` 的 toTreeOptions 转换
- 分页查询接口支持 sortField（默认 id）和 sortOrder（默认 asc）参数
