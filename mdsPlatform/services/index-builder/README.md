# index-builder

离线统一描述与专业索引构建器。输入 `/tmp/in` 中的不可变数据快照，Parser 保留原始事实与定位，语义整理器生成标准单元，再由按语义空间注册的 IndexBuilder 构建索引。旧 CLIP 图文索引继续生成并兼容读取，但不代替文本、时序或几何专业表示。

## 当前支持

| 格式 | 文本表示 | 图像表示 |
|---|---|---|
| JPG / PNG | 文件名或 COCO caption | 原图 |
| SVG | 文件描述 | PNG 预览 |
| TXT | 重叠文本分块 | - |
| CSV / JSON / JSONL | 结构、数值和时序描述 | 表格预览 |
| XLSX | 按工作表的结构、数值和时序描述 | 工作表预览 |
| PDF | 按页提取文本 | 按页渲染 |
| OBJ | 顶点、面、材质和包围盒 | 正视、侧视、俯视投影 |
| STEP | 产品名、零件和实体类型 | - |

`files.json`、`files.tsv`、`faiss.index`、`id_map.parquet` 和 `/indexes/**` 属于构建引导或索引产物，不作为业务数据重复索引。

## 时序数据

表格转换器会以确定性统计方式识别时间列，并在存在设备、传感器、测点或资产编号时分组分析。清单中包含：

- UTC 标准化的开始与结束时间。
- 每个时序分组的原始排序。
- 采样间隔中位数和不规则采样标记。
- 同一时序内的重复时间戳和无效时间值。
- 指标的起止值、变化量、趋势、均值、极值和极值时间。
- 每个文本表示的精确时间窗口和原始表格定位。

## 标准单元与专业输出

每个 `TextUnit`、`VisualUnit`、`TableUnit`、`TimeSeriesUnit`、`GeometryUnit` 均保存资产 ID、数据版本、逻辑路径、内容 SHA-256、单元 ID、locator、实体、单位、质量标记以及 Parser/整理配置版本。单文件解析失败写入质量报告，不会阻断其他文件。JSON/JSONL 的嵌套数组与对象会递归保留，避免把合法复合字段误判为缺失值。

资产 ID 与 backend、CLIP 表示共用 Java 名称 UUID（版本 ID 与标准逻辑路径）；专业单元 ID 则独立稳定生成。导入外部索引时应核对其资产 ID 与对应数据版本一致。

时间序列在没有显式设备/实体列时以来源资产 ID 作为实体，避免不同文件被拼接为一条曲线。单位只接受字段后缀、数据映射或可验证换算；未知单位保留 `UNIT_MAPPING_REQUIRED`，不猜测量纲。单资产分片按“内容 SHA-256 + Parser/配置 + Organizer/处理配置”复用，覆盖、删除和版本变化都会使对应分片失效。

专业索引按实际单元选择性产生：

- `text.lexical.zh`：中文词项/字符 n-gram BM25；`text.semantic.zh`：独立的 512 维 CPU BGE-small-zh-v1.5 中文嵌入（CLS 池化、L2 归一化，固定模型提交与权重 SHA-256），不使用 CLIP 文本分支。
- `structured.typed`：类型化只读记录、落盘的字段等值倒排/数值范围索引、排序聚合与显式实体关系；较长自由文本字段按需扫描，不伪装成已索引。
- `timeseries.facts`：完整原始事实和可合并统计；`timeseries.shape`：预建特征召回，在线有界 DTW 精排。
- `geometry.properties`：真实 OBJ/OCP STEP 属性；`geometry.shape`：归一化几何特征召回。
- `visual.clip-cn-v1` 与旧 image/text/unified 文件：视觉兼容路径。

