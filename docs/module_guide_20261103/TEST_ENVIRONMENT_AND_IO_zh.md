# 测试环境、输入合同与新增输出说明

核验日期：2026-11-03。本文以当前源码、实际环境及已存在的验收收据为依据。它不把历史冻结构建的测试成绩当成当前工作树重新测试的成绩。本轮本文件作者仅执行版本查询与命令帮助检查，没有启动仿真、重建历史输入或重跑实验。

配套文档：[模块说明书](MODULE_MANUAL_zh.md)、[核心修改清单](CORE_CHANGE_INVENTORY.md)、[可运行微型示例](../../examples/cotransport-mini/README_zh.md)。

## 1. 本机实际环境

| 项目 | 2026-11-03 实测值 | 使用意义 |
|---|---|---|
| 系统 | macOS 27.0.1，aarch64 | 当前是 Apple Silicon；不应在发布入口中写死本机路径 |
| Java | Homebrew OpenJDK 25.0.3 | 根 `pom.xml` 的 `maven.compiler.release=25`，需要 JDK 25 或兼容更新版本 |
| 系统 Maven | 3.9.16 | `mvn --version` 已实际执行 |
| Maven Wrapper | Maven 3.9.16，wrapper 3.3.4 | `sh mvnw --version` 已实际执行；wrapper properties 带发行包 SHA-256 |
| 系统 Python | 3.9.6 | 当前 `ismt_build.py --help`、`ismt_unified.py --help` 已实际通过 |
| 本机 bundled Python | 3.12.14 | 历史实验和绘图常使用此解释器；本机路径在工作区审计产物中可追溯 |
| MATSim 基线 | 2026.0，Git HEAD `c7a75eb` | 当前扩展包含大量未提交新增文件及对原生代码的修改 |

本地依赖缓存存在于工作区 `.build-cache/` 及各冻结构建中。缓存与历史构建不是 GitHub 项目应提交的依赖；跨机器需要按 `pom.xml` 和 wrapper 重新解析依赖。Python 绘图环境与 Java 仿真环境也应分别管理。

### 构建与运行层次

普通开发构建可在**独立的新工作副本**执行：

```sh
sh mvnw -pl matsim -DskipTests package
```

这只表示编译打包，`-DskipTests` 不提供测试通过证据。当前说明审计没有替用户执行该命令。不要直接对历史冻结目录执行 `clean` 或覆盖其 JAR。

现有科学复现工具 `scripts/ismt_build.py` 分为 `snapshot` 与 `build`。`snapshot` 只读当前源码并创建独立快照；`build` 对快照用 wrapper 执行 `-pl matsim -DskipTests clean package` 和固定版本 dependency plugin，复制运行依赖并生成 `ismt-build-v1` 清单。清单绑定源码、基线 commit、JAR、依赖、JVM/Maven 身份与构建命令。默认离线，明确 `--allow-downloads` 才允许下载。目录和输出均要求位于指定工作区内；既存目标会被拒绝。

```sh
python3 scripts/ismt_build.py --help
python3 scripts/ismt_unified.py --help
```

`scripts/ismt_unified.py` 默认只打印命令计划，只有加 `--execute` 才调用 Java。选择 `development` / `formal` 不等于自动获得已验证输入；Java 子入口仍会执行相应门禁。微型演示使用专门示例入口，完整命令和本轮实际运行证据见示例 README。

## 2. 根据入口准备输入

