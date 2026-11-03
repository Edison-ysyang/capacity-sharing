# 客货共运最小可运行示例

本例用独立合成数据演示当前 `PassengerFreightCoTransportModule` 的固定混运机制：订单占用原生车辆容量、乘客优先、整单同时登乘、订单完成守恒、乘客同乘与拥挤负分。它从本目录 XML/CSV 读取输入，真实运行 MATSim QSim 的第 0 轮。

2026-11-03 已用**当前仓库源码**构建运行并通过 `verify.py` 的原生事件核验；少量实际结果在 `expected-output/`。未调用旧冻结构建、杭州正式仿真或动态预测器。

## 1. 场景

- 3 个节点、4 条有向路段，每条长 1000 m，局部平面坐标；2 个站点。
- 一条客运线路 `mixed_line` / `mixed_route`，一班列车 `departure_0800`，08:00 发车。
- 列车 `mini_train` 容量 4（2 座+2 站立）。`networkMode=car` 是本例列车在 QSim 路网中的运行模式；人的乘车方式仍为 `pt`。
- 2 名乘客于 07:55 到站；1 个货运订单 `cargo_order` 于 07:30 释放，占 2 个容量单位。
- 共运上车窗口 `[07:30,08:30)`，交付截止 08:30；`PASSENGER_FIRST`、`WHOLE_ORDER`、`STRICT_FIFO`，立即激活。
- 只运行第 0 轮，固定计划；本例不包含多轮学习、动态调度或专用列车。

```text
n0 --origin_link--> n1 --destination_link--> n2
   <--return_0--      <--return_1--
       origin_stop        destination_stop
       乘客2+货物2        全部下车
```

此处货运链刻意简化为“释放→本站等待→一段 PT→最终交付活动”。首末端接驳、站内处理及末端交接时长均未单独建模；本例的完成口径只适用于这条简化链。需要完整物流链时，应显式在各容量单元的同构固定计划中加入这些活动与路段。

## 2. 运行

在 **仓库根目录 `matsim-libs-2026.0`** 执行。所需环境：JDK 25、Python 3.9+ 和 Maven（本地实测 Maven 3.9.16 / OpenJDK 25.0.3）。首次构建需可访问项目 Maven 依赖库。

```bash
python3 examples/cotransport-mini/run.py \
  --output examples/cotransport-mini/runs/my-first-run
```

脚本先以 Maven 编译当前源码及生成运行类路径，再运行一次微型场景并执行核验。输出目录必须不存在，脚本不会覆盖任何既有运行。源码入口：

```text
matsim/src/main/java/org/matsim/run/examples/RunCoTransportMini.java
```

也可使用本库 wrapper：

```bash
python3 examples/cotransport-mini/run.py --maven ./mvnw \
  --output examples/cotransport-mini/runs/my-wrapper-run
```

`--offline --maven-repo /path/to/already-populated-cache` 仅用于已有完整依赖缓存的环境。本次本地验收用了工作区现成缓存；发布后的通常命令不依赖该缓存路径。

执行后 `runs/my-first-run/` 为 MATSim 输出；同级 `my-first-run-build/` 保留构建/运行日志及本地类路径。全部 `runs/` 已忽略，不进入 GitHub。

只校验已有结果、不运行仿真：

```bash
python3 examples/cotransport-mini/verify.py examples/cotransport-mini/expected-output
```

## 3. 输入与输出

完整字段与关联约束见 [INPUT_OUTPUT_CONTRACT_zh.md](INPUT_OUTPUT_CONTRACT_zh.md)。最小需要这 6 份文件：

| 文件 | 用途 |
|---|---|
| `input/config.xml` | MATSim 与 `coTransport` 参数，含输入引用、单轮设置和评分系数 |
| `input/network.xml` | 路段、节点、连接、长度、速度、通行能力 |
| `input/population.xml` | 乘客及货物容量单元的显式计划、PT 路线与货物子群 |
| `input/transitSchedule.xml` | 站点、线路、路径、班次、车辆 ID |
| `input/transitVehicles.xml` | 车辆类型、容量、上下车时间及车辆 ID |
| `input/freight_orders.csv` | 订单、释放/截止、体积、重量、优先级、容量单位 |

本例不需要额外人员属性文件：货运 `subpopulation=freight` 写在 `population.xml` 内。仅有订单 CSV 不会自动生成货物 agent。

本次真实结果：

| 指标 | 结果 |
|---|---:|
| 完成乘客主出行 | 2 |
| 完成/准时订单 | 1 / 1 |
| 交付容量单位 | 2 / 2 |
| 整单登乘批次 | 1（2 单位同车同秒） |
| 乘客/货物拒载 | 0 / 0 |
| 卡死 | 0 |
| 最大占用 | 4 / 4 |
| 订单首登乘时刻 | 08:00:03 |
| 最后单位交付时刻 | 08:01:01 |
| 乘客额外扣分合计 | 1.329（CSV 四舍五入值） |

列车实际运行取决于路网行驶和原生上下车处理；班次时刻不是订单交付时刻。订单只有两个容量单元均到达最终 `freight_delivery` 活动后才记为完成。

`expected-output/` 为本次 `run-002` 的真实输出节选，CSV 与事件原文未改。`mini_run_receipt.json` 记录输入与关键源码 SHA-256、编译来源和一次仿真范围；其中本机绝对路径已泛化，原始运行收据仍保留在本地 `runs/`。`SHA256SUMS.json` 用于核对交付样例完整性。

## 4. 验证边界

`verify.py` 核对订单身份守恒、原生 PT 上下车事件、同秒整单登乘、容量守恒、窗口资格、无拒载/卡死、乘客完成和实际负分事件。它使用本例固定的已知人数/订单数，是本例的验收脚本，不是任意新场景的通用验证器。

这个成功案例说明接线和基本机制可以真实运行。它没有制造容量不足，因此不证明所有拒载路径、严格 FIFO 阻塞、整单无法容纳、FCFS、公平性或动态 T 的正确性；也不代表收敛、收益或铁路安全结论。更改输入后应更新场景专用验收条件，并使用新输出目录。
