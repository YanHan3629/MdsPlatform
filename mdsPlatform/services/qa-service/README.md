# 多模态问答服务

基于 Qwen3.5-0.8B 视觉语言模型，为文本、图片及混合输入提供中文问答、产业链风险分析、客户需求评判和数据摘要能力。

服务由两个独立进程组成：vLLM 负责模型推理，FastAPI 负责请求校验、多模态消息组装、提示词选择和统一响应。

## 技术栈

| 组件 | 技术 |
|---|---|
| 问答接口 | Python 3.12 + FastAPI |
| 模型服务 | vLLM 0.17.0 |
| 视觉语言模型 | Qwen3.5-0.8B |
| 接口协议 | OpenAI 兼容 Chat Completions |
| 部署方式 | Docker Compose + NVIDIA GPU |

## 核心功能

| 功能 | 说明 |
|---|---|
| 通用问答 | 回答文本问题或分析用户提供的资料 |
| 图片问答 | 识别单张或两张图片并回答问题 |
| 风险分析 | 输出总体判断、风险点、影响和应对建议 |
| 需求评判 | 评估需求价值、可行性和行动建议 |
| 数据摘要 | 提炼输入内容的主题、指标和异常点 |
| 检索意图路由 | 优先依据请求字段和输入形态选择检索类型，存在歧义时由 Qwen3.5-0.8B 判断 |
| 多模态 RAG | 支持文搜图、图搜文和双路检索，将命中内容作为来源送入问答模型 |
| 模拟后端 | 无 GPU 时使用 mock 模式验证接口链路 |

## 快速启动

本地模型应位于：

```text
qa-service/Qwen3.5-0.8B/
```

启动 vLLM 和问答服务：

```powershell
cd mdsPlatform/services/qa-service
.\start-all.ps1
```

停止服务：

```powershell
.\stop-all.ps1
```

默认地址：

| 服务 | 地址 |
|---|---|
| 问答接口 | `http://localhost:18081` |
| vLLM | `http://localhost:8000` |
| OpenAPI | `http://localhost:18081/docs` |

## 核心 API

### 健康检查

```text
GET /healthz    # FastAPI 存活状态
GET /health     # FastAPI、vLLM 和模型加载状态
GET /model      # vLLM 已加载模型
```

### 统一问答

```text
POST /api/v1/qa
```

接口同时接受 `application/json` 和 `multipart/form-data`。两种格式使用相同的字段名称、枚举和数值范围校验，并兼容 camelCase 与 snake_case。

纯文本示例：

```json
{
  "question": "请概括以下质检记录",
  "inputMode": "user_data_only",
  "taskType": "data_summary",
  "texts": ["抽检合格率下降，压缩机异响投诉增加"],
  "maxTokens": 256,
  "temperature": 0.2
}
```

图片示例：

```powershell
curl.exe -X POST http://localhost:18081/api/v1/qa `
  -F "question=请描述这张图片" `
  -F "inputMode=user_data_only" `
  -F "images=@C:/data/example.jpg"