| 入口/用途 | 至少需要 | 额外约束 |
|---|---|---|
| 仅客运原生 MATSim | config、network、population；使用 PT 时另需 transitSchedule、transitVehicles | car 车辆定义、属性、设施等按 config 实际引用提供 |
| 通用客货共运模块/本次微型示例 | 上述 PT 输入 + `coTransport` 参数组 + freight_orders.csv + population 中已生成的全部货运容量代理 | 入口必须安装 `PassengerFreightCoTransportModule`；普通官方入口不会自动识别并安装扩展 |
| 旧正式 C/M/D、旧 TIMETABLE_ONLY | `ExperimentSpec` v1、冻结 build manifest、12 类哈希绑定输入 | 保留原注册身份和机制；旧 TIMETABLE_ONLY 不等于当前动态 T |
| 新窗口 M / 动态 T | `DynamicMixedExperimentSpec` v2、build manifest、12 类输入 + 7 类定义引用 | 科学字段、窗口、运行机制均显式登记；声明格式正确不代表可以正式运行 |
| `UNIFIED_CURRENT` 的 M/T 开发入口 | 上行全部内容 + 对方的 `--paired-spec` | 双方声明和双方 19 类引用实际字节均检查；一次只运行所选一臂 |
| 专用货运 D | 专用线路/时刻表/车辆、订单、固定货运计划、明确专用 lineId | 需要客货线路隔离及专用服务校验；D100 与旧 D20 是不同供给身份 |

`config.xml` 统一声明网络、人员计划、车辆、时刻表、随机种子、线程、仿真时段、迭代、评分和策略等。路径通常相对 config；`CoTransportConfigGroup` 当前只接受本地文件路径。最小演示可以把乘客和货物属性内嵌在 population，而不另建 personAttributes 文件；正式注册的文件角色仍须完整声明，不能据此删掉注册字段。

### 2.1 订单 CSV 的实际读取合同

`FreightOrderRegistry.loadOrders()` 必需表头为：

```csv
order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority
```

`capacity_units` 可选，建议示例和新实验明确给出。源文件可以有站点、OD、路径、末端服务等附加审计列，但当前 registry 不使用这些列自动生成出行计划：**订单表不是计划生成器**。实际 OD、接驳、处理、PT 线路/路线/上下车站以及末端交接必须由 population 的固定计划定义。

| 字段 | 实际含义/校验 |
|---|---|
| order_id | 非空且唯一；同时是主容量代理 person ID |
| release_time / deadline_time | CSV 必须用 `HH:MM:SS`（秒部分可有小数，小时可超过24）；读取后换算午夜起秒。直接填48600不会被registry接受。源码没有保证 deadline 一定晚于 release，数据生成方应额外核对 |
| volume_m3 / mass_kg | 非负有限值；质量目前是订单信息，不是另一套列车承重约束 |
| service_class | 服务类别字符串；不是自动优先队列开关 |
| priority | 整数，用于货运惩罚权重；不是 `boardingOrder` 的替代项 |
| capacity_units | 正整数；存在时优先使用。缺省为 `max(1,ceil(eta×volume_m3))`，eta 为 `freightEquivalentPassengersPerM3` |

代理 ID 默认依次为 `order_id`、`order_id_cap_02`、`order_id_cap_03`……；`_cap_` 可通过 `freightShadowToken` 登记替换，序号按从1开始的容量单位位置格式化，主单位保留订单ID。需要恰好生成所有代理，禁止未登记货物或遗漏代理。即使订单 CSV 能成功读取，若 population 尚无这些人，也不能开始有效共运。

### 2.2 货运 population 合同

`validateFixedPlans()` 检查：每个容量代理必须有 **String 类型** `subpopulation="freight"`，恰好一个预生成的非空 selected plan；代理总数与 registry 完全一致。供需 ID 守恒与计划完整性是两项检查。

当前默认 `WHOLE_ORDER` 还检查：

1. 首活动明确结束于订单 release_time。
2. 同一订单所有单位的完整活动/接驳/处理/PT/末端服务链一致；评分和任意审计属性不计入服务链签名。
3. 每个 leg 有预生成 route，至少一个明确的 PT leg，并具备 line/route/accessStop/egressStop。
4. 最后是 `freightActivityPrefix` 开头的交付活动。
5. **拒绝 chained PT continuation**：原生连续乘车搬移按个体执行，当前整单机制还不能证明该路径原子化。

容量代理仍占用原生车辆席位，整单模式把它们作为同一登乘机会的不可拆分组提交。真正送达要看全部单位完成末端服务，不等于主代理下车或订单中任一单位上车。MATSim 2026 的 transit route 应采用当前 `default_pt` JSON 格式，避免把旧 PT1 文本当成可移植示例。

## 3. `coTransport` 参数组：当前源码默认值

