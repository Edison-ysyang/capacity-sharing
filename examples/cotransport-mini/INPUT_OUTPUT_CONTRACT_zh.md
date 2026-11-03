# 最小示例输入输出契约

适用范围：本目录的固定混运 M / 单轮教学示例。正式 C/M/T/D 的登记文件、运行门禁和动态约束不能由本例配置替代。

## 1. 文件关联

`config.xml` 内的输入路径相对于该配置文件所在目录解析。入口只额外指定新输出目录和拒绝覆盖策略，然后加载场景并安装 `PassengerFreightCoTransportModule`。它不会凭空生成订单计划或班次。

| 输入 | 必需内容 | 必须一致的 ID |
|---|---|---|
| `network.xml` | 节点、路段起终点、length/freespeed/capacity/permlanes/允许方式 | 计划 activity/route 及站点 link 必须存在 |
| `population.xml` | 每人 selected plan，起止活动、结束时间、pt leg、显式 `default_pt` route | `accessFacilityId/egressFacilityId/transitLineId/transitRouteId` 对应时刻表 |
| `transitSchedule.xml` | stopFacility、linkRefId、线路、路线、routeProfile、network route、departures | 路径 link 对应网络，departure vehicleRefId 对应车辆 |
| `transitVehicles.xml` | vehicleType、capacity、networkMode、vehicle | `mini_train` 被 `departure_0800` 引用 |
| `freight_orders.csv` | 下表 8 列 | `order_id` 对应主容量 agent；其余按 `_cap_XX` 生成 ID |
| `config.xml` | 输入文件引用、transit/qsim/controller/scoring/replanning/coTransport | `freightOrdersFile`、`baselineTransitScheduleFile` 引用同目录文件 |

货物 2 单位是 `cargo_order` 和 `cargo_order_cap_02`，均须出现在 population，均有 String 属性 `subpopulation=freight`。当前 `WHOLE_ORDER` 模式还要求：同订单各单位的释放时间、活动、准备/接驳、PT 线路路线与站点、最终交付链一致；所有 leg 有预建 route；第一活动结束时间等于 CSV 的 `release_time`；最终活动以配置的 `freight_` 前缀开头。不支持带 chained PT continuation 的整单计划。

## 2. 订单 CSV

```csv
order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units
cargo_order,07:30:00,08:30:00,0.5,5.0,standard,1,2
```

| 字段 | 含义及本例取值 |
|---|---|
| `order_id` | 唯一订单 ID，也为主容量单元 ID |
| `release_time` | `HH:MM:SS`，本例 07:30；不是保证已完成首端处理的含义 |
| `deadline_time` | 交付截止 08:30；不等于物理上车窗终点的普遍定义 |
| `volume_m3` | 0.5 m³ |
| `mass_kg` | 5 kg；本例仍按容量当量占位，未实现独立重量约束 |
| `service_class` | standard，订单标签 |
| `priority` | 1，影响迟到估值；不会自动改成“按优先级上车” |
| `capacity_units` | 显式整数 2；缺省时按 `max(1,ceil(volume × freightEquivalentPassengersPerM3))` 计算 |

本例 `freightEquivalentPassengersPerM3=4`，所以体积与显式容量一致。订单释放、服务上车窗口、截止时刻是三个不同概念。

## 3. 本例关键配置

| `coTransport` 参数 | 本例 | 意义 |
|---|---|---|
| `armMode` | `ALWAYS_ACTIVE` | 第 0 轮就执行货运 |
| `boardingOrder` | `PASSENGER_FIRST` | 本站等候乘客优先 |
| `freightBoardingMode` | `WHOLE_ORDER` | 一次提交订单全部容量单位，容量不足不拆分 |
| `wholeOrderQueuePolicy` | `STRICT_FIFO` | 整单不能接纳时阻止后续候选绕过；本例没有触发阻塞 |
| `freightServiceWindowStartTime/EndTime` | 27000 / 30600 s | 原生时钟上的 `[07:30,08:30)` 货物新登乘窗 |
| `freightShareCap` | 1.0 | 货物占车辆容量比例上限 |
| `passengerLoadGate` | 1.0 | 乘客载荷门槛 |
| `freightHandlingDwellSeconds` | 0 | 本例不额外加货物处理停站 |
| `comfortThreshold` | 0.6 | 总载荷超过车辆容量 60% 的部分进入物理暴露 |
| `passengerPhysicalCrowdingPenaltyPerMinute` | 0.8 | 原始物理暴露的估值系数 |
| `passengerFreightCoPresencePenaltyPerMinute` | 0.8 | 货物占容量比例随时间积分的估值系数 |
| `passengerDeniedBoardingPenalty` | 4 | 原生乘客拒载事件扣分，本例未触发 |
| `manageInnovationSchedule` | false | 本例不使用分阶段创新日历 |
| `compressReports` | false | 输出普通 CSV，便于直接阅读 |

`hardCapacityRatio=0.95` 为历史兼容字段，本例汇总明确 `hard_capacity_ratio_active=false`；物理车辆容量仍是 4，不应把该字段误读成只准装载 3.8 个单位。

