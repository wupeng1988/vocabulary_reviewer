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
├── backend/                          # Spring Boot 后端
│   ├── pom.xml
│   └── src/main/java/com/wordreview/
│       ├── WordReviewApplication.java    # 启动类
│       ├── entity/Word.java              # 单词实体（含 @Transient 评分字段）
│       ├── entity/ReviewConfig.java      # 复习配置实体
│       ├── repository/WordRepository.java
│       ├── repository/ReviewConfigRepository.java
│       ├── service/ExcelImportService.java   # Excel 导入（Upsert 逻辑）
│       ├── service/ReviewService.java        # 核心调度算法 + 记录结果
│       ├── service/StatsService.java         # 统计看板
│       ├── controller/WordController.java    # /api/words/*
│       ├── controller/ReviewController.java  # /api/review/*
│       ├── controller/StatsController.java   # /api/stats/*
│       └── dto/                              # 请求/响应 DTO
├── frontend/                         # Vue 3 前端
│   ├── vite.config.js                # 含 /api 代理到 localhost:8080
│   └── src/
│       ├── main.js                   # 注册 ElementPlus + Router
│       ├── api/index.js              # Axios 封装
│       ├── router/index.js           # 三路由: /words, /review, /stats
│       ├── components/Layout.vue     # 左菜单+右内容布局
│       └── views/
│           ├── WordManage.vue        # 词库管理（上传+表格+搜索删除）
│           ├── ReviewCenter.vue      # 复习中心（认读+默写双阶段）
│           └── StatsDashboard.vue    # 统计看板（卡片+ECharts图表）
└── 本地智能单词复习系统.md            # 需求文档
```

## 启动方式
```bash
# 后端 (端口 8080)
cd backend && mvn spring-boot:run

# 前端 (端口 5173, 代理到后端)
cd frontend && npm run dev
```
访问 http://localhost:5173

## API 接口
| 方法 | 路径 | 功能 |
|------|------|------|
| POST | `/api/words/import` | 上传 Excel (MultipartFile) |
| GET | `/api/words/page` | 分页查询 (?page=&size=&keyword=) |
| DELETE | `/api/words/{id}` | 删除单词 |
| GET | `/api/review/config` | 获取复习配置 |
| PUT | `/api/review/config` | 更新复习配置 |
| GET | `/api/review/generate` | 生成复习列表（核心） |
| POST | `/api/review/record` | 上报复习结果 {wordId, mode, result} |
| GET | `/api/stats/dashboard` | 统计看板数据 |

## 核心算法：复习调度（ReviewService.generateReviewList）
1. **强制召回**: last_review_date IS NULL 或距今 > maxDormantDays 天 → 直接入围
2. **综合评分**: Score = E×0.7 + normalized_H×0.3
   - E = (recog_wrong + spell_wrong + 1) / (review_total + 2)
   - H = 天数×0.5 - review_total×0.1（Min-Max 归一化）
3. **随机抽样**: 取前 2×batchSize 候选，shuffle 后取 batchSize 个

## 关键业务规则
- **Upsert**: Excel 导入时 word 已存在则更新内容字段，保留复习统计
- **双阶段复习**: Phase 1 认读(英→中) + Phase 2 默写(中→英)，无论 Phase 1 结果 Phase 2 必执行
- **休眠判定**: consecutive_correct >= 5 → is_dormant=true
- **拼写校验**: 忽略大小写与首尾空格
- **H2 Console**: http://localhost:8080/h2-console (JDBC URL: jdbc:h2:~/worddb, user: sa, password: 空)

## 开发注意事项
- 前端仅适配 1920×1080 及以上分辨率，不做移动端
- H2 数据文件在用户目录 ~/worddb.mv.db，重启不丢失
- Excel A-E 列严格映射: 生词/音标/含义/例句/例句释义
- CORS 已全局开放，Vite 通过 proxy 转发 /api 请求
