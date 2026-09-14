# 产业链数据空间平台

面向产业链多方数据安全共享与可信流通的后端平台。以链主企业为中心，连接各业务域供给方，实现数据注册认证、安全接入、治理索引、产品发布、消费搜索、合约自动推送的完整闭环。

## 技术栈

| 组件 | 技术 |
|---|---|
| 业务后端 | Java Spring Boot + MyBatis + Flyway |
| 在线检索 | Python FastAPI |
| 离线索引构建 | Python (容器任务) |
| 数据库 | PostgreSQL 16 |
| 对象存储 | MinIO |
| 缓存 | Redis |
| 容器化 | Docker Compose |

## 快速启动

```powershell
cd ..
./start-all.ps1
```

## 数据存储

专业索引 v1.12 支持入库时配置来源时区、时间字段、实体字段及单位，并按文件覆盖；配置冻结到任务，回填后发布新索引。前端专业查询可选择几何样本或输入查询曲线。明确且完整的查询走规则，歧义计划由 Qwen 分析或请求澄清。OBJ/STEP 特征空间隔离，精确统计在缺少必要时区或单位映射时拒绝给出确定数值。

完整项目由仓库根目录 `compose.yml` 统一管理，Docker 项目名为 `multimodal-dataspace`。数据空间、检索、问答和 vLLM 通过服务名互通；已构建镜像可运行 `./start-all.ps1 -NoBuild`，停止使用 `./stop-all.ps1`。

前端新增“多模态问答”入口：登录数据空间账号后可在“上传与自动索引”区创建真实数据集/版本、上传文件或文件夹并发布，后台自动解析建索引；随后选择 READY 索引提问、核对并下载来源。页面自动传递数据集、版本和索引 UUID，后端按组织校验访问范围。驾驶舱演示上传与 `ds-…` ID 不作为真实检索索引。完整部署和 API 说明见 [仓库 README](../README.md)。

运行数据与代码仓库分开保存。默认存储根目录为与 `MdsPlatform` 同级的：

```text
Multimodal Data Space Platform/
├── data/
│   ├── pgdata/       # PostgreSQL
│   ├── minio_data/   # MinIO 对象
│   └── log/sirius/   # 后端日志
└── dataset/          # 可选的本地导入数据集
```

如需使用其他位置，在启动 Compose 前设置 `DATASPACE_STORAGE_ROOT`。该变量应指向包含 `data` 和 `dataset` 的目录。

### 文件格式分类

上传文件在保留业务逻辑路径的同时，会在 MinIO 对象键中按格式分类。图像和纯文本继续使用统一目录；对于当前数据空间中已识别的非图文格式，不再写入笼统的 `other` 或 `binary` 目录。

| 当前数据格式 | 存储目录 | 分类标识 |
|---|---|---|
| JPG / JPEG / PNG / SVG | `image/` | `IMAGE` |
| TXT | `text/` | `TEXT` |
| CSV | `csv/` | `CSV` |
| JSON | `json/` | `JSON` |
| JSONL | `jsonl/` | `JSONL` |
| XLSX | `xlsx/` | `XLSX` |
| PDF | `pdf/` | `PDF` |
| OBJ | `obj/` | `OBJ` |
| STEP | `step/` | `STEP` |
| FAISS 索引 | `faiss/` | `FAISS` |
| Parquet 索引映射 | `parquet/` | `PARQUET` |

仓库文件接口的响应会额外返回 `fileFormat` 和 `storageCategory`。数据源批量上传响应还会返回 `formatCounts` 与 `storagePrefixes`，用于核对各格式的数量和实际存储前缀。现有对象的逻辑路径与对象键保持不变；新上传文件以及新生成的索引产物使用上述目录结构。

### 统一描述与跨格式检索

构建索引时，索引构建器会为当前业务数据生成可追溯的统一描述清单：文本、表格、PDF 和工程模型被转换为文本描述与可用的图像预览，然后与原始图文一起进入 CLIP 共享向量空间。新索引产物包括：

