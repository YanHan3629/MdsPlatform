# 给 Codex 的开发环境交接：从本地 Docker 转到同济 HPC 按需测试

编写日期：2026-10-30。适用仓库：`E:\VSCodeProject\MdsPlatform`。

这份文档供原来负责设计、开发、优化数据空间的 Codex 对话读取。**继续原对话的业务目标、已接受的设计和未完成工作；只更新对运行环境的认识。** 不要重新执行首次迁移，不要把部署验收当成原功能目标全部完成，也不要覆盖其他对话的修改。

本文中的机器配置、镜像版本、作业和验证结果是本次交接基线。真正开始下一轮开发前，应核查当前文件、远端作业、部署版本和数据状态。历史记录不能证明服务现在在线或新代码已经生效。

## 1. 必须先理解的变化

| 项目 | 现在应如何处理 |
| --- | --- |
| 开发源代码 | 仍以本地仓库为修改、审查和构建入口；远端应用目录是部署副本，不是自动同步的 Git 工作区 |
| 验证运行环境 | 主要集成测试放在同济 HPC 的计算节点上，使用 Singularity + Slurm |
| 本地 Docker | 原服务和数据保留。可以做针对性的本地开发检查；其成功不能代替 HPC 验证，也不能误当作远端结果 |
| 服务生命周期 | 测试时才提交有限时长作业；验证完主动 Stop，并确认资源已经释放 |
| 数据 | HPC 是本地初次迁移的独立副本；之后双方数据分别演进，没有双向同步 |
| 修改授权 | 用户后来已授权为适配算力平台自行修改代码；先前“不修改代码”的限制已被更新。仍只做与任务有关的改动 |
| 回答正确性 | 用户允许忽略**由模型能力不足造成**的回答错误，不代表允许忽略检索取错、证据缺失、权限错误、上传缺失或计算错误 |

不提交或推送代码，不擅自重置工作区。不要把本次文档读取理解为要求启动服务、申请 GPU 或进行数据清理。

## 2. 先读这些文件，再采取行动

路径均相对仓库根目录，除非明确写了远端路径。

1. 本文件，以及原开发对话中的功能目标和验收标准。
2. [`使用说明.md`](./使用说明.md)：现有启停与连接操作。
3. [`serve.py`](./serve.py)、[`session_control.py`](./session_control.py)、[`session.ps1`](./session.ps1)：运行时配置和实际命令，以代码为准。
4. [`runtime_cli.py`](./runtime_cli.py)、[`slurm_exec.py`](./slurm_exec.py)：涉及后台任务、索引构建、取消和资源约束时必读。
5. [`README.md`](./README.md)：镜像、备份和运行边界。
6. [`进展与待办.md`](../../reports/hpc-deployment-20261029/进展与待办.md) 的 **“2026-10-30 本轮闭环验证与使用交付”**。较早段落保留了当时的失败和待办，不能脱离日期读取。

