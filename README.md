# 产业链数据空间平台

面向产业链多方数据共享、检索与分析的完整项目。数据空间前端、Spring Boot 后端、多模态检索、统一模态索引、多模态问答和 Qwen3.5 模型服务通过一套 Docker Compose 运行。

## 技术栈

| 组件 | 技术 |
|---|---|
| 前端 | HTML / CSS / JavaScript，由后端统一提供 |
| 业务后端 | Java 17 / Spring Boot / MyBatis / Flyway |
| 多模态检索与索引 | FastAPI / CLIP / FAISS |
| 多模态问答 | FastAPI / Qwen3.5-0.8B / vLLM |
| 存储 | PostgreSQL 16 / MinIO / Redis |
| 部署 | Docker Compose，项目名 `multimodal-dataspace` |

## 快速启动

前置条件：Docker Desktop（Linux 容器与 NVIDIA GPU 支持）、支持 `include` 的 Docker Compose v2、Java 17 和 Maven。本地模型放在 `mdsPlatform/services/qa-service/Qwen3.5-0.8B/`，CLIP 模型沿用检索与索引服务各自的模型目录。

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

1. 在“多模态问答”页面登录已有数据空间账号。
2. 选择当前组织已建立 READY 索引的真实数据集，页面自动携带数据集、版本和索引 UUID。
3. 输入问题，可补充文本或最多两张图片，选择任务和检索类型。
4. 查看回答、检索路由及引用依据；“下载来源文件”通过后端校验权限并读取原文件。

支持纯用户资料、仅问题检索和混合输入；支持文字检索、图片检索和双路检索。输入或选择已明确检索类型时直接使用规则，仅歧义输入调用 Qwen3.5 分析意图。

前端统一请求 `/api/data-space/qa`，后端校验账号组织及索引范围，再调用问答服务。问答服务在项目内通过 `sirius`、`search-service` 和 `vllm` 服务名通信，请求凭据相互隔离。来源文件通过同源后端下载，浏览器无需访问容器内的 MinIO 主机名。

演示驾驶舱中的 `ds-…` 目录和数据库中的真实索引是不同的记录。问答页只列出数据库中可检索的真实索引，不会把演示 READY 标签作为检索成功的依据。旧索引仍兼容；跨格式统一描述需要重新构建索引。

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

如果本机镜像源无法获取标准基础镜像，但已有依赖完整的 `backend-sirius:latest` 和 `sirius-mm-qa-service:baseline`，可在 Maven 打包成功后离线更新应用层：

```powershell
docker build -f scripts/refresh-local.Dockerfile --target backend -t sirius-backend:latest .
docker build -f scripts/refresh-local.Dockerfile --target qa -t sirius-mm-qa-service:latest .
.\start-all.ps1 -NoBuild
```

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

## 联调验证

`scripts/check-integration.cjs` 验证账号登录、索引范围校验、真实模型文本问答、CLIP 检索、来源下载和图片双路 RAG。使用环境变量 `QA_TEST_USERNAME`、`QA_TEST_PASSWORD`，或本地被 Git 忽略的 `.env.qa-test` 提供测试凭据：

```powershell
node scripts/check-integration.cjs
```

`scripts/check-qa-ui.cjs` 使用 Playwright 与本机 Edge 验证页面登录、数据集选择、真实 RAG、来源下载和退出登录。需要在本机安装 Playwright 或通过 `NODE_PATH` 指向已有依赖，并准备 `.env.qa-test`。

2026-09-08 本地验证：后端 26 项测试（25 通过、1 项因缺少可选 COCO 样本跳过）；问答 17 项测试通过；真实接口脚本返回 `INTEGRATION_OK`，浏览器流程返回 `UI_OK`。业务数据保持原有外置挂载，已替换的旧服务容器已清理，历史任务容器保留。
