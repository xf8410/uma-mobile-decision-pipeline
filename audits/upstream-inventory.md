# P0 上游来源、分支与许可证清单

审计日期：2026-08-09

## 1. 文档目的

本清单固定首批上游候选的精确仓库、ref、commit、许可证和采用状态。它只回答“代码来自哪里、目前能否进入迁移评估”，不把上游公式、常量或README描述升级为本仓库事实。

后续若采用任何代码，必须在本文件追加：

```text
upstream_repository
upstream_ref
upstream_commit
upstream_path
upstream_license
local_destination
local_changes
verification_dataset
verification_status
```

只借鉴架构思想、不复制代码时，也应记录参考文件和精确commit。

## 2. 状态定义

| 状态 | 含义 |
|---|---|
| `REFERENCE_ONLY` | 可研究架构或流程，不直接迁移代码和业务事实 |
| `ELIGIBLE_FOR_CODE_REVIEW` | 许可证已识别，可继续做文件级代码审计；不代表已经准入 |
| `LICENSE_BLOCKED` | 当前ref未找到可确认的仓库许可证，禁止复制代码，等待来源澄清 |
| `FACT_REVALIDATION_REQUIRED` | 机制、公式、常量和数据必须使用当前dataset重新验证 |
| `HISTORICAL_BASELINE` | 只作为历史谱系和设计演进参考 |

## 3. 仓库总表

| 仓库 | 固定ref与commit | 主语言 | 仓库许可证证据 | 当前用途 | P0状态 |
|---|---|---|---|---|---|
| `EtherealAO/UmaAi` | `master@d2c7bdd1cb70ba41210b2824d89c44429a9855d3` | C++ | 根`LICENSE.txt`为MIT文本，但版权年份和姓名仍是模板占位 | 2023女神杯模拟、手写策略、MC和导出历史基线 | `HISTORICAL_BASELINE`, `FACT_REVALIDATION_REQUIRED` |
| `EtherealAO/Hachimi-Edge` | `main@eed0a986db0c8220bfbd0f2076222849e748290b` | Rust | 根`LICENSE`为GPL-3.0文本 | Hachimi运行时结构、Hook与翻译来源的外围参考 | `REFERENCE_ONLY` |
| `hzyhhzy/UmaAi` | `master@e855d52e6bf7284269df84ab61aca4f687c19b26` | C++ | 根`LICENSE.txt`为MIT文本，但版权年份和姓名仍是模板占位 | 模拟器、动作、搜索、NN、自我对弈和训练链的主要结构参考 | `ELIGIBLE_FOR_CODE_REVIEW`, `FACT_REVALIDATION_REQUIRED` |
| `hzyhhzy/UmamusumeResponseAnalyzer` | `master@5f3e6e8eea4b387842791c4921c4d2dbcddfa46e` | C# | 根`LICENSE`为GPL-3.0文本 | MessagePack DTO、回合模型、插件体系和剧本隔离参考 | `ELIGIBLE_FOR_CODE_REVIEW`, `FACT_REVALIDATION_REQUIRED` |
| `xulai1001/umaai-rs` | `master@0d3d4f5c0f5263f2d54ce90d357628dac44a9294` | Rust | 当前完整树无根许可证文件，workspace `Cargo.toml`也未声明license | Rust模拟、协议、采集、搜索、MCTS、NN和训练器结构 | `LICENSE_BLOCKED`, `REFERENCE_ONLY` |
| `xulai1001/BreedersScenarioAnalyzer` | `main@fcacd8687c99d9851baa1113bb5655e307e0af4c` | C# | 根`LICENSE.txt`为GPL-3.0文本 | 剧本插件、DTO消费、EventLogger与展示结构 | `ELIGIBLE_FOR_CODE_REVIEW`, `FACT_REVALIDATION_REQUIRED` |
| `xulai1001/UmaSimData` | `master@e14e9aca295728ef1e28026c8c3152d6c7b05f73` | Python/数据 | 根`LICENSE`为CC BY-NC-SA 4.0 | 数据包布局、版本文件和更新流程参考 | `REFERENCE_ONLY`, `FACT_REVALIDATION_REQUIRED` |

许可证识别仅记录仓库中实际存在的文本，不替代后续文件级来源核验。依赖、二进制、生成数据和上游复制文件可能具有不同许可证，迁移时必须逐文件检查。

## 4. 来源谱系

### 4.1 `EtherealAO/UmaAi` 与 `hzyhhzy/UmaAi`

`EtherealAO/UmaAi` README明确把源代码链接指向`hzyhhzy/UmaAi`，其当前master最新commit作者也显示为`hzy`。因此将它归为hzyhhzy实现的历史发布/镜像基线，而不是独立的新决策引擎来源。

迁移规则：

- 算法演进以`hzyhhzy/UmaAi`精确ref为主要审计对象；
- `EtherealAO/UmaAi`只用于核对2023女神杯时期行为、文档和数据导出差异；
- 两仓同源文件不得重复迁移或形成两个事实来源。