平台规则入口：[同济 HPC 使用说明](https://dev.tongji.edu.cn/hpc-doc/#/pages/platformDes/account)。此前读取的规则副本在 [`reports/hpc-deployment-20261028/official-docs`](../../reports/hpc-deployment-20261028/official-docs/)，包括作业提交、资源配置、软件安装、数据传输及收费。操作前核查适用的最新平台规则；无法访问时明确说明使用的是历史副本。管理员已经明确答复：平台不支持 Docker，可使用 Singularity。

## 3. 平台规则与成本控制

- 登录节点只用于连接、传输、轻量文件管理、提交及查询作业。应用服务、模型推理、索引、镜像转换、大文件解包和大量文件哈希核验在有限 Slurm 作业内执行。
- 不在 HPC 启动 Docker daemon，不使用 `sudo`，不绕过调度器，不用长期 `nohup` 服务代替作业。Windows 本地构建 Docker 镜像与在 HPC 运行 Docker 是两回事。
- 所有计算作业设置明确运行上限。传输、阅读代码、编写补丁时不占用 GPU；需要模型验证时再申请。
- 当前约束是一张 GPU 最多配 7 个 CPU 核。不要把 CPU 模式的 8 核配置直接带入单卡 GPU 作业。
- `PENDING` 表示尚未分配运行，不要为同一个测试反复重复提交。用户允许为 GPU 验证合理等待资源、增加运行时间；这不构成长时间空载占用的许可。
- 曾遇到平台拒绝延长已运行作业的时限。不能假定可以事后延长，应事先选定有限时长；必要时保存证据、结束本次作业后重提。
- 只停止本次任务拥有的作业。不要使用 `scancel -u`、宽泛进程名杀进程等方式影响其他工作。
- 测试完成或失败后都要清理本次计算会话；持久数据、日志和回执保留。计算释放不代表存储没有配额或费用约束。

当前会话脚本使用账号 `u08162`，Slurm account `p_p08162`，QoS `cpu-500_core-l40-8_card-a800-8_card`。这些是脚本中的现有配置，不是可对所有用户复用的通用参数。

## 4. 路径、端口与环境边界

### 4.1 路径

远端 `~` 在此账号下是 `/share/home/u08162`。

| 位置 | 用途及注意事项 |
| --- | --- |
| 本地 `E:\VSCodeProject\MdsPlatform` | 源代码及 `deploy/hpc` 部署工具；先检查未提交修改 |
| 本地 `E:\VSCodeProject\Multimodal Data Space Platform` | 原本地运行存储相关目录；不要当成远端临时目录清理 |
| 本地 `reports/hpc-deployment-20261029/` | 迁移归档、快照和验收证据；有敏感备份内容，不上传到 GitHub |
| 远端 `~/apps/mds-platform/` | JAR、Java 17、启动和任务适配脚本、运行源码副本 |
| 远端 `~/packages/mds-platform/` | 导出镜像归档、SIF、校验及镜像身份文件 |
| 远端 `~/backup/mds-platform/snapshot-01/` | 已核验的初次迁移备份；不是每轮开发都应覆盖的目录 |
| 远端 `~/data/mds-platform/` | 正在演进的 PostgreSQL、MinIO、回执、trace、缓存、任务状态 |
| 远端 `~/data/mds-platform/runs/<Slurm作业号>/` | 当前会话的各服务日志、就绪信息和验证结果 |
| 远端 `~/data/mds-platform/runtime/<Slurm作业号>/<容器ID>/` | 动态任务状态、日志、独立工作目录；不能把这里的容器ID当成Slurm作业号 |

`reports/` 被 Git 忽略，另一个工作区或机器不一定有这些文件。缺失时从已授权的原工作区或远端定位，不能宣称已读到证据。不得因报告目录缺失就重新恢复数据库。

### 4.2 网络与访问

| 访问位置 | 地址 |
| --- | --- |
| 登录 SSH | `logini.tongji.edu.cn:10022`，账号 `u08162` |
| 本机浏览器访问 HPC | `http://127.0.0.1:18888/data-space/index.html`，必须保持隧道 |
| 本机通过隧道访问 HPC API | `http://127.0.0.1:18888/api/...` |
| 计算节点后端 | `127.0.0.1:8888` |
| MinIO内部及本机隧道 | `127.0.0.1:19000`；签名URL的主机、端口必须一致 |
| 计算节点检索／QA／接收端／证据服务 | `18080 / 18081 / 18082 / 18083`，均回环监听 |
| 计算节点 vLLM | `127.0.0.1:8000` |

同一个 `127.0.0.1` 在 Windows、登录节点和计算节点上指向不同机器。旧测试脚本若写死本地 `8888`，可能测到原 Docker 服务；在 Windows 验证 HPC 应使用 `18888`。只转发登录节点的 localhost 不能访问计算节点上的服务。

历史上代理将域名解析为 `198.18.0.22` 后出现连接重置。用户当时保持 Xshell 登录，从实际连接核实的可用目标是 `192.168.212.245:10022`。这是**历史观测地址**，不是永久配置承诺。域名正常时优先域名；使用其他地址前核实实际网络，并保留 `HostKeyAlias=[logini.tongji.edu.cn]:10022` 和 `StrictHostKeyChecking=yes`。不关闭主机校验，不替用户修改 VPN/代理配置，不索取密码或私钥。

计算节点每次分配可能变化。使用现有 Connect 脚本取得节点和公钥，不复用历史 `gpu4005` 等节点名。Xshell 成功登录也不代表 OpenSSH 工具已经认证成功。

## 5. 开始一轮开发时的顺序

1. 读原任务目标，检查 `git status --short` 和涉及文件的差异，识别迁移改动与其他对话的未提交工作。
2. 先做本地设计、修改、静态检查和适合的单元测试。不要为了写代码保持远端服务在线。
3. 用 Status 查询作业；需要远端诊断时先确认任务归属、实际运行版本、数据范围和日志，不把旧 READY 文件当作当前健康证明。
4. 根据第6节判断需要更新脚本、JAR还是SIF，完成构建、传输准备和版本清单。
5. 按第7节更新远端测试副本，再申请匹配的有限测试会话。
6. 验证实际部署代码已生效，然后按第9节验证功能；记录失败、实际输入和限制。
7. 获取证据后停止本次作业，确认没有遗留活动任务；向用户汇报修改、验证及剩余问题。

如果原任务只要求分析、设计或文档，不需要执行后面的构建、上传和提交作业。

### 5.1 会话命令

在仓库根目录的 PowerShell 中执行。下面代码中的 `$jobId` 应填入本轮 Start 返回的实际整数作业号，不能使用历史验收作业号。

```powershell
$hpcServer = 'logini.tongji.edu.cn'  # 只有核实后才替换为其他可用地址
.\deploy\hpc\session.ps1 -Action Status -Server $hpcServer

# 数据管理、质量、索引、发布、交付测试
.\deploy\hpc\session.ps1 -Action Start -Mode CPU -Minutes 20 -Server $hpcServer

# 需要真实模型时改用这条；CPU/GPU两个Start择一，不同时运行
# .\deploy\hpc\session.ps1 -Action Start -Mode GPU -Minutes 30 -Server $hpcServer

# 将Start返回的作业号赋给$jobId，然后执行：
.\deploy\hpc\session.ps1 -Action Status -JobId $jobId -Server $hpcServer
# 确认ready=True后连接；该命令保持运行，另开终端执行其他检查
.\deploy\hpc\session.ps1 -Action Connect -JobId $jobId -Server $hpcServer
```

结束时在另一个终端使用同一 `$jobId` 和服务器参数：

```powershell
.\deploy\hpc\session.ps1 -Action Stop -JobId $jobId -Server $hpcServer
.\deploy\hpc\session.ps1 -Action Status -JobId $jobId -Server $hpcServer
.\deploy\hpc\session.ps1 -Action Status -Server $hpcServer
```

Stop 返回 `cancelRequested` 仅表示取消请求已提交；随后确认作业终态和活动列表。关闭网页或隧道不会停止 sbatch。主动结束测试会话可能在 Slurm 中显示 `CANCELLED`，必须结合测试结果说明原因，不能自动判定业务失败。

| 模式 | 资源基线 | 功能范围 |
| --- | --- | --- |
| CPU | `amd`，8核、24GB | 后端、存储、检索、工作服务；不启动 vLLM/QA |
| GPU | `L40`，1卡、7核、48GB | 完整服务，包括真实模型及问答 |

默认就绪会话30分钟，可设置1–120分钟；Slurm总时长另加15分钟用于启动和清理，排队截止时间12小时。排队时间、实际分配运行时间、就绪后测试时间是不同概念。`ready=True` 是启动就绪标记，不是持续健康保证，后续仍检查接口和作业状态。

### 5.2 多对话或多工作区协作

本地另开对话、分支或worktree不会自动隔离远端。现有脚本都指向同一套 `apps/mds-platform` 和 `data/mds-platform`，部署锁也不等于代码发布锁。开始前确认是否有其他开发任务正在使用或准备更新这套环境；不要以自己的本地版本覆盖另一对话已部署但尚未合并的修改。需要并行远端环境时，先设计独立应用目录、数据目录、端口和配置，再实现隔离，当前脚本没有这个功能。

## 6. 修改什么，远端才会更新

**本地修改不会自动生效，远端也没有通用热更新或一键发布工具。** Start/Stop 只控制会话，既不上传源码，也不构建镜像。下表描述当前 `serve.py` 的加载方式。

| 修改对象（本地） | 远端实际加载来源 | 更新动作 |
| --- | --- | --- |
| `deploy/hpc/serve.py`、`session_control.py`、`runtime_cli.py`、`slurm_exec.py`、shell入口 | `~/apps/mds-platform/` 同名文件 | 上传对应文件及依赖，核验后在下一会话使用；不在活动任务中替换适配器 |
| `deploy/hpc/session.ps1` | Windows本地脚本 | 本地更新；如果调用协议改变，同时更新远端控制脚本 |
| 后端Java、配置和资源 | `~/apps/mds-platform/sirius-backend.jar`，由私有Java17启动 | 本地构建完整可执行JAR，核验、备份、替换并重启会话；接收端也使用这个JAR |
| 后端任务模板 `src/main/resources/script/command.sh` | `~/apps/mds-platform/command.sh`，经 `APP_JOB_SCRIPT_PATH` 读取 | 另外同步LF脚本；只更新JAR不会更新这个外部模板 |
| `services/frontend/**` 前端页面 | 后端JAR内 `static/data-space/**`，提供 `/data-space/` 页面 | 当前 `backend/pom.xml` 将 `../frontend` 打包为资源；重新构建、部署后端JAR，不是只上传前端目录或启动本地开发服务器 |
| `services/qa-service` 的服务代码 | `qa.sif` 镜像内代码 | 重新构建、导出和转换QA镜像，或显式实现并验证源码绑定；当前启动器没有绑定QA源码目录 |
| `services/search-service` | `search.sif` 镜像内代码、模型及相关依赖 | 更新搜索SIF；绑定的 `/cache` 是缓存，不是源码 |
| `services/index-builder` 及其 `professional` 组件 | `index.sif` 内 `/app` | 更新索引SIF；业务/证据工作服务也复用其运行环境和部分解析组件，必须一起评估 |
| `services/business-worker` | `~/apps/mds-platform/source/business-worker/` 绑定到 `/business-worker` | 同步需要的整组脚本，记录增量版本，重启会话；若依赖变化，还需更新index镜像 |
| `services/evidence-worker/worker.py` | `~/apps/mds-platform/evidence-worker.py` **覆盖绑定** `/evidence-worker/worker.py` | 更新这个覆盖文件；只改 `source/evidence-worker/worker.py` 会被旧覆盖文件遮住 |
| evidence-worker的其他文件 | `source/evidence-worker/` 绑定目录，及index镜像中被导入的组件 | 按实际import来源决定同步目录还是重建index镜像，不只看本地文件名 |
| Qwen模型权重 | `source/qa-service/Qwen3.5-0.8B` 绑定至 `/models/Qwen3.5-0.8B:ro` | 独立暂存和核验权重，修改版本及配置后进行GPU验证；不是更新QA源码就换了模型 |
| vLLM及其依赖 | `vllm.sif`，基线vLLM 0.17.0 | 依赖变化需新SIF；启动参数在 `serve.py`，单纯参数变化只更新启动器 |
| SQL迁移 | JAR内Flyway迁移及远端现有数据库 | 新增兼容迁移、先备份再验证；不能为适配新代码清空持久数据库 |
| 后端 `script/minio` 初始化脚本 | `source/backend/src/main/resources/script/minio` 绑定至 `/scripts:ro` | 单独同步绑定源码；仅更新JAR不会更新这里的初始化脚本，策略变化需检查既有对象及凭据影响 |

涉及多个服务的协议、解析格式或索引清单变化时，按一组兼容版本发布。增加GPU不会自动让CPU检索、索引或证据工作服务改用GPU；是否用GPU由代码、镜像和作业参数共同决定。

远端宿主Python基线为3.6.8，`serve.py`、任务适配和会话控制等宿主脚本保持3.6兼容。SIF内Python版本与依赖可以不同，不把本地较新Python通过编译等同于宿主可运行；涉及语法和标准库新API时核对目标解释器。Linux脚本使用UTF-8、无BOM、LF换行。

## 7. 后续代码发布与回滚流程

以下是开发操作规范，不是已经实现的自动发布命令。现有脚本主要用于首次迁移和有限测试会话；先阅读实现，不能假设具备完整版本管理功能。

### 7.1 通用步骤

1. **形成修改清单**：记录本地文件、构建命令、测试结果、远端目标、兼容依赖和回滚文件。建议为每轮建立 `reports/hpc-development/<日期-任务>/`，清单中不放密码、令牌、私钥或带签名URL。
2. **先准备，再占资源**：本地构建，远端以独立候选文件名或发布目录暂存。传输不需要GPU；大型归档校验、解包、SIF转换放进有限CPU作业。
3. **核验内容**：对照本地与远端SHA-256和大小。比较实际产物而不是只看Git提交号；工作区可能包含已授权但未提交的修改。
4. **停止后切换**：确认没有应用和动态任务仍在使用将替换的文件，保留上一版文件及版本清单，再在同一文件系统中原子切换。不要原地覆盖运行中的JAR/SIF、模型或任务适配器。
5. **启动有限会话**：核查实际加载的文件、镜像身份、绑定路径、参数和日志。不能因新文件已上传就宣称新版本生效。
6. **完成对应验证**：按修改范围执行第9节，保存业务和调度两类证据；完成后停止作业。
7. **失败时回滚应用**：先释放失败会话，再切回兼容的旧文件组。数据库、对象和已发布索引不随代码自动回滚；存在不可逆数据迁移时，必须单独设计恢复方法，不能直接覆盖现有数据。

可复用的本地检查入口：

```powershell
git status --short
git diff --check
python -m unittest discover -s deploy/hpc -p 'test_*.py'

# 后端改动时，在services/backend目录构建，并按改动运行必要测试
Push-Location .\mdsPlatform\services\backend
try {
    mvn clean package
    if ($LASTEXITCODE -ne 0) { throw '后端构建失败，停止发布；不能使用旧JAR' }
} finally {
    Pop-Location
}
Get-FileHash .\mdsPlatform\services\backend\target\sirius-backend-1.0-SNAPSHOT.jar -Algorithm SHA256
```

`mvn -DskipTests package` 只能证明构建，不能称为测试通过。Markdown或简单配置说明修改不需要启动GPU验收。

### 7.2 镜像更新特别注意

- `export_images.py` 只导出当前本地固定标签指向的已有镜像，不会从刚改的源代码构建。先检查Dockerfile、构建上下文、代码副本及模型下载来源，生成明确的新镜像ID，再导出。不要运行 `compose up --build` 意外替换原本地服务。
- `convert_images.sh` 使用固定名称；**只要同名 `.sif` 已存在就跳过构建**。不能上传新归档、重复运行脚本后就宣称SIF已经更新。应在独立候选路径构建验证，停止会话后再切换固定运行文件；若复用旧脚本，先安全备份并明确处理已有目标，不能丢弃旧版。
- `upload.ps1` 上传到 `~/packages/mds-platform/`，不是 `apps`；按文件大小决定首次传输、续传或跳过。同样大小的新版本可能被跳过，旧部分文件也不能与新归档混合续传。用独立文件名暂存并重新核验哈希。
- `make_checksums.py` 面向完整首次迁移目录，会检查镜像、快照、application包和Java包。不要误以为它是单个新镜像的发布命令，也不要覆盖初次迁移证据。
- 当前启动器根据 `packages/mds-platform/image-identities.json` 的 `index.reference` 和 `index.image_id` 每次生成 `apps/mds-platform/images.json`。只手改后者会在下次启动丢失。
- 新索引镜像要同时处理归档身份、SIF哈希、启动器引用和任务白名单。旧任务可能记录旧的 `sha256:` 镜像ID；不得悄悄把旧ID映射到新内容。保留旧版本或明确让旧任务无法重跑，并为新版本创建新任务。
- 适配器不是完整Docker实现，仅支持已实现的参数和已登记的本地镜像。新镜像、挂载、资源或GPU参数应显式扩展并验证；不允许默默忽略不支持的选项。

### 7.3 源码包与备份标记

`prepare_bundle.py` 不是增量部署器。它固定解包到 `apps/mds-platform/source` 和 `backup/mds-platform/snapshot-01`，已有目录的 `.verified` 不匹配时会拒绝继续。不要删除标记来强制覆盖，也不要把旧哈希标记改成新值冒充已经验证。

`source/.verified` 反映首次归档解包，不会自动重算之后的源码变化。如果对绑定目录进行增量部署，另存清晰的文件哈希及版本清单；或者实现并核验版本化源码路径。单独覆盖的 `command.sh`、`evidence-worker.py`、JAR和部署脚本同样需要记入发布清单，旧 `application.tar.gz` 不包含全部后续适配状态。

## 8. 迁移适配中不能随意移除的配置

| 配置／行为 | 已知原因 |
| --- | --- |
| 私有Java17 | 平台默认Java版本曾不足以运行后端 |
| `APP_DOCKER_BINARY=runtime_cli.py` | 保留后端任务接口契约，实际执行Singularity/Slurm；代码中的Docker命名不代表远端有daemon |
| `APP_JOB_SCRIPT_PATH` | 宿主Java不能按Docker固定路径 `/app/script/command.sh` 找到任务模板 |
| `DS_RECEIVER_BIND`、`EVIDENCE_BIND_HOST` | HPC设为回环监听，保留本地Docker默认值 |
| `MINIO_PUBLIC_ENDPOINT=http://127.0.0.1:19000` | 使内部和Windows隧道使用同一个预签名URL地址 |
| `MDS_HOSTS_FILE`、Java hosts文件及SIF `/etc/hosts` 绑定 | 单节点服务仍需兼容既有 `minio`、`sirius`、`local-receiver` 等主机名 |
| 环境文件 `shlex.quote`、`--cleanenv --no-eval` | 带空格的鉴权头曾被错误解析；不能只拼接未经转义的 `KEY=value` |
| MinIO客户端 `MC_CONFIG_DIR=/tmp/mc-config` | 普通HPC用户不能写镜像默认 `/root/.mc` |
| `search-cache:/cache` | 索引预加载曾写满Singularity小型临时层导致QA失败 |
| 动态任务独立 `/root`、`/tmp` 绑定 | 任务模板、输入、输出需要可写工作区；避免依赖镜像只读层或过小临时层 |
| `FLASHINFER_WORKSPACE_BASE=/tmp` 等模型缓存变量 | 单设HOME不足，FlashInfer曾向不可写的 `/share` 写缓存失败 |
| `MDS_EVIDENCE_LOCK_DIR` 和共享锁挂载 | 维持索引解析与证据服务的CPU队列约束，不能分别使用互不相通的锁目录 |
| `--nv` 和Slurm分配的 `CUDA_VISIBLE_DEVICES` | 使用实际分配的GPU，不手动选择未分配设备 |

资源实现存在差异：常驻服务共用Slurm分配总资源，尚未逐项复刻Compose每个服务的独立限制；动态索引有步骤级CPU/内存限制，`RLIMIT_NPROC` 按同一Linux UID计数，比Docker单容器PID限制更严格。无swap任务会检查节点swap，不能删除检查后声称等价。当前适用于本账号按需测试，不等于生产多租户隔离。

现有模型基线为Qwen3.5-0.8B，`max-model-len=2048`、`max-num-seqs=2`、每提示最多2张图、GPU内存利用率0.78。资源更大也不能未经验证就改大上下文、改变模型职责或忽略真实模型输入预算。

## 9. 功能验收与原开发目标的衔接

### 9.1 验证取决于改动范围

| 修改范围 | 最低有意义的实际验证 |
| --- | --- |
| 前端／后端接口 | 确认18888实际提供新版本；正常登录；修改的交互及失败分支；以对应远端请求与持久化状态核对 |
| 上传／发布／质量 | 独立测试范围内上传，逐字节读回；原始快照冻结；真实质量检查、缺失声明时的拒绝、合法发布 |
| 解析／索引／任务适配 | 创建新候选版本或显式新构建；分页覆盖全部输入、路径/大小/SHA核验；实际子任务、日志、退出码、输出上传、预热及READY回写；拒绝或取消应停止计算和上传 |
| 检索／问答／证据 | 选择正确dataset/version/index；实际命中、原文或区域读取、证据与模型真实输入、trace及响应；权限撤销或输入缺失不能继续用旧缓存伪装成功 |
| 消费授权／交付 | 未获权拒绝、正式申请/审批/双方确认、独立测试接收端实际交付、回执签名和包内文件核对；不向真实外部业务接收方发送测试数据 |
| 生命周期／资源 | 真实作业内测试退出、失败、取消及重新启动；检查数据库/对象保存和资源释放，不只做命令字符串单测 |

继续保留原开发任务关于不可变快照、索引候选切换、来源与派生证据关联、分页输入、权限边界的要求。不要为赶通过而直接改数据库状态为READY/PASSED、硬编码答案、伪造trace、跳过校验或用演示响应替代真实路径。

模型能力例外只适用于已经排除检索、证据传输、计算、权限和环境问题后确认的模型回答错误。若原开发目标本身是改进检索或证据理解，这些目标仍需完成；不能以“模型内容可忽略”结束它们。

### 9.2 现有探针有状态和副作用

- `http_probe.py` 不是纯只读脚本：缺少探针账号时会在远端副本创建账号；GPU模式还调用固定文档数据集的真实问答。
- `functional_probe.py` 创建并复用测试数据集、账号、质量报告、产品、合约和交付，状态保存在 `data/mds-platform/functional-probe.json`。再次执行可能复用旧READY索引而没有重新运行刚修改的构建器；旧测试合约也可能过期。
- 不通过删除状态JSON假装进行全新测试，避免丢失关联后重复创建或放宽授权。需要新一轮验收时，显式建立新fixture范围、版本和幂等键，保留旧证据，并检查本轮确实生成了新的任务／trace／回执。
- `tunnel_probe.py` 依赖既有测试数据和通过stdin提供的测试账号，验证本机隧道与签名对象下载；不能代替完整浏览器业务交互。
- `model_probe.py` 只覆盖模型调用，不代表检索、数据权限或业务流程通过。
- `--storage-only` 和 `snapshot_local.py` 属于迁移/恢复路径，涉及存储恢复或暂停本地写入服务。普通开发不要为“健康检查”重新运行它们。

### 9.3 本次已有证据的准确含义

以下为2026-10-30的历史验收，可作为对照，不是以后新版本的自动通过证明：

- 初始94,927个S3对象清单和大小核对通过，初次快照逐文件哈希核验通过；不据此推断以后新增对象都已验证。
- 作业3193736中的真实索引构建完成并READY；该父作业最终FAILED，是后续产品发布被质量门禁拦截。不要改写历史失败状态。
- 作业3193742 COMPLETED：复用此前上传的测试快照及3193736构建为READY的索引，完成新质量报告、发布门禁、产品发布、消费授权、实际交付和原文核对；该作业没有重新跑一遍上传和索引构建。最终质量报告是 **PARTIAL、已测断言100分、blockingCount=0**，不是全项质量通过。
- 独立本机.NET RSA收据验签通过，原文与交付包逐字节一致。
- 会话3193745中GPU实际生成及文档证据链路通过；返回后主动取消释放资源。`CANCELLED` 与业务测试成功可以同时成立，需分别报告。
- 该CL-A26问题只选到标题区域，未回答具体规格；这是仍需单独分析的检索/内容问题，不可全部归因于模型。
- 浏览器页面已实际打开；正常登录和签名对象下载另经正式API与SSH隧道验证。没有把“看见登录页”写成“全部UI操作已验收”。

证据位于 `reports/hpc-deployment-20261029/`：`functional-probe-3193742.json`、`independent-receipt-verification.json`、`tunnel-probe-3193745.json`、`http-probe-3193745.json`、`qa-response-3193745.json`、`qa-trace-3193745.json`。本次最终JAR SHA-256为 `7e879ececc0b39590392af4f084c72c74d1ba87da3d5b387dc513decc095921c`；新发布必须重新记录，不能复用这个值。

## 10. 数据、密钥与缓存处理

- 初始备份不是远端当前数据。新测试可能已产生账号、版本、索引、授权、日志、回执，不重新导入旧备份覆盖它们。
- 原用户、组织、源目录和已审阅资产保持原样。测试fixture要有明确名称、ID、版本及用途，修改前冻结目标范围。
- 已发布数据与索引不原地改内容；按原业务流程建立新候选，构建、预热并核查引用后再切换。清理只针对核实无引用的精确对象，不直接操作MinIO物理存储文件来删数据。
- 缓存命中不等于新算法已运行。改动索引、解析或证据协议时，核对版本键、清单和缓存来源；不要为了消除旧结果一键清空全库或共享缓存。
- 发布记录和持久化日志需要脱敏。不要打印 `secrets.json`、环境文件、账号密码、私钥、完整Bearer令牌或预签名URL。已有探针账号凭据也不应写进交接文档或提交记录。
- 迁移初期曾因Windows长路径漏列文件而产生不完整清单；旧33MB快照不能复用。再次处理深路径备份时使用已有长路径处理与逐成员核验逻辑。

## 11. 故障定位顺序

| 现象 | 先核查什么 |
| --- | --- |
| SSH认证前断开、DNS失败 | 校园网/VPN、代理分流、Xshell实际连接地址、OpenSSH认证；保留严格主机验证 |
| 无法打开18888 | 作业是否RUNNING且ready，隧道是否存活、实际计算节点是否匹配、本机端口是否被占用 |
| CPU模式QA不可用 | 该模式未启动模型与QA，先确认测试模式 |
| 更新后行为完全不变 | 是否误访问本地8888；JAR/SIF是否真实更新；是否命中旧fixture、旧索引或覆盖绑定 |
| 任务脚本下载500 | 远端 `command.sh` 是否存在、LF格式、`APP_JOB_SCRIPT_PATH`是否对应当前部署 |
| 环境变量含空格时启动失败 | env-file转义和no-eval，不在日志中展开令牌 |
| 临时目录只读或空间不足 | 搜索 `/cache`、动态任务 `/tmp`/`/root`、模型FlashInfer缓存是否仍按启动器绑定 |
| 索引一直BUILDING | 查业务jobId/runId、适配器containerId及Slurm步骤日志；区分排队、实际运行、失去allocation和发布/预热失败 |
| 100分但发布被拒绝 | 查看质量status、blockingCount、声明和未评估范围，不只看分数 |
| QA有回答但内容不对 | 按检索范围→命中→原文/区域→模型真实输入→模型生成逐层核对，不统一归为模型错误 |
| 已结束会话仍有数据和目录 | 持久化是预期行为；用调度状态判断资源占用，不靠目录是否存在 |

先读 `apps/mds-platform/session-<作业号>.log` 和 `data/mds-platform/runs/<作业号>/` 的服务日志；动态任务读 `runtime/<作业号>/<容器ID>/output.log`、`state.json`、`supervisor.log`。用 `sacct` 查看作业终态、时长、退出码，遇到问题保留真实失败证据。

## 12. 每轮开发结束时给用户的交代

用简洁中文说明：改了什么及为何需要；远端实际部署了哪一版；本地检查和HPC实测各覆盖什么；业务结果和作业终态；遗留限制；本次作业是否已释放。若因用户要求保留正在运行的会话，明确作业号和到期时间；否则默认测试结束即停止。

至少记录：修改文件、产物SHA/镜像ID、作业号及节点、启动参数、数据集/版本/索引范围、相关run/trace/receipt、失败原因、最终资源状态。不用源码检查或健康接口代替实际业务验证，不将尚未测试的功能描述为完成。

---

## 可直接发给原开发对话的交接指令

> 请先读取 `E:\VSCodeProject\MdsPlatform\deploy\hpc\CODEX_HANDOFF.md`，并核对其中链接的当前启动脚本。继续本对话原来的数据空间开发目标、已接受的设计和未完成事项，不重新执行首次迁移。代码仍在本地仓库维护，主要集成验证改在同济HPC的Singularity/Slurm有限会话中；HPC与本地数据独立，修改源代码后要确认远端实际加载的JAR、SIF或绑定脚本已更新。先检查现有修改、部署状态和数据范围，再实施本任务。不要在登录节点运行应用、长期空载占用GPU、覆盖旧数据或绕过授权和质量门禁。模型能力导致的回答错误可以按用户要求单独记录，但检索、证据输入、权限和迁移错误仍要处理。完成后保存真实验证证据并释放本次作业，再说明结果和限制。
