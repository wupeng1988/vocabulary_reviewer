# 📖 Word Review Pro — 本地智能单词复习系统

> 基于 Spring Boot + Vue 3 的本地桌面端单词复习工具。核心价值是**智能调度算法**：高频打击顽固生词 + 强制唤醒长期沉睡词，杜绝单词"永久沉底"。

---

## ✨ 功能特性

- 📥 **Excel 导入词库** — 支持 `.xls` / `.xlsx`，自动去重更新，保留复习统计
- 🧠 **智能调度算法** — 三级筛选（强制召回 → 综合评分 → 随机抽样），兼顾紧急度与饥饿度
- 📖 **双阶段复习** — Phase 1 认读（英→中） + Phase 2 默写（中→英），"认识"不等于"会写"
- 📊 **统计看板** — 词库概览、易错词 Top 5、长期未复习词等 ECharts 可视化
- 💾 **零配置持久化** — H2 文件数据库，重启不丢数据，无需安装 MySQL
- 🎯 **单机即用** — 无需登录、无需联网，开箱即用

---

## 📸 界面预览

### 词库管理

上传 Excel 导入单词，支持搜索、分页浏览和删除。

![词库管理](imgs/词库管理.png)

### 数据管理

词库总览，查看每个单词的复习统计信息。

![数据管理](imgs/数据管理.png)

### 复习中心

双阶段复习入口，顶部显示批次大小和进度，可调整复习配置。

![复习中心-首页](imgs/复习中心-首页.png)

### 认读复习

展示英文单词和音标，点击"认识"或"不认识"后立即显示释义和例句，强化记忆。

| 认读 | 显示释义 |
|:---:|:---:|
| ![认读复习](imgs/认读复习.png) | ![认读复习-释义](imgs/认读复习-释义.png) |

### 默写复习

展示中文含义，输入英文单词校验拼写，忽略大小写和首尾空格。

![默写复习](imgs/默写复习.png)

### 统计看板

ECharts 可视化展示词库总量、易错词排行、长期未复习词等数据。

![统计看板](imgs/统计看板.png)

---

## 🛠 技术栈

| 层级 | 技术 | 版本 |
|:---|:---|:---|
| **后端** | Spring Boot + Spring Data JPA | 2.7.18 |
| **数据库** | H2 (文件模式) | 内嵌 |
| **Excel 解析** | Apache POI | 5.2.5 |
| **前端** | Vue 3 + Vite | 3.5.x / 8.x |
| **UI 组件库** | Element Plus | 2.14.x |
| **图表** | ECharts | 6.x |
| **HTTP** | Axios | 1.18.x |
| **路由** | Vue Router | 4.x |
| **工具** | Lombok | — |

---

## 📁 项目结构

```
vocabulary-review/
├── review-backend/                   # Spring Boot 后端
│   ├── pom.xml
│   └── src/main/java/com/wordreview/
│       ├── WordReviewApplication.java    # 启动类
│       ├── entity/                       # 实体类
│       │   ├── Word.java                     # 单词实体（含 @Transient 评分字段）
│       │   └── ReviewConfig.java             # 复习配置实体
│       ├── repository/                   # 数据访问层
│       │   ├── WordRepository.java
│       │   └── ReviewConfigRepository.java
│       ├── service/                      # 业务逻辑层
│       │   ├── ExcelImportService.java       # Excel 导入（Upsert 逻辑）
│       │   ├── ReviewService.java            # 核心调度算法 + 记录结果
│       │   └── StatsService.java             # 统计看板
│       ├── controller/                   # 接口层
│       │   ├── WordController.java           # /api/words/*
│       │   ├── ReviewController.java         # /api/review/*
│       │   └── StatsController.java          # /api/stats/*
│       └── dto/                          # 请求/响应 DTO
│
├── review-frontend/                  # Vue 3 前端
│   ├── package.json
│   ├── vite.config.js                # 含 /api 代理到 localhost:8080
│   └── src/
│       ├── main.js                   # 注册 ElementPlus + Router
│       ├── api/index.js              # Axios 封装
│       ├── router/index.js           # 三路由: /words, /review, /stats
│       ├── components/Layout.vue     # 左菜单 + 右内容布局
│       └── views/
│           ├── WordManage.vue        # 词库管理（上传+表格+搜索删除）
│           ├── ReviewCenter.vue      # 复习中心（认读+默写双阶段）
│           └── StatsDashboard.vue    # 统计看板（卡片+ECharts图表）
│
├── imgs/                             # 截图
├── dev.sh                            # Linux/Mac 一键启停脚本
├── dev.cmd                           # Windows CMD 一键启停脚本
├── dev.ps1                           # Windows PowerShell 一键启停脚本
└── 本地智能单词复习系统.md            # 详细需求文档
```

