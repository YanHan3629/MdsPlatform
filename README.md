# 产业链数据空间平台

面向产业链多方数据共享、检索与分析的完整项目。数据空间前端、Spring Boot 后端、多模态检索、统一模态索引、多模态问答和 Qwen3.5 模型服务通过一套 Docker Compose 运行。

## 技术栈

| 组件 | 技术 |
|---|---|
| 前端 | HTML / CSS / JavaScript，由后端统一提供 |
| 业务后端 | Java 17 / Spring Boot / MyBatis / Flyway |
| 多模态检索与索引 | FastAPI / CLIP / BGE-small-zh-v1.5（CPU）/ FAISS |
| 多模态问答 | FastAPI / Qwen3.5-0.8B / vLLM |
| 存储 | PostgreSQL 16 / MinIO / Redis |
| 部署 | Docker Compose，项目名 `multimodal-dataspace` |

## 快速启动

前置条件：Docker Desktop（Linux 容器与 NVIDIA GPU 支持）、支持 `include` 的 Docker Compose v2、Java 17 和 Maven。本地 Qwen 模型放在 `mdsPlatform/services/qa-service/Qwen3.5-0.8B/`，CLIP 模型沿用检索与索引服务各自的模型目录。文本专业索引使用固定版本的 BGE-small-zh-v1.5（仅 CPU，约 96 MB 权重）；首次构建镜像时会下载并核对 SHA-256，模型文件不纳入 Git。

在仓库根目录运行：

```powershell
.\start-all.ps1
```

已构建镜像时可跳过构建：

```powershell
.\start-all.ps1 -NoBuild
```

统一入口：`http://localhost:8888/data-space/index.html`

多模态问答：`http://localhost:8888/data-space/index.html#qa`

```powershell
docker compose ps
docker compose logs --tail 100 qa-service vllm
.\stop-all.ps1
```

停止脚本保留容器及数据。问答与 vLLM 的编排统一放在根目录 `compose.yml`，数据空间基础服务由其引入后端编排文件。`qa-service` 与 `search-service` 均只保留服务代码、Dockerfile 和依赖等文件，请从根目录启动完整项目。

## 数据空间问答

1. 在“多模态问答”页面登录已有数据空间账号。该页“上传与自动索引”区域可创建真实数据集或版本、上传文件/文件夹并发布；发布后后台自动建立索引，也可对旧版本发起幂等回填。驾驶舱原有上传区属于演示流程，不是专业索引入库入口。
2. 选择当前组织已建立 READY 索引的真实数据集，页面自动携带数据集、版本和索引 UUID。
3. 输入问题，可补充文本或最多两张图片，选择任务和检索类型。
4. 查看回答、检索路由及引用依据；“下载来源文件”通过后端校验权限并读取原文件。

### 专业索引与查询映射（v1.12）

入库前可填写来源时区、时间字段、实体分组字段和单位映射，也可按文件逻辑路径覆盖。映射随任务冻结；修改映射后需回填才对新索引生效，不改写原文件或旧索引。来源时区不确定时应留空，系统会阻止无依据的精确时序计算。

专业查询提供几何样本下拉选择和数值曲线输入。明确模式或可完整解析的条件不调用意图模型；条件不完整、歧义或跨检索器依赖交给 Qwen 分析，仍无法形成有效计划则请求澄清，不静默丢弃条件。

索引在入库阶段生成，增量复用扩展到文本向量、字段倒排、时序统计和曲线特征；全局不可变索引仍需汇总组装。OBJ 与 STEP 使用独立的形状特征空间，不能直接跨空间比较。几何相似只提供近似候选，不代表工程可替换性；OBJ 尚不提供完整自交认证。

支持纯用户资料、仅问题检索和混合输入；支持文字检索、图片检索和双路检索。输入或选择已明确检索类型时直接使用规则，仅歧义输入调用 Qwen3.5 分析意图。

前端统一请求 `/api/data-space/qa`，后端校验账号组织及索引范围，再调用问答服务。问答服务在项目内通过 `sirius`、`search-service` 和 `vllm` 服务名通信，请求凭据相互隔离。来源文件通过同源后端下载，浏览器无需访问容器内的 MinIO 主机名。

演示驾驶舱中的 `ds-…` 目录和数据库中的真实索引是不同的记录。问答页只列出数据库中可检索的真实索引，不会把演示 READY 标签作为检索成功的依据。旧索引仍兼容；跨格式统一描述需要重新构建索引。

## 专业索引与自动入库

文件真正写入并校验 SHA-256 后，版本发布会在同一事务写入 outbox 与幂等入库任务。任务按 `Parser → SemanticUnit → 专业 IndexBuilder → 清单/SHA 校验 → 预加载 → 原子发布` 执行；失败指数退避并保留旧 ACTIVE 索引，重启后从租约或 PREWARMING 状态恢复。已有快照可通过页面或鉴权 `backfill` API 回填，前端“索引任务”区域显示解析、构建、预热、就绪和错误。

当前专业空间包括中文文本倒排与独立向量、CLIP 视觉、类型化结构数据、时序事实/曲线形状、几何属性/形状特征。精确时序计算使用完整授权范围，几何硬条件先校验单位和未知属性；跨检索器依赖只能使用显式实体关系。问答返回 EvidencePack 证据定位、算法分数含义、计算口径、质量与限制，Qwen 不负责重算关键数字。

详细契约和恢复方式见 [backend](mdsPlatform/services/backend/README.md)、[index-builder](mdsPlatform/services/index-builder/README.md)、[search-service](mdsPlatform/services/search-service/README.md) 与 [qa-service](mdsPlatform/services/qa-service/README.md)。

各服务的配置、索引语义和能力边界见相应服务 README。

## 核心 API

| 接口 | 说明 |
|---|---|
| `POST /api/auth/login` | 数据空间账号登录 |
| `GET /api/data-space/qa/health` | 问答服务与模型状态 |
| `GET /api/data-space/qa/datasets` | 当前组织可用的真实数据集与索引 |
| `POST /api/data-space/qa` | JSON 或 multipart 问答 |
| `POST /api/data-space/qa/search` | 使用指定索引检索并保留统一描述 |
| `GET /api/data-space/qa/assets/{datasetId}/{versionId}/{assetId}` | 校验权限后下载来源 |

除健康检查外，问答接口需要 `Authorization: Bearer <token>`。问答请求字段沿用 [问答服务说明](mdsPlatform/services/qa-service/README.md)。

## 数据与资源

业务数据沿用仓库外的 `../Multimodal Data Space Platform/`：数据库位于 `data/pgdata`，文件位于 `data/minio_data`，可导入样本位于 `dataset`。可通过绝对路径环境变量 `DATASPACE_STORAGE_ROOT` 修改位置。

本地 4 GB 显存配置保留：2048 token 上下文、最多 2 张图片、GPU 使用率 0.78、并发序列数 2、默认回答上限 512 token。模型启动需要一定时间，页面会显示实际模型就绪状态。

## 项目结构

```text
MdsPlatform/
├── compose.yml                 # 统一 Docker 项目入口
├── start-all.ps1 / stop-all.ps1 # 统一启停
├── scripts/                    # 本地镜像更新与联调工具
└── mdsPlatform/services/
    ├── frontend/               # 数据空间与问答页面
    ├── backend/                # 业务 API、问答代理与权限
    ├── search-service/         # 在线多模态检索
    ├── index-builder/          # 统一描述与索引
    └── qa-service/             # 问答、意图路由、RAG 与模型编排
```

数据空间业务闭环和格式处理详见 [平台说明](mdsPlatform/README.md)。