```

## 输入模式

| 模式 | 行为 |
|---|---|
| `user_data_only` | 只使用用户提交的文本、上下文和图片 |
| `hybrid` | 同时使用用户数据和可用检索结果 |
| `question_only` | 只接收问题，不允许附带文本、上下文或图片 |

## 检索意图路由

检索路由采用“确定性规则优先、模型判断兜底”的方式。以下情况不会额外调用大模型：

- `user_data_only`、`retrievalMode=none` 或 `retrievalType=none`；
- 显式指定 `retrievalType`；
- `question_only` 或没有上传图片，此时直接使用文搜图；
- 问题明确包含“图搜文”“文搜图”“结合图片和文字检索”或“不检索”等表达；
- 使用 `bundle`，或请求缺少数据集和索引标识。

只有携带图片的 `hybrid` 请求在 `retrievalType=auto` 且规则无法判断时，才会由 Qwen3.5-0.8B 进行一次最多 128 token 的结构化意图分析。意图分析失败时回退到原有文搜图流程。

| `retrievalType` | 行为 |
|---|---|
| `auto` | 使用规则，必要时调用意图模型 |
| `none` | 不检索 |
| `text_to_image` | 使用问题文字检索相关图片及描述 |
| `image_to_text` | 使用上传图片检索相关文本 |
| `dual` | 同时执行文搜图和图搜文，使用 RRF 融合并按资产 ID 去重 |

检索完成后的来源组装、RAG 提示词、vLLM 回答和内容 ID 回传流程保持一致。

## 任务类型

| 类型 | 说明 |
|---|---|
| `general_qa` | 通用问答 |
| `chain_risk_analysis` | 产业链风险分析 |
| `demand_evaluation` | 客户需求综合评判 |
| `data_summary` | 数据综合摘要 |

## 请求字段

| 字段 | 默认值 | 说明 |
|---|---|---|
| `question` | 必填 | 用户问题，最长 8000 字符 |
| `inputMode` | `hybrid` | 输入模式 |
| `taskType` | `general_qa` | 任务类型 |
| `texts` | 空 | 文本依据数组 |
| `context` | 空 | 补充上下文 |
| `images` | 空 | multipart 图片字段 |
| `datasetId` | 空 | 数据集 UUID；数据空间检索时必填 |
| `versionId` | 空 | 数据集版本 UUID；数据空间检索时必填 |
| `indexVersionId` | 空 | READY 索引版本 UUID；数据空间检索时必填 |
| `useSearchService` | `true` | `auto` 模式是否优先使用在线检索服务 |
| `retrievalMode` | `auto` | 检索提供方：`auto/search_service/bundle/none` |
| `retrievalType` | `auto` | 检索类型：`auto/none/text_to_image/image_to_text/dual` |
| `topK` | `8` | 检索数量，范围 1～50 |
| `maxTokens` | 服务默认值 | 生成长度，范围 1～32768 |
| `temperature` | 服务默认值 | 采样温度，范围 0～2 |

## 响应结构

```json
{
  "answer": "模型回答",
  "confidence": 0.82,
  "sources": [],
  "processing_time_ms": 8200,
  "timestamp": "2026-09-06T06:11:00+00:00",
  "model": "Qwen/Qwen3.5-0.8B",
  "meta": {
    "model_name": "Qwen/Qwen3.5-0.8B",
    "confidence_source": "token_logprob_mean",
    "image_count": 1,
    "text_count": 0,
    "prompt_tokens": 236,
    "completion_tokens": 98,
    "total_tokens": 334,
    "request_id": "chatcmpl-...",
    "retrieval_type": "image_to_text",
    "intent_analysis_used": false,
    "intent_confidence": 1.0,
    "intent_reason": "请求明确指定检索类型"
  },
  "task_type": "general_qa",
  "retrieval_mode": "none",
  "backend": "vllm",
  "notice": null,
  "retrieved": []
}
```

置信度优先根据生成 token 的平均概率计算；vLLM 未返回 logprobs 时，使用来源相关度进行保守估计。该值用于辅助展示，不等同于经过校准的事实正确率。

当请求没有任何参考依据时，服务不会返回虚构的 `[来源n]` 标记。

## 本地设备约束

默认配置针对 RTX 3050 Laptop 4GB 显存：

- vLLM 上下文长度为 2048 token；
- 单次最多输入 2 张图片，超过时返回 HTTP 400；
- 每条文本依据和上下文最多取前 500 个字符；
- 默认生成上限为 512 token；
- vLLM 同时处理的请求数为 2。

这些限制可以通过环境变量或 `docker-compose.yml` 调整，但提高图片数量、上下文长度或并发数可能导致显存不足。

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `VLLM_BASE_URL` | `http://localhost:8000` | vLLM 地址 |
| `MODEL_NAME` | `Qwen/Qwen3.5-0.8B` | 模型服务名称 |
| `LLM_BACKEND` | `vllm` | `vllm` 或 `mock` |
| `REQUEST_TIMEOUT_SECONDS` | `300` | 推理请求超时 |
| `DEFAULT_MAX_TOKENS` | `2048` | 非 Compose 启动时的生成上限 |
| `MAX_UPLOAD_IMAGES` | `2` | 接口允许上传的图片数 |
| `MAX_PROMPT_IMAGES` | `2` | 实际送入模型的图片数 |
| `MAX_TEXT_CHARS` | `500` | 每条文本依据的字符上限 |
| `SEARCH_SERVICE_BASE_URL` | `http://localhost:18080` | 多模态检索服务地址 |
| `BACKEND_BASE_URL` | `http://localhost:8888` | 数据空间后端地址 |
| `BACKEND_BEARER_TOKEN` | 空 | 调用带预览图的后端检索接口时使用 |

Compose 会将 `DEFAULT_MAX_TOKENS` 覆盖为 `512`，以适配本地 4GB 显存。

## 测试

安装测试依赖：

```powershell
python -m pip install -r requirements-test.txt
```

运行独立回归测试：

```powershell
python -m unittest discover -s tests -v
```

运行真实模型测试：

```powershell
python scripts/manual_qa.py `
  --question "请用一句话说明冰箱压缩机的作用" `
  --json-only

python scripts/manual_qa.py `
  --question "请描述这张图片" `
  --image C:/data/example.jpg
```

## 项目结构

```text
qa-service/
├── app.py                    # FastAPI 入口
├── api/routes/               # 问答和健康检查接口
├── adapters/                 # vLLM 及可选数据客户端
├── core/config.py            # 环境配置与本地资源限制
├── schemas/                  # 请求和响应模型
├── services/                 # 提示词、问答编排和可选检索
│   ├── intent_analyzer.py    # Qwen 检索意图分析
│   └── retrieval_planner.py  # 快路径规则与检索计划
├── scripts/manual_qa.py      # 手动测试脚本
├── tests/                    # 独立回归测试
├── docker-compose.yml        # vLLM 与 FastAPI 编排
├── start-all.ps1             # 启动脚本
└── stop-all.ps1              # 停止脚本
```
