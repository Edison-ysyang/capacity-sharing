# MATSim 客货共运扩展模块说明书

核验日期：2026-11-03。适用对象：本仓库当前工作树。说明书依据当前 Java 源码、相对 MATSim 2026.0 的差异和本地运行收据编写；历史说明文档用于交叉核对。本文不把旧构建的试验结果当作当前源码已经重新验证的结果。

## 1. 你在 MATSim 上完成了什么

你把原本面向客运出行的 MATSim 公交仿真，扩展成了可以研究**公共交通客货共运、专用货运以及受约束日内调度**的研究框架。核心工作包括：

1. 将货运订单映射成可占用原生车辆容量的代理，并要求订单 CSV、容量单位和预生成固定货运计划相互守恒。
2. 在原生公交登乘路径中增加客货准入、乘客优先/FCFS、物理货运上车时窗和装卸停站接口。车辆容量仍由 QSim 的真实车辆对象控制。
3. 在当前版本实现整单不可拆分登乘：同一订单的容量单位必须在同一次机会、同一车辆、同一物理时刻完整上车；失败回滚尚未提交的加入操作。
4. 将乘客物理拥挤、货物同乘暴露和拒载惩罚接入真实评分，保留原始暴露与估值账本，参与后续迭代的计划选择。
5. 区分客运与货运路由视图、分离专用货运服务，避免普通乘客使用专用货运线路。
6. 建立 C/M/T/D 运行适配、输入哈希、构建身份、配对检查、运行收据和科学输出。
7. 为 T 增加因果事件观测、预测、滚动候选、约束检查、控制承诺和原生司机执行桥接。
8. 增加整次出行可行性筛查、计划记忆回退和行为/物理执行时域分离，处理末班车与日终截断问题。
9. 建设杭州场景、十站合成场景及后续典型城市路网输入工程，并保留独立的分析与审计材料。

这些能力横跨原生内核接口、客货业务模块、实验运行模块和外部场景/分析工具，并非只在输出文件中事后计算“虚拟货运”。完整修改数量见 [内核与文件清单](CORE_CHANGE_INVENTORY.md)。

## 2. 代码层次与关键入口

以下路径相对仓库根目录；Java 代码主要位于 `matsim/src/main/java/`。

| 层次 | 包/关键类 | 职责 |
| --- | --- | --- |
| 原生扩展接口 | `org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy`、`PassengerAccessEgressImpl`、两个 stop handler | 在真实候选队列和真实车辆上执行登乘选择、批提交与拒载事件 |
| 客货共运业务 | `org.matsim.contrib.cotransport`，28 个生产 Java 文件 | 订单、容量单位、准入、阶段、评分、事件账本及科学事件归档 |
| 统一实验 | `org.matsim.run.ismt`，19 个生产 Java 文件 | C/M/T/D 适配、配置、输入完整性、运行身份及输出 |
| 日内动态控制 | `org.matsim.run.ismt.dynamic`，45 个生产 Java 文件 | 因果观测、预测、候选、约束、调度证据和正式门禁 |
| 行为可行性 | `org.matsim.run.r17`，10 个生产 Java 文件 | 整次出行可行性、末班车检查、计划记忆回退及双时域 |
| 运行示例 | `org.matsim.run.examples`，14 个生产 Java 文件 | 杭州、基准、混运、专运和 R17 示例入口；具体历史入口绑定各自场景 |

`cotransport` 放在主 `matsim` 模块内的 `org.matsim.contrib` 包下，当前并不是独立的 `contribs/cotransport` Maven 子模块。使用时必须构建这份修改后的 MATSim；官方发布 JAR 不包含这些接口。

业务模块装配入口是 `PassengerFreightCoTransportModule`。它读取类型化 `coTransport` 配置，或接收显式不可变 `CoTransportSettings` 并写回有效配置。它绑定订单注册、阶段管理、登乘策略、运行监控、客运专用分析及路由提供器。已有实验还通过 `SharedIsmtExecutionModule` 统一安装客货模块与 Plan-A 生命周期监听器。

