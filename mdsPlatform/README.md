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

```bash
cd services/backend
docker compose up -d
```

## 数据存储

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
