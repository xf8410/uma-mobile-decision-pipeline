# P0 上游来源、分支与集成清单

审计日期：2026-08-09

## 1. 文档目的

这些仓库属于同一项目组的连续工程，不按彼此无关的第三方项目处理。本清单固定首批候选的精确仓库、ref、commit、来源关系和集成状态，目的是：

- 找出同一功能的最新实现与历史祖先；
- 避免把同源文件重复迁入并形成多套事实；
- 区分可直接整合的工程结构与必须按当前dataset重验的业务规则；
- 保留贡献来源、分支演进和发布时需要的许可证信息。

它不把“复制源码”作为目标，也不因仓库间许可证差异阻止同项目组内部整合。后续集成任何实现时记录：

```text
source_repository
source_ref
source_commit
source_path
local_destination
local_changes
verification_dataset
verification_status
```

## 2. 状态定义

| 状态 | 含义 |
|---|---|
| `ARCHITECTURE_REFERENCE` | 吸收模块边界、接口或流程设计 |
| `CODE_INTEGRATION_CANDIDATE` | 可进入文件级代码审计并选择性整合 |
| `FACT_REVALIDATION_REQUIRED` | 机制、公式、常量和数据必须使用当前dataset重新验证 |
| `HISTORICAL_BASELINE` | 只作为历史谱系和设计演进参考 |
| `ACTIVE_REFACTOR` | 当前head正处于重构，集成前需要确定稳定切点 |

## 3. 仓库总表

| 仓库 | 固定ref与commit | 主语言 | 仓库许可证记录 | 当前用途 | P0状态 |
|---|---|---|---|---|---|
| `EtherealAO/UmaAi` | `master@d2c7bdd1cb70ba41210b2824d89c44429a9855d3` | C++ | 根`LICENSE.txt`为MIT文本，版权字段仍是模板占位 | 2023女神杯模拟、手写策略、MC和导出历史基线 | `HISTORICAL_BASELINE`, `FACT_REVALIDATION_REQUIRED` |
| `EtherealAO/Hachimi-Edge` | `main@eed0a986db0c8220bfbd0f2076222849e748290b` | Rust | 根`LICENSE`为GPL-3.0文本 | Hachimi运行时结构、Hook与翻译来源参考 | `ARCHITECTURE_REFERENCE` |
| `hzyhhzy/UmaAi` | `master@e855d52e6bf7284269df84ab61aca4f687c19b26` | C++ | 根`LICENSE.txt`为MIT文本，版权字段仍是模板占位 | 模拟器、动作、搜索、NN、自我对弈和训练链的主要来源 | `CODE_INTEGRATION_CANDIDATE`, `FACT_REVALIDATION_REQUIRED` |
| `hzyhhzy/UmamusumeResponseAnalyzer` | `master@5f3e6e8eea4b387842791c4921c4d2dbcddfa46e` | C# | 根`LICENSE`为GPL-3.0文本 | MessagePack DTO、回合模型、插件体系和剧本隔离来源 | `CODE_INTEGRATION_CANDIDATE`, `FACT_REVALIDATION_REQUIRED` |
| `xulai1001/umaai-rs` | `master@0d3d4f5c0f5263f2d54ce90d357628dac44a9294` | Rust | 当前完整树无根许可证文件，workspace `Cargo.toml`未声明license；作为发布元数据待项目组补齐，不作为内部集成阻断 | Rust模拟、协议、采集、搜索、MCTS、NN和训练器结构 | `CODE_INTEGRATION_CANDIDATE`, `ACTIVE_REFACTOR`, `FACT_REVALIDATION_REQUIRED` |
| `xulai1001/BreedersScenarioAnalyzer` | `main@fcacd8687c99d9851baa1113bb5655e307e0af4c` | C# | 根`LICENSE.txt`为GPL-3.0文本 | 剧本插件、DTO消费、EventLogger与展示结构 | `CODE_INTEGRATION_CANDIDATE`, `FACT_REVALIDATION_REQUIRED` |
| `xulai1001/UmaSimData` | `master@e14e9aca295728ef1e28026c8c3152d6c7b05f73` | Python/数据 | 根`LICENSE`为CC BY-NC-SA 4.0 | 数据包布局、版本文件和更新流程 | `CODE_INTEGRATION_CANDIDATE`, `FACT_REVALIDATION_REQUIRED` |

许可证列用于保留项目发布信息和补齐仓库元数据，不决定同项目组源码能否进入审计。依赖、二进制、生成数据和外部引入文件仍需逐文件记录其实际来源。

## 4. 来源谱系

### 4.1 `EtherealAO/UmaAi` 与 `hzyhhzy/UmaAi`