以下默认值来自 `CoTransportConfigGroup`，是**程序默认值而非已校准推荐参数**。Java 直接构造 settings 与 XML 入口有部分不同，特别是 `manageInnovationSchedule`，应始终在新示例中明确设置关键参数。

| 参数 | 当前 XML 默认值 | 作用 |
|---|---|---|
| freightOrdersFile | 未设置，安装模块时必填 | 执行订单 CSV |
| baselineTransitScheduleFile | 未设置 | 执行非激活轮时需要，用于恢复基线 |
| activatedTransitScheduleFile / activatedTransitVehiclesFile | 未设置 | 成对配置；激活阶段供给 |
| armMode | ALWAYS_ACTIVE | 另有 ITERATION_WINDOW、DISABLED |
| boardingOrder | PASSENGER_FIRST | 另有 FIRST_COME_FIRST_SERVED；按真实队列就绪时刻及注册序号 |
| freightBoardingMode | **WHOLE_ORDER** | 整单不可拆分；旧独立容量单元需显式 UNIT_SPLIT |
| wholeOrderQueuePolicy | **STRICT_FIFO** | 整单因容量/货物份额受阻时阻止后续同序列候选绕过；FIT_FIRST 显式允许适配空位者绕过 |
| compressReports | false | runtime monitor CSV 无损改用 `.csv.gz`；不改变仿真、事件采样或其它模块 |
| activationIteration / deactivationIteration | 0 / -1 | -1 表示无停用轮；窗口激活起点含、停用起点不含 |
| innovationOffIteration / innovationReopenIteration / innovationFinalOffIteration | 150 / 180 / 300 | 三阶段策略权重时点，不意味着本机历史实验都用这些值 |
| manageInnovationSchedule | true | XML 入口默认管理创新日历；旧直接 settings 构造默认 false |
| freightEquivalentPassengersPerM3 | 4.0 | 未显式指定 capacity_units 时体积折算 |
| comfortThreshold | 0.75 | 物理拥挤暴露阈值 |
| hardCapacityRatio | 0.95 | 兼容保留值；**当前不是运行时容量门禁** |
| passengerLoadGate | 1.0 | 乘客负载大于阈值时禁止新货物登乘，边界是 `>` |
| freightShareCap | 1.0 | 货物最多占原生容量的比例；单位上限 `floor(C×cap)` |
| freightHandlingDwellSeconds | 0.0 | 该站发生货运上下车时的额外停站秒数 |
| freightLatePenaltyPerMinute | 0.35 | 货运迟到惩罚系数 |
| freightDeniedBoardingPenalty | 8.0 | 货运拒载事件惩罚系数 |
| freightIncompletePenaltyPerUnit | 25.0 | 未完成单位惩罚系数 |
| freightServiceWindowStartTime / freightServiceWindowEndTime | NaN / NaN | 必须同时指定；当前整单模式同时形成物理上车半开窗 |
| passengerDeniedBoardingPenalty | 4.0 | 乘客拒载事件附加扣分 |
| passengerPhysicalCrowdingPenaltyPerMinute | 0.18 | 物理拥挤暴露估值 |
| passengerFreightCoPresencePenaltyPerMinute | 0.18 | 货物同乘暴露估值 |
| freightActivityPrefix / freightShadowToken | freight_ / _cap_ | 完成活动识别及容量代理 ID 约定 |
| dedicatedFreightLineId | 未设置 | 有值时启用专线隔离与相应供给检查 |

物理上车窗以 `start <= now < end` 判断，窗后禁止新增/重新上车，但已在车货物允许继续到站。当前 `WHOLE_ORDER` 中，序列化服务窗对混运和专列都形成此门禁；`UNIT_SPLIT` 保留旧版仅由显式 scenario element 安装物理窗的行为。不能仅根据旧配置里有 serviceWindow 字样推断历史运行存在同样物理门禁。窗口内完成统计与最终完成统计也必须分别读取。

客运优先只改变本站合格候选顺序，不清退车上货物、不保证下游乘客零拒载。FCFS 的先到是完成释放/前序处理/接驳后进入原生候车队列的时刻，不能用 release_time 排序代替。原生 `vehicle.addPassenger()` 和车辆 seats+standing capacity 仍是物理容量权威。

