# 产业链数据空间平台

面向产业链多方数据共享、检索与分析的完整项目。数据空间前端、Spring Boot 后端、多模态检索、统一模态索引、多模态问答和 Qwen3.5 模型服务通过一套 Docker Compose 运行。

## 技术栈

| 组件 | 技术 |
|---|---|
| 前端 | HTML / CSS / JavaScript，由后端统一提供 |
| 业务后端 | Java 17 / Spring Boot / MyBatis / Flyway |
| 多模态检索与索引 | FastAPI / CLIP / BGE-small-zh-v1.5（CPU）/ FAISS |
| 多模态问答 | FastAPI / Qwen3.5-0.8B / vLLM |
| 存储 | PostgreSQL 16 / MinIO / Redis |
| 部署 | Docker Compose，项目名 `multimodal-dataspace` |

## 快速启动

前置条件：Docker Desktop（Linux 容器与 NVIDIA GPU 支持）、支持 `include` 的 Docker Compose v2、Java 17 和 Maven。本地 Qwen 模型放在 `mdsPlatform/services/qa-service/Qwen3.5-0.8B/`，CLIP 模型沿用检索与索引服务各自的模型目录。文本专业索引使用固定版本的 BGE-small-zh-v1.5（仅 CPU，约 96 MB 权重）；首次构建镜像时会下载并核对 SHA-256，模型文件不纳入 Git。

在仓库根目录运行：

```powershell
.\start-all.ps1
```

已构建镜像时可跳过构建：

```powershell
.\start-all.ps1 -NoBuild
```

统一入口：`http://localhost:8888/data-space/index.html`

多模态问答：`http://localhost:8888/data-space/index.html#qa`

```powershell
docker compose ps
docker compose logs --tail 100 qa-service vllm
.\stop-all.ps1
```

停止脚本保留容器及数据。问答与 vLLM 的编排统一放在根目录 `compose.yml`，数据空间基础服务由其引入后端编排文件。`qa-service` 与 `search-service` 均只保留服务代码、Dockerfile 和依赖等文件，请从根目录启动完整项目。

## 账号创建与使用

### 创建账号（管理员操作）

当前不开放匿名注册，也没有登录账号的自助注册页面。账号由具有 `ADMIN` 或 `SUPER_ADMIN` 角色的管理员通过 `POST /api/auth/register` 创建；驾驶舱中的“企业 / 组织注册认证”和“注册空间”不是登录账号创建入口。

首次初始化数据库会创建 `admin` 超级管理员。请向部署维护者获取有效密码，仓库不保存本地测试账号凭据。沿用已有外部数据库启动时，账号和密码保持不变，不会重新初始化。

新账号必须指定所属组织 `orgId` 和角色 ID 列表 `roles`，两者使用数据库中实际存在的 UUID，不能把 `MEMBER` 等角色代码当作角色 ID。建议为普通使用者选择目标组织内的最小权限角色，不要直接授予管理员权限。账号应加入目标数据所在组织；另建组织不会自动获得已有数据的访问权限。

在 PowerShell 中登录管理员账号并查看其组织及可用角色（密码通过交互输入，不写入命令或文件）：

```powershell
$dataspaceApi = 'http://localhost:8888'
$adminCredential = Get-Credential -Message '输入已有数据空间管理员账号'
$adminLogin = Invoke-RestMethod -Method Post -Uri "$dataspaceApi/api/auth/login" `
    -ContentType 'application/json; charset=utf-8' -Body (@{
        userName = $adminCredential.UserName
        password = $adminCredential.GetNetworkCredential().Password
    } | ConvertTo-Json)
