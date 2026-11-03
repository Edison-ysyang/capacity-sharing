# 相对 MATSim 2026.0 的代码变更清单

核验日期：2026-11-03。仓库为 `matsim-libs-2026.0/`，工作区父目录本身不是 Git 仓库。基线为本地 HEAD `c7a75ebeddc3ceb62959af046190064bf23770df`，提交说明为 `[maven-release-plugin] prepare release 2026.0`。origin 仍指向 `https://github.com/matsim-org/matsim-libs.git`。

这份清单记录“相对当前官方基线的工作树差异”，不把每个文件未经历史核查就断言为用户原创。新增源码大多未跟踪，`git diff --stat` 不包含它们，发布时只提交 diff 会遗漏实际功能。

## 统计口径

本次文档整理开始时：

| 范围 | 数量 |
| --- | ---: |
| 已跟踪且修改的生产 Java 文件 | 12 |
| 已跟踪且修改的测试 Java 文件 | 1 |
| 未跟踪新增生产 Java 文件 | 127 |
| 未跟踪新增测试 Java 文件 | 121 |
| 删除的既有非 Java 文件 | `Makefile`（1 个） |

以上生产/测试新增数只计 `matsim/src/main/java`、`matsim/src/test/java` 中 `.java`，不计历史 frozen 构建、target 编译物或本次另建演示材料。12+1 个既有 Java 共增加 897 行、删除 73 行；包含删除 Makefile 的 `git diff --stat` 为 14 文件、897 行增加、106 行删除。行数只反映已跟踪差异。

复核方法：`git diff --name-status HEAD` 与 `git ls-files --others --exclude-standard matsim/src/main/java matsim/src/test/java`，后者筛选 `.java` 后按目录计数。下表为整理前快照，后续新增示例代码应另计。

## 已修改的原生生产文件

路径均相对仓库根目录。

| 路径 | 当前实现差异/目的 |
| --- | --- |
| `matsim/src/main/java/org/matsim/core/events/EventsManagerImpl.java` | 增加只读 handler 注册、方法和顺序快照及按真实对象身份比较，供运行接线检查；不靠再注入猜测实例 |
| `matsim/src/main/java/org/matsim/core/events/SimStepParallelEventsManagerImpl.java` | 保留原生事件 worker 线程引用，提供接收线程及注册快照检查 |
| `matsim/src/main/java/org/matsim/core/mobsim/qsim/MobsimListenerManager.java` | 暴露分离的只读监听器注册及调用顺序快照 |
| `matsim/src/main/java/org/matsim/core/mobsim/qsim/QSim.java` | 可选准备前后检查、排空后/结束边界检查与运行角色快照；保留原生 events drain → After listener 顺序 |
| `matsim/src/main/java/org/matsim/core/mobsim/qsim/pt/PassengerAccessEgress.java` | 增加默认批量前缀登乘方法，兼容普通单代理入口 |
| `matsim/src/main/java/org/matsim/core/mobsim/qsim/pt/PassengerAccessEgressImpl.java` | 接入策略与真实就绪队列排序、时窗、原生容量、整单候选事务/原子提交/回滚、拒载快照；拒载按车辆隔离 |
| `matsim/src/main/java/org/matsim/core/mobsim/qsim/pt/SimpleTransitStopHandler.java` | 使用批前缀接口，验证提交数量及候选列表未被适配器意外修改 |
| `matsim/src/main/java/org/matsim/core/mobsim/qsim/pt/ComplexTransitStopHandler.java` | 串/并行门处理接入批提交，保留提交单位数量对应的原生处理时间预算 |
| `matsim/src/main/java/org/matsim/core/mobsim/qsim/pt/TransitStopAgentTracker.java` | 保存真实候车就绪时间和单调登记序号，重复入队幂等；成功上车清时钟，跨拒载保留，日终同步清空 |
| `matsim/src/main/java/org/matsim/core/mobsim/qsim/pt/TransitQSimEngine.java` | 清理改为 `clearWaitingAgents()`，同时清候车人与其就绪元数据 |
| `matsim/src/main/java/org/matsim/core/mobsim/qsim/qnetsimengine/QNetsimEngineWithThreadpool.java` | 增加可选执行屏障跟踪及 runner 构造数量检查，记录移动节点/链路/完成/失败状态 |
| `matsim/src/main/java/org/matsim/core/replanning/StrategyManager.java` | 保存并只读提供构造时实际消费的配置→策略实例映射，支撑阶段权重身份校验 |