旧正式四方案登记与现有动态预测器仍限制为其登记机制；当前动态 T 明确拒绝 WHOLE_ORDER，因为容量预测按独立单位投影；统一合同和动态装配也要求 PASSENGER_FIRST。T 需要显式使用其支持的 UNIT_SPLIT，不能任意打开 FCFS 或 WHOLE_ORDER 就宣称预测与执行已一致。通用开发模块的新参数能力与正式注册支持范围是不同层次，必须遵守具体入口校验。

## 4. 动态 v2 的 19 类引用、配对与正式门禁

`DynamicMixedExperimentSpec.schemaVersion="ismt-dynamic-experiment-v2"`。每个文件引用包含 `path` 和 SHA-256。12 个 input 角色为：

```text
matsimConfig, network, population, passengerPlanMemory, personAttributes,
baselineSchedule, baselineTransitVehicles, activatedSchedule,
activatedTransitVehicles, roadVehicles, orderPool, executionOrders
```

外加 7 类定义：

```text
runtime.resolvedConfiguration
control.policyDefinition
control.constraintDefinition
control.forecastDefinition
control.passengerProtectionDefinition
control.informationDefinition
control.baselineTreatmentDefinition
```

12+7 是文件**角色**，不要求19个不同物理文件；路径别名也不能掩盖不同字节。新 schema 明确登记种子、需求、准入/评分、阶段、供给、决策间隔、预测窗、候选步长、锁定/位移限制、乘客保护与信息假设。当前 runtime declaration 明确要求原生 sim-step parallel、一个事件 worker、同步仿真步、两步观测滞后，以及 `start < behaviouralHorizon < executionHorizon` 和 `onlyUseEndtime`。

`UNIFIED_CURRENT` 开发 M/T 要求一份 MIXED、一份 DYNAMIC_MIXED。除 `design.arm`、`design.controlEnabled`，以及 `{path,sha256}` 中同 SHA 路径别名外，科学字段都必须一致；数组顺序仍有意义。纯 comparator 不读取字节、不证明运行；`runPairedMemberDevelopment` 才逐项核对双方19类文件实际字节，随后只运行选定的一臂。

`UNIFIED_CURRENT` 和 `WINDOWED_MIXED` 当前仍拒绝 formal。保留的正式路径另需历史兼容性/配置/构建/共同历史和结果门禁；配置检查通过不等于获准复用旧结果。开发配对尚不是持久化的双登记运行证书。正式比较必须保存双方原 spec、实际 build manifest、launcher 日志和运行收据，不能只保留差值图。

## 5. 输出：哪些是原生，哪些是新增

具体目录名会受 `runId`、压缩与 output hierarchy 影响；例如迭代文件通常是 `ITERS/it.0/0.<name>`。下表列出逻辑文件名，不把所有可选模块的文件都声称为每次必产。