### 4.2 `hzyhhzy/UmaAi` 的相关分支

仓库存在多条剧本和实验分支，不能把它们视为一个同时成立的实现。首批相关ref固定为：

| 分支 | commit | 角色 |
|---|---|---|
| `master` | `e855d52e6bf7284269df84ab61aca4f687c19b26` | 当前文档入口；README明确项目已停止维护 |
| `grandMasters` | `1125dc16c2b3b969a1195283f0b4c12629ddfa2f` | 2023女神杯历史实现 |
| `UAF` | `5c310ddc3d4acccfdfbc6b94c2e53aa64907b33d` | UAF剧本参考实现 |
| `Legend` | `d88235dbb95f8c8927f2a7a1629e46783928bd79` | Legend剧本参考实现 |
| `LArc` | `d69b0c815e7e9bad503a40f0783ebb24c2f981af` | 凯旋门剧本参考实现 |
| `Cook` | `90b8c7c803820a42f4878a26f66bc01be1916472` | Cook剧本历史线 |
| `Cook2` | `2b06fb97135e17cf83075b405b21b074ccb7effa` | Cook平行历史线；不得与`Cook`自动合并事实 |
| `NNInputV2` | `6773c5c3d434a6aee105774d638b76d81580238b` | 状态编码实验参考 |

`backup*`、`test*`和高分实验分支暂不作为迁移候选。若P2发现唯一必要实现只存在其中，必须先补充精确ref和差异证明。

### 4.3 `UmamusumeResponseAnalyzer` 与场景插件

`xulai1001/BreedersScenarioAnalyzer`直接引用`UmamusumeResponseAnalyzer`、其`Gallop` DTO、`TurnInfo`与插件接口，属于URA插件生态中的剧本扩展，不是独立协议核心。

迁移规则：

- DTO字段与回合对象在P1统一审计；
- 场景插件只提供“如何隔离剧本增量”的结构样本；
- `RequiredPoints`、固定回合判断、训练等级推演和展示阈值等业务代码不得进入通用层；
- 任何协议字段必须与hlpatch当前原始session和dataset重新对齐。

### 4.4 `xulai1001/umaai-rs`

当前workspace明确分为：

```text
crates/umasim
crates/analyzer
crates/umaai
```

树中存在协议、温泉剧本、采集器、解释器、flat search、MCTS trainer、神经网络和训练样本模块，结构上最接近本仓库未来Rust核心。但当前head提交信息说明正在进行事件格式和基础模拟重构，并暂时屏蔽其他功能；在代码级审计前不能把当前head称为稳定基线。

当前ref未发现许可证文件或Cargo license声明，因此：

- 可以继续阅读并记录架构；
- 不复制源码、测试或数据文件；
- P2若拟采用具体实现，必须先取得可确认的许可来源，或只依据公开接口思想独立实现并保存差异记录。

### 4.5 `xulai1001/UmaSimData`

该仓库是“计算器和小黑板数据存档”，不是当前游戏事实源。其`version.toml`、目录分包和自动更新流程可在P1/P5作为版本化数据包设计参考；数据内容进入本仓库前仍须从当前MDB、协议和dataset重新生成并验证。

## 5. 按模块的初始候选

| 本仓库未来模块 | 首选审计来源 | 次选来源 | 当前迁移结论 |
|---|---|---|---|
| `session-core` DTO与回合结构 | `hzyhhzy/UmamusumeResponseAnalyzer` | `xulai1001/umaai-rs` protocol | 只进入P1，不迁移旧业务结论 |
| `sim-core`状态与转移接口 | `hzyhhzy/UmaAi` master | `xulai1001/umaai-rs` umasim | 进入P2；所有公式重验 |
| `search-core` | `hzyhhzy/UmaAi` Search/NN/Selfplay | `xulai1001/umaai-rs` search/trainer | 进入P2；Rust源码当前受许可证阻断 |
| `scenario-plugins`隔离方式 | URA `TurnInfo*`与插件接口 | `BreedersScenarioAnalyzer` | 借鉴边界，不继承硬编码事实 |
| `facts-current`生成 | 当前hlpatch导出 + 当前MDB + 机制仓库闭合证据 | `UmaSimData`版本布局 | 不导入上游现成数据为事实 |
| Hachimi适配 | 当前Hachimi/翻译仓库精确key与运行时观测 | `Hachimi-Edge`结构 | 后置到语言索引阶段 |

## 6. P0结论与P1入口

P0已固定首批7个仓库及关键分支的来源和commit。当前没有任何上游代码或业务事实获准迁入生产目录。

P1只审计以下链条：

```text
完整原始响应
→ MessagePack DTO
→ common response / scenario dataset
→ 回合状态
→ 同包多section
→ 插件分发
```

P1交付物为`audits/protocol-capability-matrix.md`，必须逐项标明上游字段结构、当前hlpatch原始证据要求、可复用接口和需要拒绝的旧假设。