## 3. 一次运行的流程

```text
配置 + 路网/人群/时刻表/车辆 + 订单/固定货运计划
  → 类型化配置和订单注册
  → 身份、容量单位、固定计划、服务隔离等启动校验
  → 每轮安装对应阶段的人群/供给、调整创新策略权重
  → 乘客计划/路由与可行性检查
  → QSim 原生车辆运行与客货候车
  → 候选排序 → 准入/窗口/容量检查 → 原生登乘提交
  → 原生事件 → 暴露/评分/订单/载荷账本
  → 计划得分影响下一轮选择，控制状态每日重置
```

T 在 QSim 日内还执行“已闭合事件前缀 → 状态/显式预测 → 候选 → 硬约束/乘客预测保护 → 提交承诺 → 原生司机执行”。该运行控制不会把事后完整轨迹改写成乘客当轮已知信息。

## 4. 订单、代理和服务链

`FreightOrderRegistry` 读取 CSV，拒绝重复订单、重复列名、缺列、非法单位数以及单位 ID 冲突；它不在运行时凭空补造缺失代理。

必需列是 `order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority`。`capacity_units` 可显式给出；正整数显式值优先，否则 `ProportionalFreightUnitizer` 使用：

```text
capacity_units = max(1, ceil(freightEquivalentPassengersPerM3 × volume_m3))
```

每个单位消耗一个原生乘客容量位置。主代理 ID 等于订单 ID；额外单位通常为 `order_id_cap_02`、`order_id_cap_03` 等，后缀由 `freightShadowToken` 决定。质量字段被保存，但当前容量换算不是独立的重量/轴重约束模型。

每个货运单位须以字符串属性 `subpopulation=freight` 标记，使用一个预生成、已选择的固定计划。货运策略通常设为 `KeepLastSelected`，避免普通乘客创新策略改变货运链。仅有 CSV 不足以完成运输，population 中必须包含对应全部容量代理及实际服务链。

当前 `WHOLE_ORDER` 启动检查进一步要求：同单各单位释放、处理/接驳、PT 线路/路线/上下车站、末端活动等完整计划语义一致；首活动结束时间等于订单释放时刻；每条 leg 有预建 route；PT route 必须显式记录线路/路线和上下车站。原生 chained PT continuation 逐单位 relocation，因此整单模式暂拒绝这种链式续乘。

货物下车并不自动代表客户交付。完成、准时和未完成统计必须依据计划终端服务事件以及完整单位集合核验。`OrderOutcomeReconstructor`、`FreightOrderRuntimeStats` 和监控器用于保留订单/单位的事件事实；独立后处理仍应核对末端步行、处理及交接是否完成。

## 5. 登乘规则：三个独立维度

### 5.1 谁先尝试上车

`boardingOrder=PASSENGER_FIRST`：本站适合当前车辆的普通乘客优先，之后货物竞争剩余空间。它不清退已上车货物，也不预留未知的下游乘客需求，因此不能保证零拒载。

`boardingOrder=FIRST_COME_FIRST_SERVED`：客货共同按原生候车队列就绪时刻排序，同秒按原生登记序号。货物须先完成前序处理/接驳才算就绪，订单释放更早并不等于更早排队。`TransitStopAgentTracker` 保留被拒载者的原就绪时钟，成功上车后清除；每天结束同时清空队列与时间账本。

### 5.2 订单是否可拆分

| 参数 | 当前含义 |
| --- | --- |
| `freightBoardingMode=WHOLE_ORDER` | 当前新默认；同单所有单位整组选择与原子提交 |
| `freightBoardingMode=UNIT_SPLIT` | 显式采用历史独立容量单位机制，可能部分上车、分车承运 |
| `wholeOrderQueuePolicy=STRICT_FIFO` | 当前新默认；已就绪整单因原生容量或货物份额上限无法接纳时，阻止后续候选绕过 |
| `wholeOrderQueuePolicy=FIT_FIRST` | 显式允许后续合适候选绕过装不下的订单 |

