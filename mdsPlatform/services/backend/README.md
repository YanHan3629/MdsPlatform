# backend：专业索引入库与生命周期

backend 统一真实上传、预签名完成确认、版本发布和已有快照回填。直接上传在流式写入 MinIO 时计算 SHA-256；预签名上传只有在对象存在、大小有效且完整读取计算 SHA-256 后才进入 `READY`。历史文件在专业回填时只读补算 SHA，不改写原对象或发布快照；multipart ETag 从不作为内容身份。

Flyway V7 新增持久化 `mm_ingest_task`、事务 outbox `mm_outbox_event`、通用 `mm_index_registration` 和显式 `mm_asset_relation`。幂等键包含组织、数据版本、原始 commit、Parser、Builder 与关键配置版本。任务支持重复投递、指数退避、租约超时恢复、显式失败重入及状态查询。

Flyway V8 记录任务资源用量。V9 以数据库触发器守护已发布 commit 的文件清单：禁止晚到文件插入、覆盖和删除；仅允许历史空 SHA-256 首次补齐。版本发布前再次锁定快照并逐项确认对象已就绪及内容标识。专业索引的资产 ID 与后端 `UUID.nameUUIDFromBytes(versionId + ":" + logicalPath)` 一致，来源下载按真实资产和组织权限校验。

调度器使用 PostgreSQL advisory lock 与 `SKIP LOCKED`，全局只允许一个 `PROCESSING/WAITING_INDEX` 构建。隔离容器使用任务组织中有效用户的短期 JWT 读取输入/写入候选 commit，内部索引回调另用常量时间比较的服务令牌。凭据不会写入任务表或构建日志。任务固定无 GPU、最多 4 CPU、6144 MiB 内存硬上限、无额外 swap、512 PID，并受组织运行时长限制。

构建容器完成全部上传后才发送 READY 回调。backend 先把候选 artifact commit 从 `DRAFT` 条件更新为不可写的 `PUBLISHED`，再持久化各语义空间注册并进入 `PREWARMING`；search-service 校验所有注册文件 SHA 后预加载，成功才在数据库事务中原子切换 `READY/ACTIVE`。失败保留旧 ACTIVE；服务重启恢复租约、PREWARMING 和最近 2 个 ACTIVE；配置版本过期任务标为 `SUPERSEDED`，晚到旧快照标为 `DEPRECATED`，均不能覆盖最新索引。

## API

- `POST /api/mm/datasets/{datasetId}/versions/{versionId}/ingest-tasks/backfill`：鉴权、组织范围内的幂等回填/失败重入。
- `GET /api/mm/datasets/{datasetId}/versions/{versionId}/ingest-tasks`：任务阶段、尝试次数和错误。
- `GET/POST /api/mm/datasets/{datasetId}/versions/{versionId}/ingest-tasks/mapping`：读取或保存来源时区、时间列、实体列、单位及按文件覆盖配置；校验组织权限及配置格式。
- `GET /api/data-space/qa/query-assets/{datasetId}/{versionId}`：当前授权版本中的 OBJ/STEP/STP 查询样本，供页面选择。
- `GET /api/data-space/qa/indexing`：前端可见的真实入库进度。
- `GET /api/data-space/qa/datasets`：当前组织 ACTIVE 快照及各语义空间实际能力。
- `/api/mm/internal/**`：仅服务令牌可访问的 bundle 和构建回调。

## 回填

从页面选择已有数据版本，或在登录后调用 `backfill`。重复调用返回同一个任务；任务已 READY 时不会重建，耗尽重试后再次调用会原位重入。原文件、旧索引和旧版本查询保持可用。失败项通过进度接口显示实际错误，不伪造 READY。

v1.12 将规范化映射摘要纳入幂等键；同一快照、同一配置去重，不同映射创建新任务。任务元数据冻结 `indexConfig`，后续编辑偏好不会改变正在执行的任务。构建器通过 `INDEX_PROCESSING_CONFIG_JSON` 接收配置，增量下载包含 `/professional/assets/` 和 `/professional/derived/`。

```powershell
$mavenRepository = Join-Path $env:USERPROFILE '.m2\repository'
mvn "-Dmaven.repo.local=$mavenRepository" package
```
