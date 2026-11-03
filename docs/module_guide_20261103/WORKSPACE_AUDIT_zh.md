# 工作区内容与完成状态核验

核验日期：2026-11-03。本工作区父目录本身不是 Git 仓库，实际代码仓库是其下 `matsim-libs-2026.0/`。本文以本次目录检查、Git 差异、源码与实际收据为依据。

## 目录分别承担什么工作

| 工作区路径 | 内容和作用 | 发布处理 |
|---|---|---|
| `matsim-libs-2026.0/` | 官方2026.0源码、原生内核修改、新增客货/动态/实验/可行性模块、测试、历史场景与脚本 | 以白名单提取源码和本次说明/小例 |
| `scenarios/synthetic_base10/` | 十站冻结基准、货运需求库、Base/PP/FCFS/整单/专运实验及审计 | 历史输入输出留本地，文档说明来源 |
| `scenarios/typical_urban_networks/20261013_v1/` | 四类城市网络、两种道路版及两种共同需求，16套配置，静态分析与兼容性修复 | 留本地，不能声称已运行交通仿真 |
| `outputs/`、`output/` | 历史运行事件、计划和派生结果；根 `outputs/` 约103GB | 不作为源码仓库内容 |
| `artifacts/`、`frozen/` | 冻结源码/运行时、构建身份、测试与验收收据；不是当前源码的替代物 | 留本地证据，发布副本从当前源码重建 |
| `.build-cache/` | 本机依赖和构建缓存 | 不纳入发布 |
| `analysis/`、`代码解释/` | 科研后处理、源码讲解、图源、报告及历史审阅 | 本次另写面向当前源码的简明手册，旧资料保留 |
| `CTR_Manuscript_Updated_20260917/`、`ISMT_LaTeX_Template/`、`论文框架研究/` | 论文、文献、图表、投稿与审稿材料 | 不随代码小例打包 |
| `presentations/`、根目录PDF/DOCX/PPTX | 演示、教材、专利或需求文档 | 留在原位置 |
| `CURRENT_CONTEXT.md`、`CURRENT_T_TASK.md`、`CURRENT_ATOMIC_TASK.txt` | 历史任务接续与研究边界 | 按日期参考，不代替实际终态收据 |

本次检查时，根 `scenarios/` 约100GB，代码仓库约16GB，其中 `examples/` 的历史场景与结果占绝大部分。直接上传整个工作区或不加选择地暂存代码仓库，会把大量历史结果与本机资产带入发布。

## 可以确认的完成情况

1. 客货共运已进入原生车辆登乘和评分过程，包含当前整单机制；不只是事后统计。新增模块与12个既有生产Java修改逐项见 [说明书](MODULE_MANUAL_zh.md) 和 [清单](CORE_CHANGE_INVENTORY.md)。
2. C/M/T/D 的代码装配、19类输入/定义引用校验、M/T开发配对和原生动态司机桥接已经落地；正式运行支持范围仍有明确门禁。
3. 十站已有 Base、固定混运不同排序和专列的实际运行与分析证据。不同日期的拆分机制、整单机制、D20与D100供给必须分别保留身份。
4. D100 `revisions/d100_20261004/audit/parallel_acceptance_v1/receipt.json` 实际为 PASS，225作业、45需求、逐作业50轮，`scientific_conclusions_verified=false`。检查时实验根 `registration/current_results.json` 和 D100 `audit/result_promotion_v1/publication.json` 不存在，故未认定采用切换完成。
5. 典型城市工程的 `audit/final_acceptance.json` 为静态交付 PASS，16config/64XML/0仿真；存在初始 `pt interaction` 时长恢复适配器，未来执行与重规划/评分语义还需验证。
6. 本次新增小路网已从当前源码真实构建运行，通过订单/容量/原生事件/评分检查，实际输入和结果随说明交付。

本次没有恢复历史批次、重跑225作业、发布科研结论或改写旧输入输出。新仿真仅为本次独立小例；它与历史实验目录分开保存。

## 本次新整理的交付

- 本目录的模块说明、核心变更清单、测试环境与I/O合同、工作区核验和GitHub准备说明。
- `examples/cotransport-mini/` 的六份输入、Java入口、运行/只读核验脚本和少量真实输出。
- `scripts/prepare_github_release.py` 的白名单快照与完整性核对工具。

本轮整理前已有127个新增生产Java、121个新增测试Java和13个被修改的既有Java。新增演示入口 `RunCoTransportMini.java` 单独计入本次交付，故整理后新增生产Java为128个。文件数不是测试用例数，源码存在也不等于全量验证通过。