排序与拆分是两个维度。`PASSENGER_FIRST + WHOLE_ORDER` 和 `FCFS + WHOLE_ORDER` 都成立，但前者先有类别优先级。STRICT_FIFO 并非遇到任意拒绝都堵队列：当前代码仅对整单的 `NATIVE_CAPACITY`、`FREIGHT_SHARE_CAP` 阻塞；时窗关闭、服务不合格等另记原因。

整单 FCFS 的就绪时刻取该单全部成员中最晚的就绪时间，平局使用相应登记序号；全部成员未就绪不能把已经到站的部分当完整订单发走。

### 5.3 能否上这辆车

`TransitAccessPolicy` 管客货隔离，`BoardingAdmissionPolicy` 管货运准入阈值，物理窗口独立检查，原生车辆容量最终控制。混运货物接受后须满足：

```text
projectedFreight ≤ floor(nativeCapacity × freightShareCap)
projectedPassengers / nativeCapacity ≤ passengerLoadGate
```

注意负载门槛代码使用严格 `>` 拒绝，所以恰等于 `passengerLoadGate` 时仍允许。原生容量包括座位与站立位。历史参数 `hardCapacityRatio` 保留在配置/输出中，但 `hardCapacityRatioActive()` 返回 false，当前不是运行时二次容量上限。

窗口是 `[start,end)`：开窗时刻允许，关窗时刻拒绝新增货物上车，包括重新上车；已在车的货物可以继续到站和完成末端交付。当前整单模式从已序列化的服务窗口自动取得物理门禁，适用于混运和专运。显式 `UNIT_SPLIT` 保留历史 scenario-element 窗口语义，不能把服务窗口字段本身误认作所有历史版本都具有的物理门禁。

`freightHandlingDwellSeconds` 是货物处理发生后追加的一次停站时间接口；0 表示不追加，并不删除原生车辆上下车处理时间。复杂停站处理器仍按提交单位数量计入原生每代理处理时间。并行上下车处理器的具体事件交错不能概括成普遍“先下后上”。

## 6. 乘客暴露、评分和行为反馈

`ExposureIntegrator` 按事件间恒定载荷区间积算，时间单位为分钟：

```text
rawPhysical_i = ∫ max(0,(P+F)/C - comfortThreshold) dt
rawFreight_i  = ∫ F/C dt
physicalPenalty = betaPhysical × rawPhysical_i
freightPenalty  = betaFreight × rawFreight_i
```

`ExposureValuation` 将原始暴露转换成非负成本，监控适配器发出负的评分事件。原生旅行/活动评分继续计算真实等待和旅行时间；额外暴露不再把整个门到门时间重复扣一次。乘客拒载另按原生事件数和配置系数扣分，事件数、去重人次、去重人车站机会是不同统计量。

beta 为 0 仅关闭对应估值，原始暴露、货物占位和真实时间仍存在。未闭合乘坐和事件状态错误另有输出，不能拿缺失暴露当零。评分改变迭代间计划选择，不表示已实现当轮即时改路，也不构成行为参数已校准的证据。

## 7. 阶段控制、路由和 Plan-A

`CoTransportPhaseController` 在一次连续 Controller 运行中管理货运激活、静态供给安装与创新权重日历。存在不激活轮次时必须提供显式基线时刻表；不能把干预后的表当作无货基线。它保存并恢复登记的人群/供给，捕获实际 StrategyManager 使用的策略实例，拒绝策略实例身份漂移后悄悄失效的权重请求。

`PassengerOnlyPtRoutingModuleProvider` 和 `FreightOnlyPtRoutingModuleProvider` 提供客货各自的线路视图，并通过延迟货运生命周期尽量避免休眠货运对象扰动共同历史中的乘客随机序列。单靠相同 seed 不足以证明不同入口/构建的事件轨迹相同。