| 层次 | 典型文件 | 内容与使用条件 |
|---|---|---|
| MATSim 原生 | output_config.xml、output_network.xml.gz、output_plans.xml.gz、output_persons/output_trips、scorestats、modestats、ITERS 下 events | 有效配置、场景/计划、行程与原生统计；压缩格式和写出间隔按 config |
| 本项目新增：共运运行监测 | coTransport_summary.csv、coTransport_freight_orders.csv | 每轮总账、逐订单完成/单位守恒/惩罚 |
| 本项目新增：登乘/拒载/载荷 | coTransport_whole_order_boardings.csv、coTransport_denied_boarding_events.csv、coTransport_denied_responses.csv、coTransport_load_observations.csv、coTransport_stuck_events.csv | 整单提交证据、拒载事件与响应、车辆快照、停滞；整单表仅 WHOLE_ORDER 有 |
| 本项目新增：阶段与指纹 | r17_activation_audit.csv、r17_event_fingerprint.csv | 激活阶段、创新日历与事件指纹；用于一致性检查，不等于完整原始事件 |
| 本项目新增：仅乘客统计 | passenger_modestats.csv、passenger_completed_modestats.csv、passenger_scorestats.csv、passenger_service_stats.csv | 排除货运代理的计划/完成方式份额、得分、门到门/等待/车内时间；使用分号分隔 |
| 本项目新增：行为反馈统计 | passenger_departure_bins.csv、passenger_adaptation_stats.csv、passenger_mode_transitions.csv | 分时段出行、相对参考轮方式转换；参考群体与可用覆盖须查看字段 |
| 本项目新增：ISMT标准科学产物 | iteration_summary.csv、order_ledger.csv、passenger_exposure.csv.gz、denial_ledger.csv、artifact_schema.json | 只有安装 `IsmtControllerModule` / dynamic artifact adapter 等相应 writer 才有；通用共运 module 不自动生成这些 |
| 本项目新增：完整计划/客运事件指纹 | passenger_plan_fingerprint.csv、passenger_event_fingerprint.csv | 全记忆计划及归一化客运事件 SHA；不是导出的完整计划内容 |
| 本项目新增：按需科学事件归档 | scientific_event_archive_counts.csv、每轮 `<it>.events.xml.gz` 及 `<it>.scientific_event_archive.json` | 只有显式安装 ScientificEventArchive 才有；终轮全事件、报告窗保留非 LinkEnter/LinkLeave，早期主要保留计数，具体看 profile/receipt |
| 本项目新增：动态控制证据 | dispatch/day-*.jsonl、audit/dispatch/day-*.json | 观测截止、候选、约束、承诺/回退及逐日审核；T 入口接线后产生 |

不要只按 `hd_cargo_` 等历史前缀筛选“乘客”。当前权威分类是 registry 中的货物身份和 subpopulation，驾驶员也需要单独排除。拒载事件数、去重乘客数、车站/班次机会数是不同指标。原生可能对同机会发出多条事件，必须保留原事件口径。

### 5.1 共运 CSV 关键字段及含义

`coTransport_summary.csv` 包括订单总量/完成量/准时量/未完成单位、window与最终完成率、客货登乘/拒载/stuck、客运拥挤与同乘扣分、货运迟到/拒载/未完成扣分、单位完成率、载荷比/货物份额的均值P95最大值、原始暴露、未闭合乘坐、事件状态有效性。`hard_capacity_ratio_active=false` 说明兼容参数没有作为另一套容量阈值运行。

`coTransport_freight_orders.csv` 的主要列为：

```text
order_id,capacity_units,units_boarded,units_delivered,window_units_delivered,
uncompleted_units,window_uncompleted_units,denied_boardings,release_time,
deadline_time,service_window_start,service_window_end,first_boarding_time,
last_delivery_time,late_minutes,delay_penalty,incomplete_penalty,
window_incomplete_penalty,completion_state,window_completion_state,
wait_to_first_board_min,boarding_events,delivery_events,boarded_agent_ids,
delivered_agent_ids,agent_balance_state
```

`units_boarded/units_delivered` 是单位身份数，`boarding_events/delivery_events` 是事件次数；换乘可能导致次数超过单位数。末端完成、最终完成、窗口内完成要分开。传统 monitor 文本时间采用 `HH:MM:SS`，部分小数四舍五入到3/6位；精确科学账本应优先采用标准 writer 或原生事件。

```text
coTransport_whole_order_boardings.csv:
iteration,order_id,time_seconds,vehicle_id,capacity_units,unit_ids

coTransport_load_observations.csv:
time,vehicle,line,route,stop,total_occupancy,passenger_occupancy,
freight_occupancy,capacity,load_ratio,freight_share

coTransport_denied_boarding_events.csv:
person_id,agent_type,order_id,denial_sequence,denied_time,vehicle_id,
line_id,route_id,stop_id

coTransport_stuck_events.csv:
iteration,person_id,agent_type,order_id,time_seconds,time,link_id,leg_mode,reason
```

载荷表是上下车事件驱动的观察，不是自动得到的逐区间时间加权载荷。统计区间平均、列车离站载荷或乘客暴露时必须选定正确时钟与分母。

### 5.2 标准科学账本的关键列

