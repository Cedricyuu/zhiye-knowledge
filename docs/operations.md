# 运行与开发记录

> 本文保留了项目开发机的操作说明与分阶段验收记录，供排查和理解实现时参考。文中的“本机已配置”“本机已验证”及硬件、数据库状态均描述当时的开发环境，不代表新下载的项目已经完成这些配置。首次运行请先按仓库首页的 H2 检索模式启动。
>
> 文中 `.runtime/`、`data/`、`backups/`、`.cache/` 均为本地生成目录。历史验收日志、账号数据、凭据、备份与模型不会随公开源码发布；历史测试次数也不代表本次运行结果或任意资料的模型准确率。

面向个人学习资料的 Java + Vue 应用：注册登录 → 上传资料 → 依据资料问答或出题练习 → 查看原文引用 → 回看历史与批改。

支持**中文语义检索 + 关键词检索 + 基于原文的模型回答**。中文 BGE 向量模型在 Java 进程内运行；可使用项目配套的 Qwen3 4B 本地模型生成答案，无需购买 API。回答要点附引用，后端核对支持它的原文摘录后才保存结果。

## 本机日常使用

当前电脑已经准备好程序、MySQL 和本地模型。在项目根目录双击以下文件即可，无需先打开开发工具：

| 入口 | 用途 |
| --- | --- |
| `启动知页.cmd` | 后台启动数据库、模型和应用，确认就绪后打开页面；重复点击会复用已运行的项目 |
| `停止知页.cmd` | 停止本项目后台进程，释放模型占用的显存；保存的数据保留 |
| `检查知页.cmd` | 检查应用、实际数据库连接及本地模型是否就绪 |
| `备份知页.cmd` | 备份当前项目 MySQL，并自动进行一次独立恢复校验 |

入口使用 Windows 自带 PowerShell，支持中文目录。启动、停止、备份操作互斥，避免重复点击造成并发操作。检查或操作失败会保留窗口显示原因，日志在 `.runtime/`。如果使用终端自动调用，可加 `-NoPause`；启动时加 `-NoBrowser` 可跳过打开浏览器。

备份保存在 `backups/mysql-日期时间-随机编号/`，其中 `knowledge.sql` 包含当前业务表（启用笔记练习后为七张），`manifest.json` 记录时间、行数、SHA-256 和恢复校验结果。备份使用事务快照，可在应用运行时执行；随后导入本实例内新建的临时库，重新导出并比较文件摘要，通过后删除该临时库。只有 `restoreVerified: true` 才代表校验完成。此过程不会覆盖现用知识库；备份启动项目数据库后会保留它运行。

备份含账号记录和资料正文，保存在本机且已加入忽略提交目录。这是业务数据备份，代码与模型仍使用项目目录中的文件；恢复时将 SQL 导入空库，避免覆盖正在使用的数据。该入口面向当前项目专用 MySQL 配置。

## 运行

环境：Java 17、Maven 3.6.3+、Node.js 22.12+（或 20.19+）。MySQL 路线需要已安装的 MySQL 8.x，`mysqld.exe` 可在 PATH 中找到。本机使用 Java 17 / Node 24 / MySQL 8.0.38。

在 PowerShell 中：

```powershell
cd D:\AI知识库
# 首次准备本地生成模型；当前电脑已下载时会核对并复用文件
.\scripts\setup-local-ai.ps1
.\scripts\build.ps1
# 首次建立项目专用 MySQL，并保留已有 H2 账号、资料和历史；已配置时不会重复导入
.\scripts\use-mysql.ps1
.\scripts\start.ps1 -Mode local-ai
```

