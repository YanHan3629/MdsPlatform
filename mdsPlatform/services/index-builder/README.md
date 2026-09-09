# index-builder

离线统一描述与索引构建器。输入 `/tmp/in` 中的数据集原始文件，将图像、文本和其他业务格式转换为 CLIP 可编码的文本/图像表示。

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

`faiss.index`、`id_map.parquet` 和 `/indexes/**` 不作为业务数据重复索引。

## 时序数据

表格转换器会以确定性统计方式识别时间列，并在存在设备、传感器、测点或资产编号时分组分析。清单中包含：

- UTC 标准化的开始与结束时间。
- 每个时序分组的原始排序。
- 采样间隔中位数和不规则采样标记。
- 同一时序内的重复时间戳和无效时间值。
- 指标的起止值、变化量、趋势、均值、极值和极值时间。
- 每个文本表示的精确时间窗口和原始表格定位。

## 输出

- `/indexes/image/faiss.index` 与 `/indexes/image/id_map.parquet`：兼容原图像检索。
- `/indexes/text/faiss.index` 与 `/indexes/text/id_map.parquet`：兼容原文本检索。
- `/indexes/unified/faiss.index` 与 `/indexes/unified/id_map.parquet`：所有格式的统一检索入口。
- `/indexes/manifest.json`：索引清单。
- `/representations/manifest.json` 与 `/representations/manifests/*.json`：数据集和单资产描述清单。

构建完成后通过 `BACKEND_CALLBACK_READY` 回调 backend；失败时通过 `BACKEND_CALLBACK_FAILED` 回调。