---

## 🚀 快速开始

### 环境要求

- **Java**: JDK 11+（推荐 JDK 17）
- **Maven**: 3.6+
- **Node.js**: 16+

### 方式一：一键启动（推荐）

**Windows CMD:**
```bash
dev.cmd start
```

**Windows PowerShell:**
```powershell
.\dev.ps1 start
```

**Linux / Mac:**
```bash
chmod +x dev.sh
./dev.sh start
```

启动后访问 http://localhost:5173

> 其他命令：`stop`（停止）、`status`（查看状态）

### 方式二：手动启动

```bash
# 1. 启动后端 (端口 8080)
cd review-backend
mvn spring-boot:run

# 2. 启动前端 (端口 5173, 代理到后端)
cd review-frontend
npm install
npm run dev
```

访问 http://localhost:5173

---

## 📡 API 接口

| 方法 | 路径 | 功能 |
|:---|:---|:---|
| POST | `/api/words/import` | 上传 Excel (MultipartFile) |
| GET | `/api/words/page` | 分页查询 (`?page=&size=&keyword=`) |
| DELETE | `/api/words/{id}` | 删除单词 |
| GET | `/api/review/config` | 获取复习配置 |
| PUT | `/api/review/config` | 更新复习配置 |
| GET | `/api/review/generate` | 生成本次复习列表（核心接口） |
| POST | `/api/review/record` | 上报复习结果 `{wordId, mode, result}` |
| GET | `/api/stats/dashboard` | 统计看板数据 |

---

## 🧠 核心算法：复习调度

系统每次生成复习列表时，严格执行**三级筛选 + 随机扰动**机制：

### Step 1: 强制召回（破饥饿硬约束）
- `last_review_date IS NULL`（新词）或距今 > `maxDormantDays` 天 → **直接入围**
- 不受任何优先级影响，确保沉睡词不会永久沉底

### Step 2: 综合评分排序
若强制召回词不足 `batchSize`，剩余名额按动态评分 `Score` 降序填充：

```
紧急因子 E = (recog_wrong + spell_wrong + 1) / (review_total + 2)
饥饿因子 H = 天数 × 0.5 - review_total × 0.1  （Min-Max 归一化）
最终得分 Score = E × 0.7 + H_normalized × 0.3
```

- **紧急因子权重 70%**：错误越多，越需要复习
- **饥饿因子权重 30%**：时间越久未复习，越需要唤醒

### Step 3: 候选池随机抽样（防死锁）
- 取评分前 `2 × batchSize` 个词作为候选池
- Shuffle 后随机抽取 `batchSize` 个，避免每次列表完全一致

---

## 📋 业务规则

| 规则 | 说明 |
|:---|:---|
| **Upsert 导入** | Excel 中 word 已存在则更新内容字段，保留复习统计 |
| **双阶段必执行** | Phase 1 认读 + Phase 2 默写，无论 Phase 1 结果 Phase 2 都强制执行 |
| **休眠判定** | `consecutive_correct >= 5` → `is_dormant = true` |
| **拼写校验** | 忽略大小写与首尾空格 |
| **数据持久化** | H2 文件模式，数据文件 `~/worddb.mv.db`，重启不丢失 |

---

## 🔧 调试工具

**H2 Console**: http://localhost:8080/h2-console

| 参数 | 值 |
|:---|:---|
| JDBC URL | `jdbc:h2:~/worddb` |
| Username | `sa` |
| Password | _(空)_ |

---

## 📄 Excel 导入格式

Excel A~E 列严格映射：

| 列 | 字段 | 说明 |
|:---|:---|:---|
| A | 生词 | 英文单词（业务唯一键） |
| B | 音标 | 如 `/əˈkædəmi/` |
| C | 含义 | 中文释义 |
| D | 例句 | 英文例句 |
| E | 例句释义 | 例句中文翻译 |

> 支持 `.xls` 和 `.xlsx` 格式，空行自动跳过。

---

## ⚠️ 注意事项

- 前端仅适配 **1920×1080** 及以上分辨率，不做移动端适配
- 系统为**单机单用户**，无登录鉴权，无多用户隔离
- CORS 已全局开放，Vite 通过 proxy 转发 `/api` 请求
- 详细需求文档见 [本地智能单词复习系统.md](本地智能单词复习系统.md)

---

## 📜 License

MIT