`EtherealAO/UmaAi` README明确把源代码链接指向`hzyhhzy/UmaAi`，其当前master最新commit作者也显示为`hzy`。因此将它归为同一项目在2023女神杯时期的发布/镜像基线，而不是独立决策引擎。

集成规则：

- 算法演进以`hzyhhzy/UmaAi`精确ref为主要审计对象；
- `EtherealAO/UmaAi`用于核对2023时期行为、文档和数据导出差异；
- 两仓同源文件只选择一条明确演进线，不在新仓库形成重复模块。

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
| `Cook2` | `2b06fb97135e17cf83075b405b21b074ccb7effa` | Cook平行历史线；需通过差异审计确定有效部分 |
| `NNInputV2` | `6773c5c3d434a6aee105774d638b76d81580238b` | 状态编码实验参考 |

`backup*`、`test*`和高分实验分支暂不作为首批候选。若P2发现唯一必要实现只存在其中，再补充精确ref和差异证明。

### 4.3 `UmamusumeResponseAnalyzer` 与场景插件

`xulai1001/BreedersScenarioAnalyzer`直接引用`UmamusumeResponseAnalyzer`、其`Gallop` DTO、`TurnInfo`与插件接口，属于同一URA插件生态中的剧本扩展，不是独立协议核心。

集成规则：

- DTO字段与回合对象在P1统一审计；
- 场景插件提供“如何隔离剧本增量”的实现样本；
- `RequiredPoints`、固定回合判断、训练等级推演和展示阈值等业务代码只能进入对应剧本候选层；
- 任何协议字段必须与hlpatch当前原始session和dataset重新对齐后才能进入事实层。

### 4.4 `xulai1001/umaai-rs`

当前workspace明确分为：

```text
crates/umasim
crates/analyzer
crates/umaai
```

树中存在协议、温泉剧本、采集器、解释器、flat search、MCTS trainer、神经网络和训练样本模块，结构上最接近本仓库未来Rust核心。当前head提交信息说明正在进行事件格式和基础模拟重构，并暂时屏蔽其他功能，因此P2需要先确定：

- 哪个commit是重构前最后完整可运行基线；
- 当前head中哪些新数据结构应保留；
- 被暂时屏蔽的功能如何恢复到单一Rust主线；
- 与C++ UmaAi之间是重写、移植还是并行实验关系。

仓库许可证元数据待项目组补齐，但不再标记为源码集成阻断。

### 4.5 `xulai1001/UmaSimData`

该仓库是计算器和小黑板数据存档，不直接等于当前游戏事实源。其`version.toml`、目录分包和自动更新流程可作为版本化数据包实现来源；具体数据进入`facts-current`前仍须从当前MDB、协议和dataset重新生成并验证。

## 5. 按模块的初始候选

| 本仓库未来模块 | 首选来源 | 次选来源 | 当前集成结论 |
|---|---|---|---|
| `session-core` DTO与回合结构 | `hzyhhzy/UmamusumeResponseAnalyzer` | `xulai1001/umaai-rs` protocol | P1选择接口和数据结构，业务字段按当前session重验 |
| `sim-core`状态与转移接口 | `hzyhhzy/UmaAi` master | `xulai1001/umaai-rs` umasim | P2决定Rust整合路线；所有公式重验 |
| `search-core` | `hzyhhzy/UmaAi` Search/NN/Selfplay | `xulai1001/umaai-rs` search/trainer | P2比较后并成单一实现，不保留两套产品搜索核心 |
| `scenario-plugins`隔离方式 | URA `TurnInfo*`与插件接口 | `BreedersScenarioAnalyzer` | 整合插件边界，业务规则保留在对应剧本层 |
| `facts-current`生成 | 当前hlpatch导出 + 当前MDB + 机制仓库闭合证据 | `UmaSimData`版本布局 | 上游数据只作为生成与差异输入，不直接冒充当前事实 |
| Hachimi适配 | 当前Hachimi/翻译仓库精确key与运行时观测 | `Hachimi-Edge`结构 | 后置到语言索引阶段 |

## 6. P0结论与P1入口

P0已固定首批7个同项目组仓库及关键分支的来源和commit。当前允许进入后续代码整合审计；尚未迁入生产目录的原因是还未完成版本、结构和业务事实核验，而不是仓库彼此无关。

P1只审计以下链条：

```text
完整原始响应
→ MessagePack DTO
→ common response / scenario dataset
→ 回合状态
→ 同包多section
→ 插件分发
```

P1交付物为`audits/protocol-capability-matrix.md`，逐项标明可直接整合的数据结构、需要重写的桌面耦合、当前hlpatch证据要求和必须拒绝的旧协议假设。