`PlanAWholeTourFeasibleModeChoice`、`PlanAFeasibleReRoute` 和 `PlanAWalkFeasibleTimeAllocationMutatorReRoute` 检查整次活动链与时刻表/执行边界的可行性。`PlanATransitBoardingDeadlineIndex` 提供时刻表登乘截止依据。创新候选不合格时，`PlanAPlanMemoryFeasibilityFallback` 优先恢复可执行的原选中计划，其次检查历史高分计划，只改工作副本。

`PlanAExecutionHorizonSwitch` 与前后两个监听器将 QSim 物理执行时域暂时延长，再恢复行为时域，并写 `plan_a_execution_horizon_audit.json`。这能区分正常恢复与异常中断；延长物理执行不能自动证明全部计划或全部订单完成。

## 8. C/M/T/D 和统一实验入口

| 方案 | 机制 | 当前解释 |
| --- | --- | --- |
| C / CONTROL | 无货运，保留基线客运供给 | 乘客基准；没有货运完成率作为可相减的零值 |
| M / MIXED | 固定表客货混运 | 研究固定供给下的容量竞争、暴露和服务可靠性 |
| T / DYNAMIC_MIXED | M 的机制上加受约束日内发车微调 | 只调已存在且允许调整的班次，保持登记的需求/容量/准入条件 |
| D / DEDICATED | 客货分离，货物仅搭专用服务 | 需明确登记专用车、班次、窗口和运营资源 |

历史 `TIMETABLE_ONLY` 仍是旧方案名，不是新版 T。专用运输额外增加资源时，M/D 或 T/D 的比较包含资源差异，不是完全等资源的纯分离效应。

`UnifiedExperimentRunner` 有 `PRESERVE_REGISTERED`、`WINDOWED_MIXED`、`UNIFIED_CURRENT` 三种 profile。前者保留旧注册语义，后两者按显式窗口机制开发。C/D 与 M/T 保留不同供给适配，不强行让 D 使用混运控制器。

`UNIFIED_CURRENT` 开发 M/T 要求给出对方 spec；`UnifiedMixedComparison` 对科学字段逐项比较，除了方案身份、控制开关以及同哈希文件路径别名外必须相同。随后执行入口核验双方全部 19 类文件引用的实际字节，只运行所选一臂。比较声明本身不是实际运行等价证明，当前配对门禁也不是持久双登记正式证书。新 profile 正式运行仍被拒绝，需要单独登记。

**当前两项关键限制：**既有登记/统一合同和动态 Controller 装配要求 `PASSENGER_FIRST`，FCFS 应使用独立通用开发配置；动态 T 还明确拒绝 `WHOLE_ORDER`，因为当前 `MixedCapacityProjection` 按独立容量单位预测。T+整单、T+FCFS 需要同步修改预测器和科学登记，不能仅通过改 XML 开关运行。

## 9. T 的日内控制模块

| 子模块 | 关键类 | 作用 |
| --- | --- | --- |
| 运行装配 | `DynamicMixedControllerModule`、`DynamicMixedQSimModule` | 在 Controller/QSim 生命周期中安装每轮独立 session |
| 观测边界 | `NativeEventPrefixObserver`、`ParallelEventDrainBuffer`、`CausalObservationBuffer`、`ClosedCoTransportObservationState` | 将原生已处理事件转为可用于决策的因果快照 |
| 已知货运 | `KnownFreightReleaseState`、`KnownFreightJourneyState` | 限定已释放/可知需求与完整服务链元数据 |
| 预测 | `ClosedPassengerArrivalForecast`、`CausalMixedServiceForecast`、`MixedCapacityProjection`、`SingleLineForecastSupply` | 利用已闭合客流历史、时刻表及显式模型生成预测；当前具有单线/单位可拆分等适用范围 |
| 候选与约束 | `RollingDepartureCandidates`、`RollingDeparturePolicy`、`DispatchConstraintChecker` | 搜索候选、登记班距/共享点/车辆接续约束、按预测乘客代价保护并比较货运目标 |
| 承诺与执行 | `DispatchCommitmentState`、`ControlledTransitDispatchBridge`、`ControlledTransitDriverAgent` | 对未锁定未来班次提交版本化承诺，在原生司机进入工作段时应用 |
| 证据与门禁 | `DynamicMixedDailyDispatchAudit`、`DynamicMixedDispatchEvidence`、配置/输入/结果/历史审计器 | 区分声明、装配、控制决定和真实执行，拒绝缺失证据的正式启动 |