$adminHeaders = @{ Authorization = "Bearer $($adminLogin.token)" }
$adminLogin.user.org
$availableRoles = Invoke-RestMethod -Uri "$dataspaceApi/api/roles" -Headers $adminHeaders
$availableRoles | Select-Object roleId, roleCode, roleName, orgId
```

将下方占位内容替换为目标组织及该组织角色的真实 UUID，再创建账号。需要查询其他组织时，超级管理员可调用 `GET /api/orgs?page=1&size=20`。

```powershell
$newCredential = Get-Credential -Message '输入要创建的新账号用户名和密码'
$registerBody = @{
    userName = $newCredential.UserName
    password = $newCredential.GetNetworkCredential().Password
    orgId = '<目标组织 UUID>'
    roles = @('<目标角色 UUID>')
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$dataspaceApi/api/auth/register" `
    -Headers $adminHeaders -ContentType 'application/json; charset=utf-8' -Body $registerBody
```

用户名不可重复。密码至少 8 位，只允许英文字母、数字及 `-_@*#$%&`，且大写字母、小写字母、数字、上述符号四类中至少包含两类；`!` 等其他符号不被接受。注册成功返回账号信息，不返回登录令牌，需要使用新账号另行登录。

### 登录与调用接口

打开 `http://localhost:8888/data-space/index.html#qa`，输入创建好的用户名和密码登录。在当前组织的数据集列表中选择已有 READY 索引的数据集，即可检索和问答；新数据通过该页“上传与自动索引”区域上传并发布，等待索引就绪后使用。

也可在上面的 PowerShell 会话中使用新账号调用 API：

```powershell
$userLogin = Invoke-RestMethod -Method Post -Uri "$dataspaceApi/api/auth/login" `
    -ContentType 'application/json; charset=utf-8' -Body (@{
        userName = $newCredential.UserName
        password = $newCredential.GetNetworkCredential().Password
    } | ConvertTo-Json)
$userHeaders = @{ Authorization = "Bearer $($userLogin.token)" }
Invoke-RestMethod -Uri "$dataspaceApi/api/data-space/qa/datasets" -Headers $userHeaders
```

登录接口的用户名字段为 `userName`。响应包含 `token`、`refreshToken`、有效期以及用户组织和角色；受保护的 API 使用 `Authorization: Bearer <token>`。不要将密码、令牌或包含它们的响应提交到 Git。

页面会话失效后需重新登录。API 客户端可通过 `POST /api/auth/refresh` 提交 `{"refreshToken":"<刷新令牌>"}` 换取新令牌；通过带登录令牌的 `PATCH /api/users/me/password` 提交 `oldPassword` 和 `newPassword` 修改密码，新密码遵守同一规则。

页面“退出登录”只清除当前浏览器会话。需要使账号已有令牌失效时，应携带登录令牌调用 `POST /api/auth/logout`；修改密码本身不会自动撤销已有令牌，建议修改后调用该接口，再以新密码登录。

常见问题：`401` 请检查账号密码或重新登录；`403` 请检查操作所需角色及数据所属组织；注册返回 `409` 表示用户名重复。数据集列表为空时，先核对账号组织及索引是否 READY，不要通过新建组织来绕过数据权限。

## 数据空间问答

1. 在“多模态问答”页面登录已有数据空间账号。该页“上传与自动索引”区域可创建真实数据集或版本、上传文件/文件夹并发布；发布后后台自动建立索引，也可对旧版本发起幂等回填。驾驶舱、供给工作台和问答页面共用实际数据版本与任务状态。
2. 选择当前组织已建立 READY 索引的真实数据集，页面自动携带数据集、版本和索引 UUID。
3. 输入问题，可补充文本或最多两张图片，选择输入模式和任务；需要特定来源格式时在问题中说明。
4. 查看回答、检索路由及引用依据；“下载来源文件”通过后端校验权限并读取原文件。

汇总计算可能涉及多个文件，来源卡片会分别列出下载入口。下载前按当前账号、数据集版本的原始快照与对象存储核验文件，标注 JSON 等未登记为资产的原始文件也可下载。若旧索引仍引用已删除的文件，页面会明确提示来源不可用，而不会提供无效下载按钮；需重新构建索引消除这类过期引用。用户直接输入的文字或未匹配到记录的计算结果没有原始文件下载入口。

### 专业索引与查询映射（v1.12）

入库前可填写来源时区、时间字段、实体分组字段和单位映射，也可按文件逻辑路径覆盖。映射随任务冻结；修改映射后需回填才对新索引生效，不改写原文件或旧索引。来源时区不确定时应留空，系统会阻止无依据的精确时序计算。

专业查询提供几何样本下拉选择和数值曲线输入。明确模式或可完整解析的条件不调用意图模型；条件不完整、歧义或跨检索器依赖交给 Qwen 分析，仍无法形成有效计划则请求澄清，不静默丢弃条件。

索引在入库阶段生成，增量复用扩展到文本向量、字段倒排、时序统计和曲线特征；全局不可变索引仍需汇总组装。OBJ 与 STEP 使用独立的形状特征空间，不能直接跨空间比较。几何相似只提供近似候选，不代表工程可替换性；OBJ 尚不提供完整自交认证。

支持纯用户资料、仅问题检索和混合输入；支持文字检索、图片检索和双路检索。输入或选择已明确检索类型时直接使用规则，仅歧义输入调用 Qwen3.5 分析意图。

前端统一请求 `/api/data-space/qa`，后端校验账号组织及索引范围，再调用问答服务。问答服务在项目内通过 `sirius`、`search-service` 和 `vllm` 服务名通信，请求凭据相互隔离。来源文件通过同源后端下载，浏览器无需访问容器内的 MinIO 主机名。

演示驾驶舱中的 `ds-…` 目录和数据库中的真实索引是不同的记录。问答页只列出数据库中可检索的真实索引，不会把演示 READY 标签作为检索成功的依据。旧索引仍兼容；跨格式统一描述需要重新构建索引。

## 专业索引与自动入库

文件真正写入并校验 SHA-256 后，版本发布会在同一事务写入 outbox 与幂等入库任务。任务按 `Parser → SemanticUnit → 专业 IndexBuilder → 清单/SHA 校验 → 预加载 → 原子发布` 执行；失败指数退避并保留旧 ACTIVE 索引，重启后从租约或 PREWARMING 状态恢复。已有快照可通过页面或鉴权 `backfill` API 回填，前端“索引任务”区域显示解析、构建、预热、就绪和错误。

当前专业空间包括中文文本倒排与独立向量、CLIP 视觉、类型化结构数据、时序事实/曲线形状、几何属性/形状特征。精确时序计算使用完整授权范围，几何硬条件先校验单位和未知属性；跨检索器依赖只能使用显式实体关系。问答返回 EvidencePack 证据定位、算法分数含义、计算口径、质量与限制，Qwen 不负责重算关键数字。

详细契约和恢复方式见 [backend](mdsPlatform/services/backend/README.md)、[index-builder](mdsPlatform/services/index-builder/README.md)、[search-service](mdsPlatform/services/search-service/README.md) 与 [qa-service](mdsPlatform/services/qa-service/README.md)。

各服务的配置、索引语义和能力边界见相应服务 README。

## 核心 API

| 接口 | 说明 |
|---|---|
| `POST /api/auth/login` | 数据空间账号登录 |
| `POST /api/auth/register` | 管理员创建账号，指定组织及角色 UUID |
| `POST /api/auth/refresh` | 使用刷新令牌换取新令牌 |
| `POST /api/auth/logout` | 使当前账号已有令牌失效 |
| `PATCH /api/users/me/password` | 修改当前账号密码 |
| `GET /api/data-space/qa/health` | 问答服务与模型状态 |
| `GET /api/data-space/qa/datasets` | 当前组织可用的真实数据集与索引 |
| `POST /api/data-space/qa` | JSON 或 multipart 问答 |
| `POST /api/data-space/qa/search` | 使用指定索引检索并保留统一描述 |
| `GET /api/data-space/qa/assets/{datasetId}/{versionId}/{assetId}` | 校验权限后下载来源 |
| `POST /api/data-space/qa/source-files/{datasetId}/{versionId}` | 提交来源 UUID 数组，批量核验文件名称与可下载状态 |

除健康检查外，问答接口需要 `Authorization: Bearer <token>`。问答请求字段沿用 [问答服务说明](mdsPlatform/services/qa-service/README.md)。

## 数据与资源

业务数据沿用仓库外的 `../Multimodal Data Space Platform/`：数据库位于 `data/pgdata`，文件位于 `data/minio_data`，可导入样本位于 `dataset`。可通过绝对路径环境变量 `DATASPACE_STORAGE_ROOT` 修改位置。

业务数据、模型权重、验收报告、测试资料和运行日志留在本地，不作为平台源码发布。数据版本发布后保持不可变；新增或修正数据应发布新版本，解析规则变更应构建新的索引。

本地 4 GB 显存配置保留：2048 token 上下文、最多 2 张图片、GPU 使用率 0.78、并发序列数 2、默认回答上限 512 token。模型启动需要一定时间，页面会显示实际模型就绪状态。

## 检索、问答与业务模块

`#retrieval` 提供多模态检索，筛选项由所选快照的语义能力生成；`#qa` 提供问答，按相关性、原始格式和事实去重选择依据。两页共享账号、组织和数据版本，保留独立的输入状态。完整范围统计由程序执行，不将检索前几条当作全表。

驾驶舱、供给工作台、消费搜索与合约推送复用数据库、存储、账号组织、冻结版本及授权。`business-worker` 执行实际质量评估，`local-receiver` 提供签名接收回执，交付调度支持租约和历史回执恢复。V10–V13 为增量数据库迁移。外部接收端需要单独配置；本地接收成功不代表外部业务系统已经接入。

### 全格式问答证据

原生证据覆盖 TXT、CSV、XLSX、JSON、JSONL、PDF、JPG、PNG、SVG、OBJ、STEP。表格保留工作表、行列、公式及缓存状态；文档保留页、原文/OCR和区域；工程文件提供源声明、可计算几何属性及真实视图。未知单位、缺失公式缓存或未实现的工程测量明确标注，不由模型猜测。

`evidence-worker` 使用索引构建镜像进行受限 CPU 渲染；应先构建 `index-builder` 镜像，再启动工作器。所有原生读取、预览和计算都绑定冻结索引，并重新检查当前账号授权。旧索引缺少原生证据时需要正常重建，不能改写旧快照。

| 接口 | 用途 |
|---|---|
| `GET /api/data-space/qa/capabilities/{datasetId}/{versionId}/{indexVersionId}` | 当前范围的格式、资产和操作能力 |
| `GET /api/data-space/qa/evidence/{datasetId}/{versionId}/{indexVersionId}/{assetId}` | 原生证据及表示清单 |
| 同路径 `POST /read` | 按清单登记的操作读取原文、字段或属性 |
| 同路径 `POST /render` | 返回受保护的区域图片及读取回执 |
| `POST /api/data-space/qa/retrieval/execute` | 执行专业只读检索和计算 |

来源卡保留原始格式、定位、证据性质与原件下载。问答结果中的 `meta.trace_id` 可关联真实模型输入、证据哈希和引用链。实际输入和来源一致不代表模型语义正确，当前小模型的视觉解释仍需核对原件。

问答审计默认保存到 `./data/qa-evidence-traces`，可在本地 `.env` 设置 `QA_EVIDENCE_TRACE_HOST_DIR` 指定目录。该目录可能包含业务内容与模型输出，应限制本地访问并按部署要求保留；不提交 Git。审计存储失败时拒绝交付无法留痕的回答。每张用户图片最多 10 MB、最多两张；空附件、非文件表单项、损坏图片及超限内容均明确拒绝。

切换活动索引使用版本索引的 `POST .../indexes/{indexVersionId}/activate` 接口，提交 `expectedActiveIndexVersionId` 防止覆盖并发变更。回退同样指定保留的 READY 索引和预期当前值；不会修改原始数据或历史产品冻结范围。

## 项目结构

```text
MdsPlatform/
├── compose.yml                 # 统一 Docker 项目入口
├── start-all.ps1 / stop-all.ps1 # 统一启停
├── scripts/                    # 本地镜像更新与联调工具
└── mdsPlatform/services/
    ├── frontend/               # 数据空间与问答页面
    ├── backend/                # 业务 API、问答代理与权限
    ├── search-service/         # 在线多模态检索
    ├── index-builder/          # 统一描述与索引
    └── qa-service/             # 问答、意图路由、RAG 与模型编排
```

数据空间业务闭环和格式处理详见 [平台说明](mdsPlatform/README.md)。
