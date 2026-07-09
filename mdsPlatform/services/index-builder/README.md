# index-builder

离线索引构建器，输入 `/tmp/in` 的原始图片与 `captions*.json`，输出 `/tmp/out/indexes/**`：
- `/indexes/image/faiss.index`
- `/indexes/image/id_map.parquet`
- `/indexes/text/faiss.index`
- `/indexes/text/id_map.parquet`
- `/indexes/manifest.json`

脚本会优先通过环境变量回调 backend：
- `BACKEND_BASE_URL`
- `BACKEND_BEARER_TOKEN`
- `BACKEND_CALLBACK_READY`
- `BACKEND_CALLBACK_FAILED`
- `OUTPUT_COMMIT_ID`

回调成功后，backend 会把该 `mm_index_version` 标记为 READY/FAILED。