控制器不能读取未来真实乘客到达、结果或全日事后事件来充当预测。原生并行事件排空与 After 监听器的顺序保留；当前桥接使用受控的已闭合前缀，存在明确观测滞后。它不通过重排事件或强制额外排空伪造零延迟观测。

乘客使用不可变公布基线进行出行前计划，运营承诺及真实执行单独保存；提前发车可能导致错过车辆和额外等待，必须保留该代价。每天运营控制从基线重置，乘客计划记忆跨轮延续。预测保护不是已实现乘客零损害或真实铁路安全认证。

## 10. 主要配置、输出和接入方式

配置模块名为 `coTransport`，须用 `ConfigUtils.loadConfig(path, new CoTransportConfigGroup())` 读取类型化扩展，再加载 Scenario、安装 `PassengerFreightCoTransportModule`。若程序直接传 `CoTransportSettings`，该不可变 settings 为执行来源，并回写配置。

建议每次显式登记 `boardingOrder`、`freightBoardingMode`、`wholeOrderQueuePolicy`、窗口、容量比例、装卸、评分和创新日历，避免当前默认值与历史构建不同。`CoTransportConfigGroup` 默认 `manageInnovationSchedule=true`，builder 的默认为 false；两条 API 接入方式不可只看一个字段就认为协议相同。

| 参数类别 | 参数 |
| --- | --- |
| 数据 | XML 使用 `freightOrdersFile`（Java settings 对应 `freightOrdersCsv()`）、`baselineTransitScheduleFile`、`activatedTransitScheduleFile`、`activatedTransitVehiclesFile` |
| 登乘 | `boardingOrder`、`freightBoardingMode`、`wholeOrderQueuePolicy`、`freightShareCap`、`passengerLoadGate` |
| 服务 | `freightEquivalentPassengersPerM3`、`freightServiceWindowStartTime`、`freightServiceWindowEndTime`、`dedicatedFreightLineId`、`freightHandlingDwellSeconds` |
| 评分 | `comfortThreshold`、`passengerPhysicalCrowdingPenaltyPerMinute`、`passengerFreightCoPresencePenaltyPerMinute`、`passengerDeniedBoardingPenalty`、货运迟到/拒载/未完成罚值 |
| 阶段 | `armMode`、`activationIteration`、`deactivationIteration`、`manageInnovationSchedule`、三个创新阶段迭代参数 |
| 文件 | `compressReports`：无损 gzip 监控 CSV；压缩不改变账本语义 |

`CoTransportRuntimeMonitor` 输出客货汇总、订单/单位、车辆负载、乘客暴露、拒载明细及整单登乘证据；`IsmtRunArtifactWriter` 与动态 artifact 模块写研究专用产物/指纹，Plan-A 写可行性/时域审计。`ScientificEventArchive` 是另外显式安装的资源受限归档：报告窗前只计数，报告窗保存除 link traversals 外事件，终轮保存全事件，并写覆盖收据。不能把过滤事件流当作全原生事件。

每种入口实际安装的 writer 和覆盖范围不同，不能假设全部文件必定产生。具体 input 文件、可运行小场景、实际 output 路径与列名应与本目录的输入输出材料配套阅读。

