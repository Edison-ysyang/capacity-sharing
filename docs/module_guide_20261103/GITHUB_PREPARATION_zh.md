# GitHub 发布准备

2026-11-03。先在本地创建独立、可审阅的发布副本；目标 GitHub 仓库及可见性确定后再提交和推送。本说明不表示已经上传。

## 发布副本的范围

发布工具 `scripts/prepare_github_release.py` 纳入：

- 官方2026.0基线中当前仍存在的受跟踪文件，保留原工程、许可证、资源、测试夹具和Git提交祖先。
- 当前 `matsim/src/main/` 和 `matsim/src/test/` 全部源码及资源，包含尚未受Git跟踪的新功能与测试。
- Maven wrapper及校验配置、`ismt_build.py`、`ismt_unified.py`。
- 本次说明书、小规模示例及实际输出节选、发布准备工具。

本机缓存、编译产物、大规模历史scenario/raw、论文、专利、演示和未选择的旧脚本不进入发布副本。历史真实实验仍可在工作区查证，不宣称随小仓库完整复现。官方原有9个workflow从副本工作树移除，其历史仍保留，以免新仓库沿用官方发布流程。本次没有新增自动部署。

副本首页为中文扩展README，官方基线README另存为 `README_UPSTREAM.md`。本地既有Makefile删除保留在副本差异中，手册将其列为原因未明的既有删除；未将其描述为模块必需修改。

## 本地准备和复核

在原源码仓库根目录运行，目标路径必须是尚不存在的独立目录。下列命令里的父目录布局只是示例：

```sh
python3 -B scripts/prepare_github_release.py \
  --destination ../github-release/matsim-cotransport-20261103

python3 -B scripts/prepare_github_release.py \
  --destination ../github-release/matsim-cotransport-20261103 --write

python3 -B scripts/prepare_github_release.py \
  --destination ../github-release/matsim-cotransport-20261103 --verify
```

第一条仅预览。`--write` 从本地官方基线克隆独立Git仓库，复制白名单文件，生成逐文件SHA-256清单并暂存差异；拒绝已有目标。原工作树、原远程地址和历史数据不变。副本分支为 `release/cotransport-20261103`，仅保留 `upstream` 抓取来源，且其push地址设为 `DISABLED`；没有 `origin`，没有自动commit或push。

`RELEASE_MANIFEST.json` 记录文件哈希、源文件身份、官方基线与生成转换。`--verify` 检查交付文件缺失/增加/变化、常见凭据模式及远程边界；它不是编译、全量测试或科研结果认证。小例另有运行收据、输出文件SHA和原生事件断言。

在副本中可查看待提交差异：

```sh
git status --short
git diff --cached --stat
python3 examples/cotransport-mini/verify.py examples/cotransport-mini/expected-output
```

## 上传时需要明确的内容

1. 目标为已有仓库地址，或新仓库的名称与账号/组织。
2. 新仓库的公开/私有可见性。
3. 若本机未配置提交身份，上传时使用用户确认的身份或与已登录GitHub账号对应的noreply身份；本次准备不擅自写全局Git配置。

本机GitHub CLI已登录账号 `Edison-ysyang`，原源码origin仍是MATSim官方地址。发布副本应在确定目标后单独添加用户仓库为origin，创建本地提交，再上传；不要把本地扩展直接推向官方origin。用户可审阅本次副本后继续指定目标，无需重新整理整个工作区。