四张账本共有 `schema_version,experiment_id,block_id,arm,iteration`，字段和类型还会写入 `artifact_schema.json`。`*_seconds` 为午夜起秒，`*_minutes` 为分钟；惩罚列用正成本表示，实际计分事件是负增量。空值表示不可用，不是零。

| 文件 | 关键字段与粒度 |
|---|---|
| iteration_summary.csv | 一轮一行；passenger_count/scored_passenger_count、selected_passenger_trips/pt_share、mean_pt_door_to_door_minutes/mean_pt_wait_minutes、raw/applied暴露扣分、订单与单位服务/拒载、unfinished、phase与激活次数 |
| order_ledger.csv | 每轮每个登记订单一行；number_of_units、units_boarded/delivered、首末上车时刻、completion_time_seconds、deadline_seconds、completed/on_time、窗内/执行期完成、lateness_minutes、denial_reasons、boarding/delivery events |
| passenger_exposure.csv.gz | 每人每覆盖轮一行；raw_physical_exposure、raw_freight_copresence_exposure、physical/co_presence_penalty、applied_*、total_added_penalty、unfinished_ride |
| denial_ledger.csv | 每条详细拒载事件一行；sequence/time/actor/order/vehicle/line/route/departure/stop/reason、passenger/freight occupancy、native_capacity、share_cap/load_gate |

原始物理暴露为 `∫max(0,(P+F)/C−comfortThreshold)dt`，货物同乘暴露为 `∫F/C dt`，dt 用分钟。估值为 beta×raw；实际已应用扣分必须看 `applied_*`。beta=0 不取消物理占位或 raw 记录。未闭合乘坐可能有已积累 raw 但尚未完成下车评分，不能强行让 valued 与 applied 相等。

`first_accessible_departure_time_seconds`、`schedule_wait_minutes`、`additional_admission_delay_minutes` 当前可能空缺，`accessible_departure_status=unavailable_no_reliable_route_schedule_reconstruction` 是 writer 明确保留的限制，不能把空字段补成0后宣称无额外等待。

暴露输出覆盖通常是终轮，或显式选择 REPORTING_WINDOW 的报告轮；不能将终轮明细外推成全轮逐人明细。标准 writer 对重复、缺轮、顺序错误、陈旧快照和拒载账不一致会失败；这些守恒检查不能替代独立全事件重建或因果结论验证。

## 6. 本机已有场景与已完成证据

工作区根为本仓库父目录。下列目录留在工作区，可作为历史证据；发布小型仓库不需要复制数百GB历史 raw。

| 工程 | 当前实际范围 | 证据/限制 |
|---|---|---|
| scenarios/synthetic_base10/20260929_v2 | 冻结十站单线、13.5km、容量100、12车/200班、7449人/14898主出行 | 是合成需求基准；静态62%/85%等是计划压力目标，方式选择后不保证实际载荷 |
| experiments/base50_passenger_20260930 | 0–49轮，car可用性代理、计划记忆3、40后关闭创新 | car不是网约车派单；40–49是描述窗，不是已证明均衡 |
| experiments/1001-boarding-order-comparison 与 1001-boarding-high-q1920 | Base/PP/FCFS各50轮；当时 UNIT_SPLIT 历史构建 | 已有事件与汇总核验；全50覆盖不意味着全50每个人都完成，不能标成当前整单实验 |
| experiments/1002-atomic-base-mix-dedicated | 整单/严格FIFO，新订单/种子矩阵、专列修订 | 应按实际登记与具体 receipt 判断各臂/各格完成范围，T为NOOP，不视为动态T矩阵 |
| 其 revisions/d100_20261004 | 45需求×5种子的225个D100作业已存在独立验收 PASS，逐作业50轮 | `audit/parallel_acceptance_v1/receipt.json` 实际结束于2026-10-04 23:12上海时间，scientific_conclusions_verified=false；顶层 `registration/current_results.json` 本次检查不存在，不能称D100新采用映射已发布 |
| scenarios/typical_urban_networks/20261013_v1 | corridor/radial/grid/ring_radial各25站，shared_grid/urban_context两道路版×monocentric/polycentric需求，共16config/64XML | final_acceptance.json静态PASS，simulation_launches=0；不是已跑通交通、货运、评分或均衡 |