- `/representations/manifests/{assetId}.json`：单文件统一描述。
- `/representations/manifest.json`：数据集描述目录。
- `/indexes/unified/faiss.index`：图像和文本表示共用的 FAISS 索引。
- `/indexes/unified/id_map.parquet`：向量到原始文件、格式和命中位置的映射。

CSV、JSON/JSONL 和 XLSX 表格会优先识别时间列和设备/传感器分组列。描述清单保存时间范围、时区、原始排序、采样间隔、重复时间戳、异常采样以及按时间排序后的趋势、极值与变化量。时序分块均携带精确的 `timeStart`、`timeEnd`、`seriesKey` 和 `sortedByTime` 定位信息。

### 专业索引与可靠入库

真实快照发布通过事务 outbox 创建幂等入库任务，已有快照可用 `POST /api/mm/datasets/{datasetId}/versions/{versionId}/ingest-tasks/backfill` 回填。任务按 Parser 注册表、标准语义单元、专业索引、文件校验、预热、原子切换执行；失败重试或服务重启不会损坏旧 ACTIVE 索引。候选索引文件全部上传后，commit 先封存为 `PUBLISHED` 才能预热和发布，晚到或配置版本过期的任务不能覆盖新版本。

当前在线检索器覆盖中文文本（BM25 与独立向量融合）、CLIP 视觉、类型化结构过滤/聚合、完整时序事实与曲线相似度，以及 OBJ/STEP 几何属性与形状候选核验。查询使用严格 `QueryPlan`，输出可追踪到原文件、locator、实体、时间范围、单位、计算口径与质量标记的 `EvidencePack`。依赖式跨空间查询只沿显式实体关系执行。

访问前端界面：`http://localhost:8888/data-space/index.html`

## 平台四层页面

| 页面 | 说明 |
|---|---|
| **总览驾驶舱** | KPI 指标卡、7 步闭环流程、实时事件面板、资源目录表格 |
| **供给方工作台** | 企业注册认证 → 数据源接入上传（MinIO）→ 资源目录 → 治理加工 → 索引构建 → 产品发布 |
| **链主消费搜索** | 按关键词/业务域/模态搜索数据产品，预览样本，发起消费意向 |
| **合约与推送** | 确认合约（数据源、发送时间、质量线），按约定自动加密推送，交付记录审计 |

## 核心 API

接口前缀：`/api/data-space`

```text
GET  /snapshot                          # 全局快照
POST /spaces/register                   # 空间注册
POST /spaces/{spaceId}/verify           # 空间认证
POST /sources                           # 数据源接入
POST /sources/{sourceId}/files          # 文件上传
POST /sources/{sourceId}/catalogs       # 资源目录
POST /sources/{sourceId}/govern         # 治理加工
POST /datasets/{datasetId}/build-index  # 索引构建
POST /datasets/{datasetId}/publish      # 产品发布
GET  /products/search                   # 产品搜索
POST /intents                           # 消费意向
POST /contracts                         # 生成合约
POST /contracts/{contractId}/dispatch   # 合约推送
```

## 数据安全设计

- **数据隔离**：各供给方 MinIO 存储物理隔离，密钥独立管理
- **权限管控**：基于身份的访问控制，未授权产品不可见
- **传输加密**：推送端到端加密，数字签名可验证完整性与来源
- **全链审计**：注册 → 接入 → 索引 → 消费 → 推送 → 交付，全链路可追溯

## 项目结构

```
services/
├── backend/           # Spring Boot 主服务
│   ├── src/main/java/com/fwdrobo/sirius/
│   │   ├── dataspace/     # 数据空间流通闭环
│   │   ├── minio/         # MinIO 配置
│   │   ├── service/       # 业务服务层
│   │   ├── controller/    # API 控制层
│   │   └── security/      # JWT 认证
│   ├── src/main/resources/
│   │   ├── static/data-space/   # 前端页面
│   │   ├── script/minio/        # MinIO 初始化脚本
│   │   └── db/migration/        # Flyway 迁移
│   └── docker-compose.yml
├── search-service/     # Python 在线检索
└── index-builder/      # Python 离线索引构建
```
