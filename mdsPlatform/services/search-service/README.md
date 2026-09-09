# multimodal-search-service

独立 Python 检索服务，提供：
- `POST /internal/v1/search/text-to-image`
- `POST /internal/v1/search/image-to-text`
- `POST /internal/v1/indexes/preload`
- `GET /healthz`

它不会直接解析原始数据集，而是通过 backend 的内部 bundle 接口拿到索引文件下载 URL，然后加载：
- image faiss index
- text faiss index
- image metadata parquet
- text metadata parquet
- unified faiss index（新版索引）
- unified metadata parquet（包含原始格式、表示类型、命中位置和时间窗口）

当 bundle 包含统一索引时，文本和图像查询都使用 CLIP 共享向量空间检索所有文件格式，并按 `asset_id` 聚合多个页面、表格窗口或模型视图。旧版 bundle 仍自动回退到原图像/文本索引。

运行前需要：
1. 配置 `BACKEND_BASE_URL`
2. 配置 `BACKEND_BEARER_TOKEN`
3. 配置 `MODEL_PATH`
4. backend 已经存在 READY 状态的 `mm_index_version`
