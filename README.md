# Uma Mobile Decision Pipeline

纯手机赛马娘采集、会话解析、版本化事实包、剧本模拟与只读决策流水线。

## 产品目标

本仓库建立一条可持续更新、可重新解析、可追溯证据的单一产品主线：

```text
游戏进程内 hlpatch SO
→ 独立 Android 薄采集 App
→ 原始 session 导入与完整性校验
→ MessagePack 与同包多 section 解析
→ 版本化 MDB / Hachimi / 机制事实包
→ 通用育成模拟器 + 独立剧本插件
→ 蒙特卡洛 / MCTS / 分布价值模型
→ 只读推荐、备选、风险和证据边界
→ UNKNOWN 交给 Agora 闭合并回写规则与测试
```

本仓库不替代历史证据库，也不把旧项目直接拼接成新产品。旧仓库继续保留；这里只有通过当前迁移门槛的现行契约、代码、机器事实与测试。

## 固定职责边界

### `xf8410/hlpatch`

游戏进程内唯一采集端，负责Hook、原始字节持久化、session/file索引和只读导出端点。SO不承担复杂中文解释、长期搜索或决策模型。

### 本仓库

负责Android采集、离线解析、版本化事实包、通用模拟、剧本插件、搜索和只读推荐。日常已知结构应由确定性代码自动处理，不依赖在线AI逐包操作。

### `xf8410/umamusume-scenario-mechanics`

保存机制证据、研究过程、纠错和历史结论。它允许出现`HYPOTHESIS`、`INVALIDATED`与跨版本材料；这些状态不得自动进入本仓库生产事实层。

### `xf8410/hlpatch-observation-architecture`

保存观测架构说明与单局原始协议归档。本仓库通过稳定契约读取原始会话，不复制其历史架构状态为现行实现。

### `xf8410/Agora-Workbench`

作为适配器、报告查看与UNKNOWN研究入口，不承载采集、解析、模拟和事实管理的唯一实现。

## 规划目录

```text
contracts/             SO、App、解析器之间的稳定契约
collector-android/     独立 Android 薄采集 App
session-core/          session导入、校验、MessagePack、多section
session-cli/           GitHub Actions及命令行入口
facts-schema/          事实状态、版本和证据引用Schema
facts-current/         仅当前有效的机器可读事实
sim-core/              通用育成状态、动作、转移和评分接口
scenario-plugins/      剧本独立增量；首个目标为14_ramen
search-core/           蒙特卡洛、MCTS、分布价值和不确定性
adapters/              hlpatch、GitHub与Agora适配
fixtures/              当前dataset金样与原始证据引用
reports/               自动完整性报告与UNKNOWN清单
```

目录只在对应阶段开始时建立，不预先提交空壳实现。

## 事实迁移门槛

进入`facts-current/`或生产代码的每条事实必须同时满足：

1. 标明适用的游戏版本、资源版本、MDB、SO构建或`dataset_id`；
2. 能回指原始协议、MDB行、运行时观测或可复现实验；
3. 证据状态为`CONFIRMED`，或边界明确且有测试覆盖的`SUPPORTED`；
4. 不与机制仓库当前纠错结论冲突；
5. 自动测试能够验证字段、公式、状态转移或输出；
6. 未闭合字段和规则输出`UNKNOWN`，不补默认值；
7. 不迁移固定运行时地址、无版本常量、旧协议时序、跨剧本硬编码、候选公式或已经失效的README断言。

`HYPOTHESIS`、`INVALIDATED`和无可追溯来源的旧常量留在原仓库或机制证据库，不进入本仓库事实层。

## 上游采用规则

上游只按模块审计，不整仓复制：

| 上游 | 主要参考范围 | 采用边界 |
|---|---|---|
| `EtherealAO/UmaAi` | 早期模拟器、手写估值、蒙特卡洛和数据导出 | 2023女神杯历史基线；公式和数据必须重新验证 |
| `hzyhhzy/UmaAi` | 状态、动作、转移、搜索、NN、自我对弈和训练链 | 仓库已停止维护；近似机制不得进入事实层 |
| `hzyhhzy/UmamusumeResponseAnalyzer` | MessagePack DTO、回合状态和剧本插件结构 | 桌面小黑板架构仅作解析结构参考 |
| `xulai1001/umaai-rs` | Rust workspace、模拟、协议、搜索、MCTS、NN和训练器 | 当前剧本实现需按dataset逐项校验 |
| `xulai1001/BreedersScenarioAnalyzer` | 剧本插件隔离、DTO消费和展示 | 剧本硬编码不得上升为通用规则 |

任何迁移必须保存上游仓库、ref、commit、许可证、选取文件和本地修改记录。

## 分阶段计划

| 阶段 | 工作 | 主要交付物 | 进入下一阶段的门槛 |
|---|---|---|---|
| P0 | 三方仓库来源、分支和许可证谱系 | 上游清单 | 每个候选模块有精确来源与状态 |
| P1 | 采集、协议、DTO、回合和插件审计 | 协议能力矩阵 | 可复用结构与旧假设已经分开 |
| P2 | 状态、动作、转移、评分、MC/MCTS/NN审计 | 决策引擎矩阵 | 每项标记复用、重写、验证或拒绝 |
| P3 | 用户旧仓库职责与重复能力审计 | 仓库角色图 | 每个旧仓库只有一个历史或现行角色 |
| P4 | 长期记忆到需求与事实映射 | 需求台账 | 必须、后置、历史、UNKNOWN分开 |
| P5 | 契约和Schema冻结 | ADR、契约、迁移规则 | 关键接口可测试且无业务伪实现 |
| P6 | 最小骨架和CI | 可构建工作区 | CI通过且没有复制旧结论 |
| P7 | 逐单元迁移 | 小型PR序列 | 当前SHA终态后才开始下一单元 |

## 固定实施顺序

```text
A1 hlpatch /storage/read_range
→ dataset fingerprint
→ MDB/resource导出
→ Android可靠下载与GitHub提交
→ session-core完整性与MessagePack
→ 多section和跨包状态机
→ 当前事实包
→ 通用模拟核心
→ 拉面杯插件
→ 搜索与只读推荐
```

本仓库的建立不改变`hlpatch`现有唯一活动分支；与A1无关的实现不得混入该分支。

## 数据与解析原则

- 实际获得的原始URL、Headers、Cookie、请求体、响应体、MessagePack、二进制和字段完整保留；
- 派生解析、关系和中文时间线通过引用关联原始文件，不替代原始字节；
- 同一响应可同时包含多个section，解析器必须全部保存并保留包内顺序；
- 解析失败记录原文件、偏移、解析器版本和原始错误；
- dataset不匹配时明确失败，不静默使用其他版本事实解释旧session；
- 采集、存储、导出、上传、解析和展示链不得加入字段隐藏、替换、过滤或裁剪路径。

## 首版范围

首版包含完整采集、可靠导入、确定性解析、版本化事实、拉面杯模拟和只读推荐。自动点击、自动操作和连续屏幕识别不属于首版。

## 分支与CI纪律

- 默认分支保持唯一；
- 同一任务只有一个活动`workbench/*`分支；
- 一次提交一个最小功能单元；
- 当前SHA的CI未终态前不提交下一单元；
- 失败只按原始诊断修复一个问题；
- 禁止`retry`、`v2`、`final`等平行分支；
- 禁止用空壳、占位符或截断内容覆盖真实文件。