中文文本模型采用 [BAAI/bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5) 的固定提交 `7999e1d3359715c523056ef9478215996d62a620`。官方镜像构建会在 `/app/bge-small-zh-v1.5` 获取所需文件，并对 `model.safetensors` 验证 SHA-256 `354763b9b1357bc9c44f62c6be2276321081ed2567773608c0d0785b61d5a026`；已在构建上下文提供同一文件时可离线复用。模型目录受 Git 忽略。文档不加查询前缀，短查询加模型建议的中文检索前缀；两侧均用 CLS 池化与 L2 归一化。可用 `TEXT_EMBEDDING_MODEL_PATH` 指定容器内路径。

每项注册包含语义空间、索引/算法/模型版本、维数、归一化、距离、构建参数、文件 SHA-256、内容校验和、数据版本、覆盖与状态。同维数索引也不会跨语义空间混用。

## 输出

### v1.12 解析与增量语义

`INDEX_PROCESSING_CONFIG_JSON` 接受 `timezone`、`timeColumn`、`entityColumns`、`unitMapping`、`assetMappings`；后者以原文件逻辑路径为键覆盖默认配置。映射参与解析缓存身份，来源时间保留原值并按明确时区规范化，不猜测业务时区。

OBJ 检查面索引、退化面、有向边一致性及闭合性，体积以中心化坐标计算；不满足条件时不输出可信体积。仍未实现完整网格自交认证，质量标记 `MESH_SELF_INTERSECTION_NOT_CERTIFIED` 明示此限制。STEP 使用 OpenCascade XDE 遍历真实装配实例，累计变换、逐实例计算 B-Rep 属性，单位规范到 mm/mm²/mm³；无法解析的文件不生成伪造部件。

OBJ 的主轴矩特征 `mesh-principal-moments-v2` 与 STEP 的包围盒/B-Rep 特征 `cad-aabb-brep-v2` 分别存储，后者注册到 `geometry.shape.cad`。不因维数相同而混入同一 FAISS 空间。

`/professional/derived/*.json.gz` 保存带内容校验的计算缓存，复用文本向量、单资产字段倒排、时序统计及曲线窗口特征；变更内容重新计算。清单 `incremental.derivedArtifacts` 披露实际构建/复用数量。缓存既可命中上一快照，也可命中本次构建中重复内容，因此 `Reused` 不能单独解释为跨版本复用率。全局 FAISS/JSON 仍重新汇总为不可变快照；迟到数据影响的序列统计会重新计算，尚非所有时间桶都独立增量更新。

- `/indexes/image/faiss.index` 与 `/indexes/image/id_map.parquet`：兼容原图像检索。
- `/indexes/text/faiss.index` 与 `/indexes/text/id_map.parquet`：兼容原文本检索。
- `/indexes/unified/faiss.index` 与 `/indexes/unified/id_map.parquet`：所有格式的统一检索入口。
- `/indexes/manifest.json`：索引清单。
- `/professional/manifest.json` 与 `/professional/assets/{assetId}.json`：专业索引覆盖、质量统计和可复用单资产分片。
- `/representations/manifest.json` 与 `/representations/manifests/*.json`：数据集和单资产描述清单。

所有清单、分片和索引文件上传完成后才通过 `BACKEND_CALLBACK_READY` 回调 backend；backend 随后封存候选 commit、校验并预热，成功后原子切换 ACTIVE。失败时通过 `BACKEND_CALLBACK_FAILED` 回调，旧 ACTIVE 不受影响。

## 资源约束

生产任务由 backend 限制为单并发、CPU 最多 4 核、内存硬上限 6144 MiB、无额外 swap、最多 512 PID，并受组织单任务超时约束；构建器不请求 GPU，避免与 4 GB 显存上的 Qwen 冲突。

依赖与模型已在本机镜像缓存中时，可用 `INDEX_BUILDER_BASE_IMAGE=sirius-mm-index-builder:latest` 及 `INSTALL_SYSTEM_PACKAGES=0`、`INSTALL_PYTHON_PACKAGES=0`、`PREPARE_TEXT_MODEL=0` 构建应用层更新；首次构建保留默认值并验证模型校验值。