四类城市工程的 `使用入口与目录说明.txt` 和 `INPUT_CONTRACT.txt` 是最新入口。每profile为10000人/20000主出行，重复profile不是独立抽样。**裸原生 PopulationReader 会丢失其初始非零 pt interaction 时长**：目录内有 `code/InputStageDurationRestorer.java` 显式恢复适配器和只读验证；未来仿真必须接入，并继续验证重规划/评分语义，不能直接复制 config 就宣称保留30/120秒缓冲。car route 还经过完整首末link文本编码修复，修复轨迹保留。

### 测试证据的使用边界

- 历史 `artifacts/boarding-order-20261001/verification/verification.json` 实际记录91项白名单纯单元测试，0失败/错误/跳过，0仿真/预测调用。它属于当时快照，早于当前整单改动。
- 9月统一模块和动态模块有各自冻结构建/测试/原生小场景证据，不能把不同日期/源码的测试数累加成当前工作树覆盖率。
- 当前默认整单与旧可拆分不同；移机复现必须选定代码、参数、输入与构建身份，并核验对应测试收据。
- 本轮新微型示例的实际运行、文件哈希与断言由示例自己的输出和验收记录说明；单轮示例证明模块接线和有界机制，不能证明完整动态矩阵、稳定性、收敛或策略收益。

## 7. 换输入前后的最少核对

更换路网时同步核对 link/node、站点 link、transit route link序列、班次车辆、PT路由 line/route/stop、人员活动坐标/link 与完整首末端计划；只替换 network.xml 不足以形成可执行输入。更换订单时同步重建容量代理及同单固定计划；只改 CSV 数量会触发守恒失败。

运行后至少对账：订单数与单位数、整单同车同秒登乘、末端完成、单位ID去重、客货物理容量、窗内新增上车、窗后在车合法到站、拒载事件与扣分、raw/valued/applied暴露、未完成出行及实际文件覆盖。输入/输出示例应附运行身份与验收结果。不同场景和机制使用独立新输出目录，保留原始失败和历史结果。

## 8. 源码核对入口

- [CoTransportConfigGroup.java](../../matsim/src/main/java/org/matsim/contrib/cotransport/CoTransportConfigGroup.java)：参数名/默认值/序列化/路径。
- [CoTransportSettings.java](../../matsim/src/main/java/org/matsim/contrib/cotransport/CoTransportSettings.java)：不可变运行设置、枚举和builder。
- [FreightOrderRegistry.java](../../matsim/src/main/java/org/matsim/contrib/cotransport/FreightOrderRegistry.java)：订单CSV、代理ID、固定/整单计划校验。
- [CoTransportBoardingPolicy.java](../../matsim/src/main/java/org/matsim/contrib/cotransport/CoTransportBoardingPolicy.java)：物理窗、队列、整单和准入。
- [CoTransportRuntimeMonitor.java](../../matsim/src/main/java/org/matsim/contrib/cotransport/CoTransportRuntimeMonitor.java)：传统CSV实际表头与统计。
- [PassengerOnlyIterationStats.java](../../matsim/src/main/java/org/matsim/contrib/cotransport/PassengerOnlyIterationStats.java)：分号分隔乘客统计。
- [IsmtRunArtifactWriter.java](../../matsim/src/main/java/org/matsim/run/ismt/IsmtRunArtifactWriter.java)：版本化科学账本字段/类型/单位/空值与写出覆盖。
- [DynamicMixedExperimentSpec.java](../../matsim/src/main/java/org/matsim/run/ismt/dynamic/DynamicMixedExperimentSpec.java)、[ExperimentSpec.java](../../matsim/src/main/java/org/matsim/run/ismt/ExperimentSpec.java)：12+7引用合同。
- [UnifiedExperimentRunner.java](../../matsim/src/main/java/org/matsim/run/ismt/UnifiedExperimentRunner.java)：profile、M/T双声明与formal门禁。
- [ScientificEventArchive.java](../../matsim/src/main/java/org/matsim/contrib/cotransport/ScientificEventArchive.java)：事件归档profile及实际覆盖收据。