已修改测试：`matsim/src/test/java/org/matsim/core/mobsim/qsim/pt/TransitStopAgentTrackerTest.java`，新增候车时钟、顺序和清理等测试。

删除 `Makefile` 的原因不能仅从当前差异确定；发布准备应显式决定保留该删除还是恢复官方文件，不能无解释将它作为模块必需修改。

## 新增 Java 按包统计

| Java 包 | 生产 | 测试 | 代表内容 |
| --- | ---: | ---: | --- |
| `org.matsim.contrib.cotransport` | 28 | 25 | 配置、订单、单位、阶段、登乘、暴露、评分、监控、整单审计、归档 |
| `org.matsim.core.events` | 1 | 2 | `SimStepEventsRuntimeInspection` |
| `org.matsim.core.mobsim.qsim` | 3 | 2 | `QSimPreparationCheck`、`QSimRuntimeInspection`、`QSimStepBoundaryCheck` |
| `org.matsim.core.mobsim.qsim.pt` | 6 | 22 | 控制司机/工厂/桥、登乘策略及拒载明细事件接口；原生接线测试 |
| `org.matsim.core.mobsim.qsim.qnetsimengine` | 1 | 1 | `QNetsimRuntimeInspection` |
| `org.matsim.run.examples` | 14 | 0 | 杭州、基准/混运/专运、R17 示例入口 |
| `org.matsim.run.ismt` | 19 | 15 | 统一执行、spec、配对、输入/构建/运行验证与 artifact writer |
| `org.matsim.run.ismt.dynamic` | 45 | 49 | T 的日内观测/预测/控制/门禁与输出 |
| `org.matsim.run.r17` | 10 | 5 | Plan-A 可行性、末班车、记忆回退、双时域 |
| 合计 | 127 | 121 | 测试“文件数”不等于测试“用例数” |

## 新增客货业务类完整列表

包 `org.matsim.contrib.cotransport`：

- 配置/装配：`CoTransportSettings`、`CoTransportConfigGroup`、`PassengerFreightCoTransportModule`。
- 订单/单位：`FreightOrder`、`FreightOrderRegistry`、`FreightUnitizer`、`ProportionalFreightUnitizer`、`FreightOrderRuntimeStats`、`OrderOutcomeReconstructor`。
- 登乘/服务：`BoardingOrderPolicy`、`BoardingAdmissionPolicy`、`TransitAccessPolicy`、`CoTransportBoardingPolicy`、`DedicatedFreightServiceValidator`、`WholeOrderBoardingAudit`。
- 阶段/路由：`CoTransportPhaseController`、`PassengerOnlyPtRoutingModuleProvider`、`FreightOnlyPtRoutingModuleProvider`。
- 状态/评分：`VehicleCapacityState`、`PassengerRideState`、`PassengerExposureSnapshot`、`ExposureIntegrator`、`ExposureValuation`。
- 监控/分析：`CoTransportRuntimeMonitor`、`PassengerOnlyAnalysisModule`、`PassengerOnlyIterationStats`、`IterationEventFingerprint`、`ScientificEventArchive`。

新增 PT 原生类为：`TransitBoardingPolicy`、`TransitBoardingDeniedDetailEvent`、`TransitBoardingDeniedDetailEventHandler`、`ControlledTransitDispatchBridge`、`ControlledTransitDriverAgent`、`ControlledTransitDriverAgentFactory`。

## 行为与结果语义发生过的变化

当前源码默认 **WHOLE_ORDER + STRICT_FIFO**，并在整单模式下让序列化服务窗口成为物理上车约束。这与早期 2026-10-01 文档的独立单位拆分机制有实质差异；历史 UNIT_SPLIT 输出不得改名为整单输出。当前 T 对 WHOLE_ORDER 明确拒绝，统一登记/动态装配对 FCFS 明确拒绝，防止执行与预测语义不一致。

新增内核检查接口也不能被概括为全是被动无行为差异：登乘路径、队列去重、原子批提交和拒载归属确实修改运行行为。当前没有足够证据声称所有旧场景逐事件等价。发布 README 应明确这是基于 MATSim 2026.0 的修改版。

## 发布边界

当前工作树的新增项还包括场景、脚本、文档和大体积结果。它们与本表 Java 数量不同；不要执行不加选择的 `git add .`。应以可审核的白名单打包源码、必要测试、可移植构建文件、说明书和微型输入输出，同时保留上游许可证和修改声明。原始巨量输出、构建缓存及本机路径绑定实验另留本地/外部数据归档。GitHub 目标仓库也应明确选定，现有 origin 是官方项目地址。
