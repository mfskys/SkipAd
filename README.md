# 澎湃SkipAd

> 一次性的小米系统广告开关批处理工具：装好、跑一遍、把 MIUI / HyperOS 能关的系统广告开关关掉，之后可以卸载。

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-13%2B-green.svg)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF.svg)]()

---

## 它解决什么问题

小米系统里的广告散落在十几个地方：系统设置、天气、日历、画报、应用商店、钱包、浏览器、搜索、负一屏、下载管理、垃圾清理、智能服务……每个都要手动翻进去拨开关。

本项目把这些路径写成脚本，由**无障碍服务**代你逐项点完；只在你点「开始处理」后工作，清单走完或失败即停止。开关是**持久设置**，跑完一次之后不再需要本应用。

## 隐私

- **不申请 `INTERNET` 权限** —— 应用没有任何联网能力，不存在"偷偷上传"的通道。
- **不集成任何统计 / 广告 / 崩溃上报 SDK。**
- 处理记录只写在应用私有目录（`filesDir/logs/`），最多 512 KB 自动轮转；只有你点「复制全部」时才会被写到剪贴板。
- 诊断信息包含机型、系统版本、失败项，**不包含账号，也不会列出你装了哪些应用**。

详见 [SECURITY.md](SECURITY.md)。

## 权限说明

| 权限 | 用途 | 能否不给 |
|---|---|---|
| 无障碍服务 | 整个方案的基础：读节点树、执行点击 / 手势 | 不能 |
| `QUERY_ALL_PACKAGES` | 判断某个预装应用是否还在（被卸载/停用的项要如实跳过） | 不能 |
| `SYSTEM_ALERT_WINDOW` | 自动化期间挂 1×1 透明悬浮窗，防止 MIUI 冻结进程 | 不能 |
| `REQUEST_INSTALL_PACKAGES` | 只有「应用包管理组件 · 资源推荐」那一项需要 | 可跳过该项 |
| Shizuku（可选） | 点一下直接开启无障碍，省掉跳系统设置 | 可不用，会降级为手动开 |

> `QUERY_ALL_PACKAGES` 与「无障碍工具」声明在 Google Play 上有政策限制。本项目面向 **GitHub 侧载**，不上架应用商店。

## 环境要求

- **系统**：MIUI / HyperOS（Android 13+，`minSdk 33`）。其他 ROM 未验证。
- **构建**：Android Studio 或命令行；`compileSdk 37 (minorApiLevel 2)`、JDK 25（Gradle 会通过 foojay 自动下载）、Gradle 9.8。

## 构建

```bash
# Windows
gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

首次构建需要：

1. `local.properties`（Android Studio 会自动生成）或环境变量 `ANDROID_HOME`；
2. 本机安装 **API 37** 的 SDK Platform（`sdkmanager "platforms;android-37"`）。

产物：`app/build/outputs/apk/debug/澎湃SkipAd_debug_<version>.apk`

release 需要自备 keystore（四个 `RELEASE_*` 属性，见 [`app/build.gradle.kts`](app/build.gradle.kts) 与 [PROJECT.md](PROJECT.md) 第八节）。**仓库里不含任何密钥。**

## 使用

1. 安装 APK；
2. 按主页「权限获取」的引导依次开启权限（无障碍是前提，其余可自动完成）；
3. 点「开始处理」，等待进度跑完；
4. （可选）处理完关闭无障碍服务，或直接卸载本应用。

已经关掉的项会被记住，中断后再点开始**不会重复跑已完成的任务**。

## 已知限制

- **MIUI 安全中心对自动化有防护**：标准无障碍点击在手动的「应用信息 / 权限管理」页可能无效，因此权限流程里有一部分只能"跳过去 + 由你手动拨一下"。
- **「安装未知应用」页面**的开关不在无障碍节点树里（`noHideDescendants`），无法自动完成。
- **不是所有广告位都能关**：部分开关的值存在小米账号私有存储里，只能通过 UI 点击修改。
- 不同机型 / 系统版本的页面结构可能变化，脚本会失败并给出页面快照，而不是瞎点。

## 项目结构

```
app/src/main/java/org/dpdns/mfsky/skipad/
├── adskip/                 # 自动化引擎（任务、步骤、匹配、点击、调度）
│   └── BuiltinAdTasks.kt   # ★ 内置任务清单（加新任务只改这里）
├── ui/                     # Compose + Material 3 Expressive 界面
│   ├── design/             # 设计系统：主题 / 组件 / 自适应
│   └── feature/            # 主页 / 设置页
└── util/                   # Shizuku 等工具
```

深入的设计决策、45 条真机踩坑记录与调试方法见 **[PROJECT.md](PROJECT.md)** —— 改代码前建议先读它。

## 贡献

- 提交前先读 [PROJECT.md](PROJECT.md) 的设计纪律（尤其"不碰用户其它应用""失败绝不跳过"两条）。
- 新增广告开关任务：只改 `BuiltinAdTasks.kt`，并在 PR 里附上失败快照或真机验证结果。
- 安全问题请走 [SECURITY.md](SECURITY.md) 的渠道，不要开公开 issue。

## 免责声明

本项目为独立开发的第三方工具，与任何厂商无关。所有自动化操作均由用户主动触发，作用于本机系统设置。使用本工具即表示你了解：修改系统设置可能影响部分功能体验，请自行评估风险。

## 许可证

[Apache License 2.0](LICENSE)
