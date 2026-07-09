# 多模态数据空间平台 API 说明

## 1. 对外业务接口

### 1.1 空间
- `POST /api/mm/spaces`
- `GET /api/mm/spaces`
- `GET /api/mm/spaces/{spaceId}`

### 1.2 数据集
- `POST /api/mm/spaces/{spaceId}/datasets`
- `GET /api/mm/spaces/{spaceId}/datasets`
- `GET /api/mm/datasets/{datasetId}`

### 1.3 数据版本
- `POST /api/mm/datasets/{datasetId}/versions`
- `GET /api/mm/datasets/{datasetId}/versions`
- `POST /api/mm/datasets/{datasetId}/versions/{versionId}/publish`

### 1.4 文件上传下载
- `POST /api/mm/datasets/{datasetId}/versions/{versionId}/files:begin-upload`
- `PUT /api/mm/datasets/{datasetId}/versions/{versionId}/files/content?path=...`
- `GET /api/mm/datasets/{datasetId}/versions/{versionId}/files`
- `GET /api/mm/datasets/{datasetId}/versions/{versionId}/files/content?path=...`
- `GET /api/mm/datasets/{datasetId}/versions/{versionId}/files/download-url?path=...`
- `DELETE /api/mm/datasets/{datasetId}/versions/{versionId}/files?path=...`

### 1.5 索引
- `POST /api/mm/datasets/{datasetId}/versions/{versionId}/build-index`
- `GET /api/mm/datasets/{datasetId}/versions/{versionId}/index-status`

### 1.6 资产
- `GET /api/mm/datasets/{datasetId}/versions/{versionId}/assets`
- `GET /api/mm/datasets/{datasetId}/versions/{versionId}/assets/{assetId}`
- `POST /api/mm/datasets/{datasetId}/versions/{versionId}/assets/{assetId}/tags`
- `POST /api/mm/datasets/{datasetId}/versions/{versionId}/assets/{assetId}/categories`

### 1.7 搜索
- `POST /api/mm/search/text-to-image`
- `POST /api/mm/search/image-to-text`

## 2. 内部接口

### 2.1 index-builder 回调
- `POST /api/mm/internal/datasets/{datasetId}/versions/{versionId}/indexes/{indexVersionId}/ready`
- `POST /api/mm/internal/datasets/{datasetId}/versions/{versionId}/indexes/{indexVersionId}/failed`

### 2.2 search-service 解析索引 bundle
- `GET /api/mm/internal/datasets/{datasetId}/versions/{versionId}/indexes/{indexVersionId}/bundle`

返回内容包含：
- index repo/commit 信息
- manifest、image index、text index、image metadata、text metadata 的下载 URL
- modelName、indexType

## 3. 关键链路

### 3.1 上传数据
1. 创建 dataset version
2. 通过 `files:begin-upload` 获取预签名上传 URL
3. 上传图片和 `captions*.json`
4. 发布 version

### 3.2 构建索引
1. 调用 `build-index`
2. backend 先解析 `captions*.json`，写入 `mm_asset` 和 `mm_asset_text`
3. backend 创建 `mm_index_version`
4. backend 启动 index-builder job
5. index-builder 写 `/tmp/out/indexes/**`
6. index-builder 回调 READY/FAILED
7. backend 将 version 切换到 READY，并设置 `active_index_version_id`

### 3.3 搜索
1. 前端调 backend 搜索接口
2. backend 查当前 active index
3. backend 调用 search-service
4. search-service 通过 bundle 接口下载索引文件
5. 文搜图/图搜文返回 assetId + score + logicalPath
6. backend 补资产预览 URL / captions / tags / categories