## 11. 已完成证据与尚未完成事项

| 证据 | 可以确认 | 不能外推 |
| --- | --- | --- |
| 相对官方 2026.0 的源码差异 | 上述接口/实现存在，12 个既有生产 Java 修改，新增功能代码已落地 | 当前全部源码在本次已重新编译/全量测试 |
| `artifacts/ismt-cmdt-framework-20260923/verification/results.json`（工作区路径） | 2026-09-23 冻结快照的 113 项 Java 纯单元测试 PASS | 当前整单新默认已被这份旧报告覆盖 |
| `artifacts/boarding-order-20261001/verification/verification.json` | 实际 `passed=true`、`tests=91`、无仿真/预测器调用，该快照登乘排序白名单通过 | T+FCFS 或当前整单模式已全面验证 |
| 十站 `1001-boarding-order-comparison`、`1001-boarding-high-q1920` | 既有固定表 Base/PP/FCFS 原生运行、事件后处理材料存在 | 这些 UNIT_SPLIT 历史结果等于当前 WHOLE_ORDER 结果 |
| 十站 `1002-atomic-base-mix-dedicated/revisions/d100_20261004/audit/parallel_acceptance_v1/receipt.json` | 实际 status=PASS，225 个 D100 作业、45 需求、50 轮独立运行/覆盖验收 | 收据明确 `scientific_conclusions_verified=false`，不等于全部科学结论验收 |
| [本次微型示例](../../examples/cotransport-mini/README_zh.md)及其 `expected-output/` | 当前1681个生产Java源码重新编译成功，真实第0轮订单/容量/原生事件/负分对账PASS | 只覆盖固定表、整单、无容量不足的简化物流链，不覆盖T/FCFS及全量测试 |

2026-11-03 检查时，`1002-atomic-base-mix-dedicated/registration/current_results.json` 和 D100 的 `audit/result_promotion_v1/publication.json` 均不存在。因此 D100 已验收运行不能写成“当前结果映射已经正式切换”。根目录 `CURRENT_ATOMIC_TASK.txt` 是历史接续记录，不以其中旧进程号推断今天仍在运行，也不在本次说明书整理中恢复旧批任务。

仍需单独处理的事项包括：T 的整单预测/FCFS 扩展与登记；新统一矩阵的持久配对证据；D100 结果采用发布及受影响派生分析；当前代码剩余机制及测试的完整验证；现实行为参数校准和真实运营约束。合成压力、单种子相关迭代的均值/范围以及创新关闭后的稳定，都不能直接证明独立重复实验、严格均衡或现实线路安全。

## 12. 推荐阅读顺序

1. `CoTransportSettings`、`CoTransportConfigGroup`、`FreightOrderRegistry`：理解订单/配置契约。
2. `BoardingOrderPolicy`、`TransitAccessPolicy`、`BoardingAdmissionPolicy`、`CoTransportBoardingPolicy`：理解排序与准入。
3. `PassengerAccessEgressImpl`、`SimpleTransitStopHandler`、`ComplexTransitStopHandler`：理解真实原子登乘。
4. `CoTransportRuntimeMonitor`、`ExposureIntegrator`、`ExposureValuation`、`WholeOrderBoardingAudit`：理解事实账与评分。
5. `CoTransportPhaseController`、路由提供器和 `run/r17`：理解跨轮行为与可行性。
6. `UnifiedExperimentRunner`、`UnifiedArmContract`、`UnifiedMixedComparison`：理解方案装配与配对。
7. `DynamicMixedControllerModule` → `DynamicMixedQSimModule` → 观测/预测 → `RollingDeparturePolicy` → 原生 dispatch bridge：理解 T。

历史文档如 `docs/ismt_reproducibility/boarding_order_20261001.md` 明确描述的是当时可拆分单位版本；与当前 WHOLE_ORDER 默认冲突时应以当前源码及本说明书的版本边界为准，保留旧文档用于追溯。
