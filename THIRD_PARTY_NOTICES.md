# 第三方组件与来源说明

本项目（Word Review Pro）以 GPL-3.0 发布（见 [LICENSE](LICENSE)）。
下面列出项目里直接引入或移植的第三方代码、素材与协议实现。

---

## 1. edge-tts（Python 项目）— 协议实现的参考来源

* **上游项目**：[rany2/edge-tts](https://github.com/rany2/edge-tts)
* **上游许可证**：GNU Lesser General Public License v3.0
  （上游仅 `src/edge_tts/srt_composer.py` 一个文件为 MIT，其余全部为 LGPLv3）
* **Copyright**：Copyright (c) 2014-2023 Christopher Down；Copyright (c) 2025- rany \<rany@riseup.net\>
* **在本项目中的使用方式**：**参考并移植其 WebSocket 协议实现**，用 Java 重写。

涉及的文件：

| 本项目文件 | 对应上游文件 | 说明 |
|:---|:---|:---|
| `review-backend/src/main/java/com/wordreview/service/EdgeTtsDrm.java` | `src/edge_tts/drm.py` | Sec-MS-GEC v2 token 生成。**逐行移植**：魔改 SHA-256 的调度表与轮常量、CS64 编码、时钟盐串拼接方式都原样照抄，只把 Python 的 `& 0xFFFFFFFF` 换成 Java int 自然溢出、把 `>>` 换成 `>>>`。 |
| `review-backend/src/main/java/com/wordreview/service/EdgeTtsService.java` | `src/edge_tts/communicate.py`、`constants.py`、`data_classes.py` | 协议常量、SSML 构造、MUID 生成、语音名转换、403 后的时钟校准重试逻辑。 |

**许可关系说明**：LGPL-3.0 §3 允许将 LGPL 作品整体作为 GPL-3.0 作品发布
（若修改了 LGPL 库，则该修改须以 LGPL-3.0 发布）。本项目整体采用 **GPL-3.0** 发布，
符合该条件；上述两个文件中的移植代码在语义上保持与上游一致，未作行为性修改。

**致谢**：感谢 rany 及 edge-tts 项目的贡献者。微软 Edge 在线 TTS 的 WebSocket 协议
本身没有公开文档，本项目的协议实现建立在对该服务网络的逆向分析之上，
使用者需自行确保符合微软服务条款。

---

## 2. 微软 Edge TTS（在线语音服务）

* **用途**：`EdgeTtsService` 连接微软 Edge 在线 TTS 服务合成单词发音
* **默认语音**：`en-US-EmmaMultilingualNeural`
* **说明**：**代码与协议实现归本项目所有**，微软不提供本仓库中的任何代码。
  发音音频（`review-backend/audio/*.mp3`）是本机运行时**自己合成生成的数据**，
  不包含微软的资产或音频文件。

---

## 3. 运行时依赖

以下依赖通过 Maven / npm 引入，各自保留其原有许可证，
本项目不对其做任何修改：

| 依赖 | 用途 | 许可证 |
|:---|:---|:---|
| Spring Boot 2.7.18 | Web / JPA 框架 | Apache-2.0 |
| H2 Database | 文件数据库 | MPL-2.0 / EPL-1.0 双许可 |
| Apache POI 5.2.5 | Excel 导入导出 | Apache-2.0 |
| Lombok | 编译期代码生成 | MIT |
| Vue 3 | 前端框架 | MIT |
| Vite | 前端构建工具 | MIT |
| Element Plus | UI 组件库 | MIT |
| Axios | HTTP 客户端 | MIT |
| ECharts | 图表 | Apache-2.0 |
| Vue Router 4 | 前端路由 | MIT |

> 依赖的完整许可文本可通过各自的仓库或包管理器获取。
> 完整依赖树见 `review-backend/pom.xml` 与 `review-frontend/package.json`。

---

## 4. 词库数据

`data/单词复习数据备份.json` 收录的单词、音标、释义与例句，
以及 `review-backend/audio/*.mp3` 发音缓存，均为**本项目自行整理/生成的数据**，
不包含任何第三方受版权保护的词表或音频资产。

---

## 5. 项目图标与文案

界面使用的 Emoji 图标（📖 📊 🔊 等）为 Unicode 标准字符，
由操作系统字体渲染，不含独立的版权声明。