打开 [http://127.0.0.1:8088](http://127.0.0.1:8088)，点击“创建账号”。没有预置账号或密码。进入后可点击“导入示例，试一试”，再问“青竹分享会什么时候开始？”或上传自己的资料。

如果当前已有后台演示进程，直接打开页面即可；先运行 `.\scripts\stop.ps1` 可以停止本项目记录的后台进程，再用 `start.ps1` 在终端启动。停止脚本会核对 PID 与本工作区的 JAR 路径。

`build.ps1` 安装锁定依赖、执行前端异步状态回归、类型检查和构建、运行后端测试，然后生成包含前端的 `backend/target/knowledge-api-0.1.0.jar`。首次构建需要下载依赖。模型和依赖都保存在工作区 `.cache/`。

`start.ps1` 默认 `auto`：存在本地模型时启动生成式问答，否则启动语义原文检索。`-Mode local-ai` 明确要求本地生成模型；`-Mode retrieval` 只运行语义检索。`-Background` 在后台运行应用并记录 PID；`stop.ps1` 默认停止应用、模型和项目专用 MySQL，`-KeepModel` / `-KeepDatabase` 可以分别保留对应进程。前台运行时 Ctrl+C 停止 Java 应用，其余进程用 `stop.ps1` 停止。

本地 Qwen3 4B Q4_K_M 文件约 2.5GB，llama.cpp Vulkan 运行程序约 33MB，中文 BGE 及 ONNX 依赖随 Maven 下载。本机使用 RTX 4060 8GB；运行模型会占用数 GB 显存。只监听 `127.0.0.1`，不安装服务或改动驱动。两个文件都使用固定版本及官方 SHA-256 校验。来源：[Qwen 官方 GGUF](https://huggingface.co/Qwen/Qwen3-4B-GGUF)、[llama.cpp 固定 release](https://github.com/ggml-org/llama.cpp/releases/tag/b11433)。本地模型端口为 18081，访问密钥随机生成并保存在忽略提交的 `.runtime/model-api-key`。

日常前端开发可以分别运行：

```powershell
# 终端 1，在根目录启动后端（后端修改后重新运行）
mvn.cmd -pl backend spring-boot:run

# 终端 2
cd D:\AI知识库\frontend
npm.cmd run dev
```

开发页面为 [http://127.0.0.1:5173](http://127.0.0.1:5173)。Vite 把 `/api` 转发至 8088。运行 `npm run preview` 仅能预览静态页面，不转发后端 API；完整演示使用打包后的 8088 页面。

## 已实现的范围

| 功能 | 实现 |
| --- | --- |
| 账号与资料隔离 | BCrypt 密码、24 小时随机会话令牌、数据库仅保存令牌 SHA-256；全部文档、检索与历史查询限制当前用户 |
| 文档管理 | PDF / UTF-8 TXT / Markdown；去重、解析、切分、预览、删除 |
| 混合检索 | BGE-small-zh-v1.5 量化中文向量 + BM25，RRF 融合排序，返回最多 4 个片段，可限定文档 |
| 来源定位 | 文档名、PDF 页码、片段编号、字符位置与原文快照；点击引用打开相应片段 |
| 历史与反馈 | 最近 100 条问答、正负反馈及撤销、单条删除，重启后保留数据 |
| 模型回答 | LangChain4j ChatModel 调用本地 Qwen 或兼容接口；结构化输出、逐要点引用、连续原文摘录校验，失败明确报错 |
| 索引持久化 | 向量保存在数据库，重启后复用；旧文档可按需补建或手动重建索引；删除文档级联删除向量 |
| 笔记练习 | 从所选笔记出一道简答题，提交后逐要点批改、展示参考答案与原文；支持草稿自动保存与恢复、原题再练、错题筛选和近期学习概览 |

学习自己的 Notion 笔记：将导出的 `.md` 文件上传后，进入“笔记练习”，默认使用全部资料；取消“全部资料”可勾选某一章。点击“开始出一题”，用自己的话回答，再“提交批改”。在文档问答输入“问问我问题”或“考考我”也会打开练习并自动出题。每次只出一道题，题目会尽量避开近期练过的片段；仅有标题、纯链接或文字太少的文件不出题。

参考答案和原文在提交前不由接口返回。批改将要点分成正确、部分正确、遗漏和错误，由后端计算 0–100 分；得分项必须附上能在学生回答中核对的原话，正确的补充说明不应扣分。模型批改仍可能误判，需要结合原文核对。回答最多 4000 字符，模型失败时保留页面中的草稿以便重试；已批改的题目不能用另一份答案覆盖。练习保留出题时的来源快照，删除资料后如需彻底清除内容，还应删除相关练习记录。

停止输入约 0.8 秒后自动保存草稿，页面显示“正在保存草稿”或“草稿已保存”；刷新或回到练习页后可继续上次待答题。切换题目、出新题和提交批改前会先保存当前输入。保存失败时保留文字，可点击“重试保存”；多个页面编辑同一道题发生冲突时，可选择“使用已保存内容”或“保存当前草稿”。已保存的草稿随账号保存在数据库；当前浏览器会话另外按账号保存最多 10 题未保存输入的临时副本，帮助恢复刷新前的最近输入。草稿不计入已作答次数或评分，已批改和旧版未审校题目不能修改草稿。

批改后点击“原题再练”，会沿用原题和原文依据新建一份作答，原有答案、评分均保留；已有待答复练时直接继续该次记录及其草稿。复练提交前仍隐藏参考答案。“待复习”按同一道题最近一次批改判断：有未掌握要点的题目进入待复习，全部要点正确后进入已掌握。近期概览和“全部 / 待作答 / 待复习 / 已掌握”筛选均基于最近 100 条保留记录，平均分按已批改的作答次数计算，旧版未审校记录不计分；删除记录后重新计算。复练关系保存在新记录的题目快照中，已有数据和表结构无需迁移。

单份资料限制 10 MB、提取文本不超过 12 万字符、PDF 不超过 100 页；每个账号最多 50 份资料。保存解析后的文字片段、向量及文件元数据，不保存原始附件。不支持扫描件 OCR、DOCX、图片识别或多轮追问。每次问答独立使用当前问题和所选资料。

新上传资料的片段最长 420 字符，相邻重叠 60 字符，PDF 不跨页切分，避免截断 Unicode 代理对。已有文档保留原有切分方式。Markdown 作为普通文字处理。预览的字符位置相对于规范化后的文字，不是 PDF 排版坐标。

删除文档会级联删除片段和向量。历史保留当时的原文快照；彻底清除内容时同时删除相关问答。没有相关资料，或模型判断资料不足时返回 `NO_EVIDENCE`。语义分数和融合分数都不是置信度。

模型必须输出 `answerable` 与 `claims`，每个要点包含引用编号及连续原文摘录。后端检查编号、摘录是否存在和输出格式，再生成带编号的回答。摘录存在仍不能保证概括与原文完全一致，小模型的事实判断和复杂推理仍需人工核对。模型失败不会伪装成成功的原文检索。单台本地模型一次处理一个生成请求，繁忙时返回 429。

## MySQL 配置

本机已经切换至**项目专用 MySQL + 本地 Qwen**。`start.ps1` 会读取忽略提交的 `.runtime/database.json` 并自动启动数据库；运行地址仍是 8088。新检出且没有选择数据库时使用 `data/knowledge.mv.db` 中的 H2，运行 `use-mysql.ps1` 才切换至 MySQL。

项目 MySQL 使用本机现有程序，以后台进程运行，监听 `127.0.0.1:13307`，数据在 `data/mysql/`；数据库为 `ai_knowledge`。应用账号仅有该库所需权限，随机密码和本实例管理密码放在 `.runtime/mysql.json`，客户端配置也在忽略提交的 `.runtime/` 中。启动程序不读取系统 MySQL 配置，也不注册 Windows 服务。中文路径在当前 MySQL 8.0 程序上需要临时目录别名，脚本自动建立并核对指向本工作区的专用 Junction；实际数据仍在项目目录。

`use-mysql.ps1` 停止本项目应用后，把原 H2 文件备份到 `.runtime/backups/before-mysql-*/`，以只读方式打开原库，将现有业务表导入**空的**项目 MySQL；旧 H2 没有练习表时跳过该表。导入在一个事务内完成，逐表比较行数与 SHA-256 内容摘要；任何不一致都会回滚。目标已有数据时拒绝覆盖。账号、会话、文档、片段、向量及历史保持原 ID；原 H2 文件保留。核对记录在 `.runtime/mysql-migration.json`。数据库时间字段使用微秒精度。

日常使用：

```powershell
.\scripts\start.ps1 -Mode local-ai -Background
.\scripts\stop.ps1
```

`-Database mysql` 明确使用这个专用实例；`-Database h2` 使用原 H2 文件。切回 H2 只切换数据源，不会同步切换后新增的资料。不要删除 `data/` 或 `.runtime/mysql.json`；后者是访问该实例的密码记录。

如果要使用自行准备的 MySQL 数据库，在启动终端设置：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'mysql'
$env:KB_DB_URL = 'jdbc:mysql://127.0.0.1:3306/ai_knowledge?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC'
$env:KB_DB_USERNAME = 'knowledge'
$env:KB_DB_PASSWORD = '<本项目数据库密码>'
.\scripts\start.ps1 -Database external
```

外部模式只创建不存在的 `kb_*` 表，数据库和账号需自行准备。应用使用同一套 Mapper 与 `schema.sql`；本机已在真实 MySQL 8.0.38 上完成业务和模型验收。后续修改已有表结构仍需要明确的迁移方案。

## 使用自己的模型服务

使用提供 Chat Completions 兼容接口的服务，或已启动并提供兼容接口的本地模型。设置在启动进程的环境变量中，密钥不写进前端或版本库。`.env.example` 只是字段参考，不会被自动读取。

```powershell
$env:KB_AI_ENABLED = 'true'
$env:KB_AI_BASE_URL = '<模型服务的 API 根地址，通常包含 /v1>'
$env:KB_AI_MODEL = '<该服务实际支持的模型名>'
$env:KB_AI_API_KEY = '<该服务密钥>'
.\scripts\start.ps1 -Mode external
```

`-Mode external` 使用当前终端里设置的模型配置，不启动配套本地模型。`-Mode retrieval` 显式关闭生成；`auto` 优先使用已下载的配套本地模型。兼容接口用法参考 [LangChain4j 官方文档](https://docs.langchain4j.dev/integrations/language-models/open-ai/)。需要服务支持 JSON 模式。模型最多等待 75 秒，不自动重试；失败返回 502。发送的数据只含当前问题、当前用户的候选片段及其来源信息。

## 代码入口与验证

```text
frontend/src/App.vue                   页面、上传与问答交互
frontend/src/api.ts                    请求与会话
backend/src/main/java/cn/knowledge/
  auth/                               注册、登录、令牌认证
  document/DocumentController.java     上传接口入口
  document/DocumentService.java        文档业务与数据库事务
  document/DocumentParser.java         提取文字、按页切分
  persistence/DocumentMapper.java     文档与片段 SQL
  rag/QuestionController.java         问答、历史和反馈入口
  rag/QuestionService.java            检索、回答与引用持久化
  rag/KeywordRetriever.java           中文/英文词项与 BM25
  rag/EmbeddingService.java           Java 进程内的中文 BGE 向量推理
  rag/VectorIndexService.java         向量生成、保存、按用户加载与补建
  rag/HybridRetriever.java            余弦相似度、BM25 与 RRF 融合
  rag/ModelGateway.java               LangChain4j 模型接入
  practice/PracticeController.java    出题、提交批改、继续与删除练习
  practice/PracticeService.java       资料范围、记录及答案展示控制
  practice/PracticeModel.java         出题和逐要点批改的模型协议
backend/src/main/resources/schema.sql  7 张业务表
```

上传链：`App.vue → POST /api/documents → DocumentService → DocumentParser → VectorIndexService → DocumentMapper / EmbeddingMapper`。文件解析和向量计算先在数据库事务外完成，再以短事务保存文档、片段和向量，每批最多写入 64 条；任一批失败会整体回滚。提交前锁定当前用户记录并重新检查数量与重复内容，防止并发上传突破 50 份限制。

再理解问答链：`App.vue → QuestionController → QuestionService → HybridRetriever → ModelGateway → AnswerMapper`。SQL 先限制用户和所选文档，再加载向量与文本，融合检索结果后交给模型。向量持久化在普通数据库表中，由 Java 计算余弦相似度，适合当前小型个人知识库；没有假称使用 MySQL 原生向量索引或专用向量数据库。

```powershell
# 在项目根目录运行后端验证
mvn.cmd -B -ntp test
# 前端类型与产物检查
cd frontend
npm.cmd test
npm.cmd run build
```

后端集成测试使用真实 Spring、MyBatis、内存 H2 和真实中文 BGE 向量推理，覆盖权限、会话、上传、PDF 页码、索引重建、向量删除、同义表达召回、历史和反馈。固定问题和“纯关键词零命中、语义检索命中”的对照用例用于回归，不能当作生产准确率。模型协议测试使用本地 HTTP 固定响应，单独覆盖无依据、伪造编号、伪造摘录、格式错误和上游失败；真实 Qwen 问答另行通过运行中的服务验收。

启动配套模型后，可单独运行真实 Qwen 验收，使用独立内存数据库，不污染自己的知识库：

```powershell
$env:KB_RUN_LOCAL_AI_TESTS = 'true'
mvn.cmd -B -ntp -pl backend test '-Dtest=LocalRagAcceptanceTest'
Remove-Item Env:KB_RUN_LOCAL_AI_TESTS
```

八个用例覆盖时间与准备事项、换种说法问退款、拒绝编造预算、限定文档后回答不同规则，以及相似店铺规则区分、跨片段回答、矛盾资料对照、否定条款与复合问题。默认构建跳过这组依赖 GPU 模型的验收，只运行普通回归测试。

真实 MySQL 验收复用上述业务及模型用例，另核对四字节中文字符和时间精度：

```powershell
# 26 项 MySQL 业务及事务检查（含 7 项练习业务）
.\scripts\test-mysql.ps1
# 加上 8 项真实问答和 6 项真实练习模型案例
.\scripts\test-mysql.ps1 -WithLocalModel
# 只复测模型案例，仍使用独立验收库
.\scripts\test-mysql.ps1 -WithLocalModel -ModelOnly
# 只验证笔记练习：7 项业务及 6 项真实模型案例
.\scripts\test-mysql.ps1 -WithLocalModel -PracticeOnly
```

脚本只使用本项目在 13307 端口的实例，创建随机名称的独立验收库，结束后删除该次创建的库；实际知识库不参与测试。MySQL 测试类默认跳过，直接指定测试类而未提供专用验收配置时会拒绝运行。

此前功能验收：14 项 H2 业务/语义检索、8 项模型协议、15 项 MySQL 业务、4 项 MySQL + 真实 Qwen 验收，共 41 项通过。真实模型复测中修复了复合问题遗漏第二个子问题的情况。上述结果来自固定用例，不代表任意资料和问题的正确率。

交付收尾增加了 3 项健康检查测试，并复测已有的真实 H2 公共接口用例，4 项均通过。`/api/health` 实际验证数据库连接，连接失败时返回 HTTP 503 和 `databaseReady: false`；模型是否启用是配置状态，本地模型探活由“检查知页”单独验证。

Windows 双击入口已验证完整启停、重复启动复用进程、端口被占用时拒绝启动，以及数据库断连后的检测与恢复。备份已在临时库恢复并通过 SHA-256 校验；完整重启后的六张业务表也与该备份摘要一致。运行验收记录在本机 `.runtime/delivery-health.json`、`delivery-lifecycle.json`、`delivery-data.json`。

本轮优化增加了旧会话 401 响应、文档预览乱序和退出登录后迟到响应的回归测试。文档列表通过一次分组统计取得索引数量，非空列表固定使用两次业务查询；语义候选用大小为 12 的队列筛选，融合阶段最多处理 24 个候选，保留原有相关性门槛和排序规则。当前仍会读取所选资料的全部片段和向量，尚未把这些优化视为大规模知识库的性能验收。

2026-10-18 优化验收：按受影响范围分批运行，6 项前端回归、32 项普通后端检查、19 项 MySQL 业务及事务检查、8 项 MySQL + 真实 Qwen 问答均通过。真实模型评测发现并修正了冲突对照遗漏一方规定、复合问题漏答后半部分的情况；这是固定案例的通过结果。新版已打包启动，实际运行的页面及资源与前端构建 SHA-256 一致，更新前后的六张业务表摘要一致。详细记录在 `.runtime/optimization-acceptance.json`。

2026-10-19 收尾验收：修复列表刷新响应乱序、旧请求干扰当前加载状态和退出后的迟到错误提示，前端回归扩展至 11 项并全部通过，类型检查和生产构建通过。当前打包程序在 8088 上通过了登录、上传及索引、文档列表和预览、真实模型问答、原文引用、历史、反馈及撤销、删除测试数据、退出后令牌失效的 HTTP 流程。更新前后六张业务表摘要一致；验收完成后，除会话表外的五张业务表摘要与测试前一致，验收登录可能清理已过期会话，新建令牌已注销。运行记录在 `.runtime/completion-acceptance.json`，此前后端测试未因本次前端改动重复运行。

2026-10-20 笔记练习验收：16 项前端回归、15 项新增后端检查、7 项 MySQL 练习业务、6 项真实模型案例分批通过。覆盖答案隐藏、权限、失败不保存、重复提交、正确与错误作答、额外解释、诱导给分、评分编号及原文证据。用实际导入的继承笔记副本完成独立浏览器验收，验证出题、批改、刷新继续、原始“问问我问题”口令及窄屏布局；包含练习记录的七表备份恢复校验通过。数据库仅新增练习表，升级前后的原六表摘要一致；清理验收数据后，原有账号和业务内容摘要一致。记录在 `.runtime/practice-acceptance.json`。模型案例仅用于回归，不能代表任意问题的批改准确率。

2026-10-22 复练开发验收：26 项前端回归、29 项受影响后端回归、15 项独立 MySQL 练习验收通过，前端类型检查和生产构建通过。真实 Qwen 与浏览器完成“首次答错 0 分 → 原题复练 → 答对 100 分 → 移出待复习”，另覆盖刷新继续、原记录保留、重复请求复用、删除后重新统计和 390px 窄屏布局。更新前的七表备份恢复校验通过；测试账号清理后，原六张业务表（不含会话表）的摘要与更新前一致。记录在 `.runtime/review-ui-acceptance.json` 和 `.runtime/review-acceptance.json`。这些固定案例用于回归，不代表任意笔记的出题或批改准确率。

2026-10-23 草稿开发验收：38 项前端回归、26 项后端练习回归和 26 项独立 MySQL 验收通过，类型检查及生产构建通过。浏览器使用两个临时账号和真实 Qwen 验证自动保存、保留空白和表情、刷新与切页恢复、刷新前最近输入恢复、保存中继续输入、清空草稿、保存失败重试、编辑冲突选择、复练草稿恢复及账号隔离；首次作答 0 分、复练 100 分，原评分保留。后端另以确定的并发交错验证批改期间的新草稿不会被旧结果覆盖。临时数据清理后，原六张业务表摘要一致，更新前七表备份恢复校验通过，页面资源与构建摘要一致。记录在 `.runtime/draft-acceptance.json`，本轮无需表结构迁移。

技术版本：Spring Boot 3.5.16、MyBatis Starter 3.0.5、LangChain4j 1.22.0、PDFBox 3.0.7、Vue 3.5.44、Vite 8.3.4。兼容性参考 [Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[MyBatis Starter 版本矩阵](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)、[LangChain4j 入门](https://docs.langchain4j.dev/get-started/)。项目参考其官方 API 与示例组织实现，没有直接复制一套完整业务系统。
