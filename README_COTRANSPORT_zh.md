# MATSim 客货共运研究扩展

本项目基于 MATSim 2026.0，在原生公交车辆、候车队列、登乘与事件评分路径上实现客货共享容量、整单运输、乘客暴露评分，以及独立的受约束日内发车控制实验框架。它是一份修改后的 MATSim 源码工程，客货模块位于主 `matsim` 模块；官方 MATSim JAR 不包含本项目新增接口。

整理与核验日期：2026-11-03。官方基线为 `c7a75ebeddc3ceb62959af046190064bf23770df`。这是本地研究代码的说明与小例交付；历史冻结实验和本次小例分别保留证据。

## 从这里开始

| 内容 | 入口 |
|---|---|
| 我修改了什么、新模块怎样工作 | [新增模块说明书](docs/module_guide_20261103/MODULE_MANUAL_zh.md) |
| 原生内核修改、Java 文件及版本变化 | [代码变更清单](docs/module_guide_20261103/CORE_CHANGE_INVENTORY.md) |
| 本机测试环境、需要哪些 input、新增哪些 output | [测试环境与输入输出](docs/module_guide_20261103/TEST_ENVIRONMENT_AND_IO_zh.md) |
| 完整小路网输入、真实运行命令及实际结果 | [微型示例](examples/cotransport-mini/README_zh.md) |
| 六份输入的字段、ID关联与逐输出解释 | [示例输入输出合同](examples/cotransport-mini/INPUT_OUTPUT_CONTRACT_zh.md) |
| 当前工作区各目录做了什么 | [工作区核验记录](docs/module_guide_20261103/WORKSPACE_AUDIT_zh.md) |
| 发布包纳入范围与上传准备 | [GitHub 发布说明](docs/module_guide_20261103/GITHUB_PREPARATION_zh.md) |

## 新增能力

- 订单 CSV 与固定货运计划守恒，货物按容量单位占用原生车辆空间。
- 乘客优先或客货 FCFS；当前默认整单原子登乘 `WHOLE_ORDER` 与 `STRICT_FIFO`，保留显式 `UNIT_SPLIT` 历史模式。
- 物理货运上车窗口、客货线路隔离、混运/专运供给、处理停站与订单末端完成统计。
- 原始拥挤/货物同乘暴露、真实负分事件、拒载与跨轮乘客行为反馈。
- C/M/T/D 实验配置、输入/构建哈希、共同历史、配对与运行审计。
- T 的因果观测、预测、滚动候选、约束、承诺与原生司机执行桥接。
- 计划记忆可行性回退、末班车检查、行为时域与物理执行时域分离。

当前 T 的预测与正式合同只支持其已登记的乘客优先、独立容量单位机制，明确拒绝整单与 FCFS。通用固定表模块支持的开关，不能直接视作动态正式实验支持的开关。旧 `TIMETABLE_ONLY` 也不是当前 `DYNAMIC_MIXED`。

## 运行小路网

需要 JDK 25、Maven 与 Python。示例使用 Python 标准库；本次实际使用 Python 3.9.6、OpenJDK 25.0.3 和 Maven 3.9.16。首次构建需要下载 Maven 依赖，已有完整缓存时可离线。

在仓库根目录执行：

```sh
python3 examples/cotransport-mini/run.py --maven ./mvnw \
  --output examples/cotransport-mini/runs/my-first-run
```

脚本重新编译当前源码，只运行第 0 轮，并核对原生上下车事件、整单同秒同车、容量与订单守恒、乘客完成及额外评分。既存输出目录会被拒绝。此例包含 3 节点、4 有向路段、2 站、2 乘客及 1 笔占 2 单位的货运订单；列车容量 4。六份 XML/CSV 输入均位于 `examples/cotransport-mini/input/`。

2026-11-03 的真实小例结果为：乘客完成 2 次主出行、订单 1/1 完成且准时、单位 2/2 交付、整单批次 1、最大占用 4/4、拒载和卡死均为 0。生成了实际负分事件，和新增报告对账通过。少量原始结果已放入 `expected-output/`；本例物流链经过简化，不代替完整物流链或动态 T 验证。

只读取并核验随附结果：

```sh
python3 examples/cotransport-mini/verify.py examples/cotransport-mini/expected-output
```

## 研究证据和复现范围

历史杭州、十站及典型城市输入工程留在原工作区，未混入小例输入。D100 的 225 作业已有独立运行验收 PASS，但收据不宣称全部科学结论通过，采用映射文件尚未发布。2026-10-13 四类城市路网工程完成的是静态输入交付，交通仿真次数为 0。详细证据路径、版本和限制见说明书。

单轮小例证明有界接线与基本机制，不能证明拒载全部分支、动态控制收益、全矩阵稳定或行为收敛。历史测试收据属于各自冻结代码，不能相加当成当前代码覆盖率。

## 上游与许可

保留 [MATSim LICENSE](matsim/LICENSE)、[COPYING](matsim/COPYING) 及原有源码版权声明。发布副本保留官方 Git 基线及其历史；其中 `README_UPSTREAM.md` 保存官方基线 README。本项目的修改范围见变更清单。