评分中的原始物理暴露为 `∫max(0,(P+F)/C−comfortThreshold) dt_minutes`；同乘暴露为 `∫F/C dt_minutes`，均按乘客实际在车时间分段积分。分别乘系数，通过 `PersonScoreEvent` 写入负分。原生出行时间评分继续存在；不要再次把额外扣分当旅行分钟相加。本例单轮固定计划只展示评分接线，不展示后续行为改变。

## 4. 原生 MATSim 输出

完整运行目录还包含以下原生文件；发布节选只保留 `output_events.xml`：

| 文件 | 内容 |
|---|---|
| `output_events.xml` / `ITERS/it.0/0.events.xml` | 事件时间、type、person、vehicle、link、站点等。当前版本 PT 登乘类型为 `PersonEntersPtVehicle`，下车为 `PersonLeavesPtVehicle`；司机另有车辆事件 |
| `output_config.xml` | 有效配置，包括序列化的 `coTransport` 参数 |
| `output_plans.xml` / `0.plans.xml` | 本轮计划及评分 |
| `output_network.xml`、`output_transitSchedule.xml`、`output_transitVehicles.xml` | 本轮场景副本 |
| `output_trips.csv`、`output_legs.csv`、`output_activities.csv` | 原生出行、路段和活动；包含货物，研究乘客指标时要分群 |
| `scorestats.csv`、`modestats.csv`、日志 | 原生总体统计与运行诊断 |

附加分仍使用原生事件容器，`type=personScore`，`kind=coTransportPassengerPhysicalCrowding` / `coTransportPassengerFreightCoPresence` 表示新增模块生成的暴露负分。仅查 `PersonEntersVehicle` 会漏掉本版本的 PT 乘客事件。

## 5. 新增共运与乘客报告

以下均由扩展模块产生。每轮文件在 `ITERS/it.N/N.*`；`compressReports=true` 时共运监控报告后缀为 `.csv.gz`，行和字段保持完整。

| 文件 | 核心字段/用途 |
|---|---|
| `0.coTransport_summary.csv` | `freight_orders/completed_orders/on_time_orders`，单位完成数，拒载/卡死，额外扣分，载荷比，原始暴露，完整性标志 |
| `0.coTransport_freight_orders.csv` | 每订单 `capacity_units/units_boarded/units_delivered`、首登乘和最后交付、完成状态、等待/迟到、上车/交付 agent ID 与 `agent_balance_state` |
| `0.coTransport_whole_order_boardings.csv` | `iteration,order_id,time_seconds,vehicle_id,capacity_units,unit_ids`；每成功整单批次一行，仅 WHOLE_ORDER 模式有此账本 |
| `0.coTransport_load_observations.csv` | 车站离站快照：`time,vehicle,line,route,stop,total_occupancy,passenger_occupancy,freight_occupancy,capacity,load_ratio,freight_share` |
| `0.coTransport_denied_boarding_events.csv` | 原生拒载事件，含货/客身份、时间、车辆、站点和原因；本例只有表头 |
| `0.coTransport_denied_responses.csv` | 被拒者后续同轮是否登乘/交付、首次拒载后的等待；本例只有表头 |
| `0.coTransport_stuck_events.csv` | 卡死个体定位；本例只有表头 |
| `passenger_completed_modestats.csv` | 过滤货运及司机的实际完成乘客人数/出行数和方式份额；分号分隔 |
| `passenger_service_stats.csv` | 完成乘客门到门、候车、在车时长均值及分位数；分号分隔 |
| `passenger_scorestats.csv` | 乘客计划评分统计；分号分隔 |
| `r17_activation_audit.csv` | 本轮货运/供给激活状态、安装 agent 数及事件数 |
| `r17_event_fingerprint.csv` | 本轮规范事件指纹及计数，用于同条件身份核验 |

原生事件中逐个货运单位先后下车，因此最后交付时刻取最后单位进入最终活动的时间。批次登乘两单位在同一秒并不要求所有后续下车事件也在同一秒。

`coTransport_load_observations` 是特定站点快照，不能单靠此表穷尽车内每个瞬间的负载；事件流才可用于分段重建。本例同时核对两者，并检查所有观察点 `P+F=total≤capacity`。

## 6. 实际样例内容

实际订单行（本次验收的 CSV 原文）：

```csv
cargo_order,2,2,2,2,0,0,0,07:30:00,08:30:00,07:30:00,08:30:00,08:00:03,08:01:01,0.000,0.000,0.000,0.000,completed,completed_by_service_window,30.050,2,2,cargo_order|cargo_order_cap_02,cargo_order|cargo_order_cap_02,completed_conserved
```

载荷快照：08:00:06 从起站离开时，乘客 2、货物 2、总量 4、容量 4；08:01:03 从终站离开时全部为 0。准确列名及全部数值在 `expected-output/` 原始 CSV 中。

这里完成率 100% 来自独立合成微型例，不应替代杭州、十站基准或其他实验的结果。保留旧实验 `UNIT_SPLIT` 的机制身份；当前 `WHOLE_ORDER` 默认不能据此反向改写旧实验结论。
