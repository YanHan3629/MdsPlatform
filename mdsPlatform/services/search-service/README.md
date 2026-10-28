# multimodal-search-service

独立 Python 检索服务，提供：
- `POST /internal/v1/search/text-to-image`
- `POST /internal/v1/search/image-to-text`
- `POST /internal/v1/indexes/preload`
- `POST /internal/v1/search/execute`
- `GET /internal/v1/indexes/{datasetId}/{versionId}/{indexVersionId}/capabilities`
- `GET /healthz`

它不会直接解析原始数据集，而是通过 backend 的内部 bundle 接口拿到索引文件下载 URL，然后加载：
- image faiss index
- text faiss index
- image metadata parquet
- text metadata parquet
- unified faiss index（新版索引）
- unified metadata parquet（包含原始格式、表示类型、命中位置和时间窗口）

当 bundle 包含统一索引时，文本和图像查询都使用 CLIP 共享向量空间检索所有文件格式，并按 `asset_id` 聚合多个页面、表格窗口或模型视图。旧版 bundle 仍自动回退到原图像/文本索引。

专业执行接口接收经过 Pydantic 严格校验的 `QueryPlan`，只执行白名单检索与计算：

- Text：BM25 与独立的 CPU BGE-small-zh-v1.5 中文向量按带权 RRF 融合，保留各通道原始分数；旧哈希向量索引仍按其注册版本兼容读取，不与新模型混用。
- Structured：基于入库时落盘的类型化等值倒排和数值范围索引进行过滤，并支持字段排序、聚合；长自由文本保留受约束扫描，不执行模型生成的 SQL/Python。未知字段明确返回能力限制。
- Time series：精确操作读取完整授权时间范围；去重键包含来源资产，跨资产集合聚合会披露序列数，依赖连续性的操作拒绝拼接多条序列；相似曲线最多召回 200 个候选后做有界 DTW。
- 双时间段比较会分别核对时区、单位、数据存在性以及两段是否属于同一来源序列；候选检索也受显式实体和时间窗约束。不带时区、倒置时间范围或包含未知字段的查询计划在执行前被拒绝。
- Geometry：硬条件先验证单位与未知属性，形状候选最多 200 个，再做有限特征核验。
- Visual：专业视觉通道只查询 IMAGE 表示，保留页/帧 locator 和派生预览位置；兼容图文接口不变。图片不能证明尺寸、拓扑或可替换性。

v1.12 按 `featureSpaceId` 隔离 OBJ 与 STEP 的形状候选，不允许跨特征空间直接比较。结构化 `average` 使用显式均值聚合。时序精确计算若范围内任一所需样本缺失或无法换算单位，返回映射限制、空数值及 `completeAuthorizedRange=false`，不能丢弃问题样本后宣称全范围结果。

返回统一 `EvidencePack`，包含证据粒度 locator、实体/时间范围、原始分数含义、事实、单位、口径、来源、质量标记、覆盖、冲突、限制和分阶段耗时。跨检索器依赖只能沿 `relations.jsonl` 中的显式关系执行；不存在关系时返回限制，绝不按文件名猜测。

硬条件只在可验证的结构化或几何作用范围执行；带硬条件的独立跨空间融合在缺少明确证据级关联时拒绝计划，不能把未核验的文本/视觉命中冒充满足条件。文字检索的显式实体按单元 `entity_id` 硬过滤，视觉检索若要求精确实体则先澄清。

已就绪的关系索引没有所需实体边时返回 `RELATION_NOT_FOUND`；缺少关系索引本身则返回 `RELATION_INDEX_NOT_READY`。可比较的精确数值冲突进入 `conflicts`，不让相似度排序覆盖冲突或硬条件。专业证据中的资产 ID 必须与 backend 的同版本资产记录一致。

所有 `/internal/**` 请求必须携带 `X-MM-Internal-Token`。服务不发布宿主机端口。本机配置 `MAX_LOADED_INDEXES=1`，替换快照前先释放旧完整索引；占用完整索引的查询与预热使用同一内存租约串行执行，排队超过 `request_timeout_seconds` 会明确失败。模型仍可按原配置并发，搜索侧只调度其证据检索。能力描述走独立轻量缓存，不预载全部专业数据。

结构化和时序 JSONL 逐物理行解码，避免同时持有整文件文本和切行副本。普通查询按需构建专业检索器，同一快照的并发首次加载共享一个解码结果。发布用 `/indexes/preload` 即使命中查询缓存，也必须完整准备所有已注册专业空间，失败不会标记预热完成。检索只加载已构建产物，不触发 Parser、建库或修改活动索引。扩大缓存前需重新测试目标机器内存，不能据模型显存推算可用 RAM。

运行前需要：
1. 配置 `BACKEND_BASE_URL`
2. 配置 `MM_INTERNAL_TOKEN`
3. 配置 `MODEL_PATH`
4. BGE 中文模型位于 `TEXT_EMBEDDING_MODEL_PATH`（默认 `/app/bge-small-zh-v1.5`；构建镜像时下载固定版本并验证 SHA-256）
5. backend 已经存在 READY 状态的 `mm_index_version`
