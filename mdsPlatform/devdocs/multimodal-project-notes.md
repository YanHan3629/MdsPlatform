# 多模态后端项目说明

## 1. 本轮推进内容

这轮代码在上一版基础上继续补了两条真正可用的链路：

1. **数据上传 -> 元数据入库**
   - `MmFileFacadeController` 补齐 begin-upload、直传上传、列表、下载、下载 URL、删除。
   - `MmMetadataImportService` 在 build-index 前自动扫描版本内的 `captions*.json` 和图片文件，导入：
     - `mm_asset`
     - `mm_asset_text`

2. **两个搜索算法工程化可用**
   - 新增 `services/search-service`：独立 FastAPI 检索服务。
   - 新增 `services/index-builder`：独立离线索引构建器。
   - backend 新增内部 bundle 接口供 search-service 拉取 READY 索引文件。

## 2. 目录说明

- `services/backend`
  - Java 控制面
  - 负责数据集/版本/文件/资产/索引/搜索代理
- `services/search-service`
  - Python 在线检索服务
  - 提供文搜图 / 图搜文
- `services/index-builder`
  - Python 离线索引构建器
  - 输出 faiss + parquet + manifest

## 3. 算法接入方式

### 文搜图
- 以图片向量索引为主。
- 输入文本，经 CLIP/ChineseCLIP 文本编码后检索 image faiss index。

### 图搜文
- 以文本向量索引为主。
- 输入图片，经 CLIP/ChineseCLIP 图片编码后检索 text faiss index。

### 与你上传脚本的关系
- 文搜图沿用了原始 `CLIP_Match_usecsv.py` 的思路：
  - 图片按图片维度聚合
  - 文本 prompt 扩展
  - 文本查图片
- 图搜文沿用了 `CLIP_Classifier_usecsv.py` 的思路：
  - 文本预编码
  - 图片查文本

## 4. 当前实现约束

1. 当前环境未实际运行 Maven 编译，因此这份交付仍然属于**高贴合仓库的代码推进版**，不是“已在此环境完整编译联调通过”的最终版。
2. `index-builder` 依赖部署时提供模型目录（默认 `clip_model_chinese`）。
3. `search-service` 依赖 `BACKEND_BEARER_TOKEN` 调用 backend 内部 bundle 接口。
4. `build-index` 使用的容器镜像默认为：
   - `sirius-mm-index-builder:latest`
   - 可通过 `MM_INDEX_BUILDER_IMAGE` 覆盖。

## 5. 推荐联调顺序

1. 启动 backend
2. 创建 space / dataset / version
3. 上传 `val2017/*.jpg` 与 `captions_val2017.json`
4. publish version
5. 启动 search-service
6. 构建 `index-builder` 镜像
7. 调 `build-index`
8. 查看 `/index-status`
9. 测试：
   - `POST /api/mm/search/text-to-image`
   - `POST /api/mm/search/image-to-text`
