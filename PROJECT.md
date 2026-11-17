# 澎湃SkipAd —— 项目说明

> **给接手开发的 AI / 开发者：先读完本文再动代码。**
> 本文记录的不只是"代码长什么样"，更重要的是"**为什么这样写**"和"**哪些路已经试过走不通**"。
> 最后一节是当前进度，可以先跳过去看。

---

## 一、这是什么

一个**一次性的系统广告关闭工具**。新手机装好、跑一遍、把 MIUI/HyperOS 内置的广告开关全部关掉，之后本应用可以卸载。

### 三条铁律（改代码时不能破坏）

1. **不自动开始**：无障碍服务连上后什么都不做，必须等用户点「开始处理」。
2. **只处理清单内的目标**：清单之外的事件一律忽略。
3. **做完就停**：清单走完或某一项彻底失败就结束会话，此后再也不动屏幕。

换句话说：**它不会在用户正常用手机的时候跳出来点东西。**

---

## 二、目录结构与职责

```
app/src/main/java/org/dpdns/mfsky/skipad/
├── MainActivity.kt                 入口（edge-to-edge）
├── adskip/                         ★ 核心：自动化引擎
│   ├── AdTask.kt                   任务 / 匹配条件（Match）/ 目标解析（MatchTarget）
│   │                               ＋ AdTask.requiredPackages（未安装即跳过，见 3.7）
│   ├── AutomationStep.kt           九种步骤，见 3.1
│   ├── BuiltinAdTasks.kt           ★ 内置脚本（唯一需要维护的业务文件）
│   ├── AutomationRunner.kt         步骤执行引擎（全部冗余都在这里）
│   ├── AdSkipRuleEngine.kt         任务队列调度 + 会话生命周期 + 回主页
│   ├── NodeMatcher.kt              节点匹配（多候选、DOM 关系定位）
│   ├── NodeClicker.kt              点击执行（三级回退 + 去重 + 手势）
│   ├── PermissionTasks.kt          「自动获取权限」任务（走 MIUI 安全中心那条路径）
│   ├── SkipAdAutomationService.kt  前台服务 + 1×1 透明悬浮窗（防冻结，见坑 #10）
│   ├── SkipProgressRepository.kt   已完成任务的持久化（DataStore）
│   ├── PageSnapshot.kt             失败时导出界面快照（排查用）
│   ├── AdSkipRuntime.kt            运行期状态与日志（StateFlow）
│   └── AdSkipAccessibilityService.kt  无障碍服务 ＋ AdSkipPermission（权限查询/跳转）
└── ui/
    ├── SkipAdApp.kt                应用骨架（底部导航）
    ├── adaptive/                   大屏自适应
    ├── design/                     设计系统（组件 / 主题）
    └── feature/
        ├── home/HomeScreen.kt      主页：状态卡 + 权限 + 日志 + 待处理清单
        └── settings/SettingsScreen.kt  设置页：权限 / 外观 / 关于
```

**核心心智模型**：`BuiltinAdTasks.kt` 是"业务脚本"，`adskip/` 其余文件是"引擎"，`ui/` 是"操作台"。
**加新任务只需改 `BuiltinAdTasks.kt`**。

---

## 三、核心设计

### 3.1 任务脚本模型

一个任务 = 一串步骤，每一步**必须真的到位**才进入下一步：

```kotlin
AdTask(
    id = "mi_system_tool_ad",        // ⚠️ 发布后不要改：它是"已完成"的记录键
    title = "系统工具广告",
    guide = "设置 → 搜索「广告」→ 系统广告",   // 失败时给用户照着自己走
    allowedPackages = listOf("com.android.settings", "com.xiaomi.account"), // 防呆判据
    steps = listOf(
        AutomationStep.Launch("android.settings.SETTINGS"),
        AutomationStep.AwaitFor(...),
        AutomationStep.Click(...),
        AutomationStep.Input(text = "广告"),
        AutomationStep.ToggleOff(...),
    ),
)
```

**九种步骤**：

| 步骤 | 作用 | 特殊能力 |
|---|---|---|
| `Launch` | 启动页面 | `action` / `component` / `appPackage` 三选一；`data`（`package:xxx`）；`clearTask` |
| `AwaitFor` | 等节点出现 | `retryable`（超时后重跑整个任务）、`retryPreviousInput`（原地重发输入）|
| `Click` | 点击 | `useGesture`（强制真实触摸，见 3.3）、`optional`（没出现就当跳过）|
| `Input` | 写文本 | `requestFocus`、`submitAfter`（按键盘搜索键）|
| `ToggleOff` | 关开关（终点）| **幂等**（本来就是关的算成功）＋ **验证**（读回 `isChecked`）＋ `confirm`（有确认弹窗时填）|
| `ToggleOn` | 开开关 | 同上，方向相反（用于「安装未知应用」那类授权页）|
| `ScrollTo` | **滚动查找** | 边往下翻边找目标；不依赖页面长度，滚到底还没有才判失败 |
| `ToggleOffAll` | **关掉本页所有开关** | 边滚边关；`exclude` 排除总开关（**不写就会误关**）|
| `PressBack` | 按返回键 | ⚠️ 只用于"刚展开过覆盖层、现在要收起"，**别当通用返回用** |

> `ScrollTo` / `ToggleOffAll` 是"多轮操作"型步骤，走的超时不是默认那 5 秒
>（见 `AutomationRunner.stepTimeout`）—— 它们天生要反复做同一件事，5 秒根本不够。

**为什么需要"批量"步骤**：像钱包功能设置页那种二十多个推荐位开关的页面，
逐个 `ToggleOff` 写死是不现实的 —— 漏写一个那个广告位就还在、多写一个版本更新后
就匹配不到导致整项失败。那些开关的语义是齐的（"这页上的都关掉"），
所以值得用一个专门的步骤来表达，而不是把清单逐条写进脚本。

**匹配条件 `Match`**（多候选，任一命中即算命中，**按顺序尝试**）：

```kotlin
Match(
    text = "系统广告", textMatch = MatchType.Exact,  // 文本 / 匹配方式
    viewIdContains = "settings_search_item_name",     // 资源 id 子串
    descContains = "我的", descMatch = MatchType.Exact, // contentDescription / 匹配方式
    className = "android.widget.Switch",               // 类名（全等）
    clickable = true, editable = true, focused = true, // 状态
    maxTextLength = 10,                                // 长度上限（防误点）
    target = MatchTarget.ClickableAncestor,            // 命中锚点后操作谁
    onlyWhenChecked = true,                            // 只在开着时才点
)
```

`MatchTarget` 三种：`Self`（自身）/ `ClickableAncestor`（可点击祖先）/ `SwitchInSameRow`（同一行的开关）。

⚠️ **`descMatch` 默认是 `Contains`，但底部导航栏那类必须传 `Exact`**：
找「我的」这个 tab 时，`Contains` 会先命中树里更靠前的 `我的钱包` / `我的消息 9` /
`我的头像` / `我的资产 *****` —— 而 `findAnchor` 是**命中即返回**，于是点到了一个
完全无关的地方。踩过一次，见坑 #19。

### 3.2 执行引擎：多层冗余（从轻到重）

全自动最怕"某一次交互没生效"，所以按代价从低到高铺了多层：

| 层 | 机制 | 代价 |
|---|---|---|
| **0. 多候选匹配** | 每步写主选 + 兜底，一路不中就换下一路，日志会打印命中的是第几个候选 | 零 |
| **1. 结果验证** | 输入后读回内容比对；开关操作后读回 `isChecked` | 几乎为零 |
| **2. 操作前稳定性确认** | 点击前要求目标**连续存在 400ms**，避开"列表正在重建"的窗口 | 几乎为零 |
| **3. 原地重发输入** | `retryPreviousInput`：等一半时间还没结果，重写关键字 + 重新提交 | 轻，屏幕不跳 |
| **4. 原地重试本步骤** | 滚动/批量关闭这类"多轮操作"会自己重试（如"滚动未生效"连试 3 次才判到底）| 轻 |
| **5. 整个任务重跑** | `retryable`：上面都不成，从第 1 步重来（最多 2 次，重跑前清去重记录）| 重，屏幕会跳 |

**设计意图：先用便宜的手段**。搜索结果没出来，十有八九只是"那一次输入没触发查询"，没必要重新开设置。

**"多轮操作"型的两个额外纪律**（`ScrollTo` / `ToggleOffAll`，都是从踩坑里总结的）：

- **不能一次失败就下结论**。`ACTION_SCROLL_FORWARD` 在上一次点击手势还没跑完时
  会被拒，返回 `false` —— 那**不代表到底了**。必须连续几次都失败才收手。
- **每轮重新扫描，一次只做一个动作**。关掉一项后页面可能重排（小米画报那页实测会增减条目），
  预收集的节点会集体失效 —— 那正是"点了没反应"的经典成因。

**防呆**：`allowedPackages` 里的包名之外的前台（且非自己）→ 判定"走偏" → 立刻中止并重跑。有 1.5 秒宽限期，读不到包名时判为"没跑偏"（宁可多等，不误重跑）。

### 3.3 ⚠️ 反自动化边界（最重要的一节）

**MIUI/HyperOS 对系统安全中心做了防自动化保护**，实测结论：

| 手段 | 普通应用（如设置搜索框）| **MIUI 安全中心**（应用信息/权限管理）|
|---|---|---|
| `ACTION_CLICK`（无障碍标准点击）| ✔ | ✘ **返回 true 但界面纹丝不动** |
| `dispatchGesture`（无障碍手势/真实触摸）| ✔ | ✘ **同样无效** |
| `input tap`（系统级注入，需 adb/Shizuku）| ✔ | ✔ **唯一有效的方式** |

**推论**：
- 广告开关流程（走的是"设置"App，不是安全中心）✔ 无障碍可以全自动。
- **授权流程要分两类看**（这一节早期写得太笼统，后来实测修正了）：
  - **「其他权限」页里的那些**（后台弹出界面 / 显示悬浮窗 / 获取应用列表）✔ **可以全自动** ——
    它们不是"点列表行跳转"，而是**行内原地展开**一个三选一
    （拒绝 / 仅在使用中允许 / 始终允许），全程不启动新页面，`ACTION_CLICK` 正常工作。
    见 `PermissionTasks`。
  - **「安装未知应用」那一页** ✗ **做不了** —— 但原因**不是**这里的反自动化，
    而是**那页的开关压根不在无障碍树里**：ROM 把装载它的列表项标成了 `noHideDescendants`，
    而这个标记**无法**被 `flagIncludeNotImportantViews` 穿透（那个 flag 能救 `no`，救不了它）。
    极具迷惑性的是 `uiautomator dump` **能**看到那个开关（它的 flags 组合不同），
    于是表现为"adb 能看到、脚本看不到"，很容易被误判成"页面没加载好 / 匹配写错了"。
    所以那一项给的是"跳过去 ＋ 悬浮提示 ＋ 用户打开后自动返回"（`startInstallGrantWatch`）。

**还有一类系统弹窗要主动处理**：跨应用启动时 MIUI/HyperOS 会弹一句确认 ——
「澎湃SkipAd」想要打开「天气」（本次允许 / 始终允许 / 拒绝）。它一出现，
前台就变成了系统包，会被"跑偏检测"判成走偏、把整个任务重跑 ——
实测现象是**"卡一会儿，然后莫名其妙回到软件里"**。
引擎现在会认出它并**点「始终允许」**（系统记住之后不再问），
见 `AutomationRunner.handleJumpDialog` 与坑 #37。

**另一个坑**：`ACTION_CLICK` 对**输入框**也无效（点得中、光标有，但**不弹软键盘**）。而 MIUI 设置搜索框的**查询逻辑挂在 IME 上** —— 不弹键盘就永远不触发搜索（表现为"字写进去了，下面没列表"）。所以 `Input` 步骤必须先用 `NodeClicker.touch()`（`dispatchGesture`，120ms 按压）唤醒键盘。

### 3.4 权限体系

主页的"权限准备"是**步骤流**（5 步），设置页是**常驻列表**。两处顺序一致，
且**刻意和系统页面上的顺序一致**（MIUI 的「其他权限」页里「获取应用列表」
就排在另两个之前），免得用户对照着看觉得"怎么倒着来的"。

| # | 权限 | 作用 | 检测方式 | 能否自动 |
|---|---|---|---|---|
| 1 | **无障碍服务** | 干活的根本 | `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` | ✗ 用户手动（它是前提）|
| 2 | **获取应用列表** | 判断某个预装应用装没装（见 3.7）| `checkSelfPermission(QUERY_ALL_PACKAGES)` | ✓ 自动 |
| 3 | **后台弹出界面**（MIUI 私有）| 处理完能自动切回本应用 | `AppOpsManager` op **10021**（反射 `checkOpNoThrow(int,int,String)`）| ✓ 自动 |
| 4 | **显示悬浮窗** | 自动化期间挂 1×1 透明窗防冻结（见坑 #10）| `Settings.canDrawOverlays` | ✓ 自动 |
| 5 | **允许安装应用** | 只有「应用包管理组件」那一项需要 | `canRequestPackageInstalls` | ✗ 用户手动（见下）|

**第 2~4 步在无障碍就绪后自动接上**：它们本来就在**同一条路径**上
（应用信息 → 权限管理 → 其他权限），所以由 `PermissionTasks` **一次全办掉**，
而不是让用户点三次、看三次跳页。

### 3.4.1 第 1 步的快捷通道：有 Shizuku 就**点一下直接开**

`「无障碍服务」`那一行（主页 / 设置页共用同一个 hook：
`rememberShizukuAccessibilityOpener`）：

| 本机状态 | 点下去会发生什么 |
|---|---|
| 没装 Shizuku，或装了但**没运行** | 跳系统无障碍设置页（= 老路子） |
| 在运行但**没授权** | 弹 **Shizuku 自己的**授权框 → 授权后**自动继续**开无障碍 |
| 已授权 | 直接开，**全程不离开本应用** |

开完把权限状态**手动刷一次**（`rememberSystemState(refreshKey)`），
于是"无障碍刚开启 → 自动接上第 2~4 步"那段既有逻辑立刻接管 ——
用户一个开关都不用拨。

两条硬约束（写错都会出事）：

1. **不能覆盖写** `enabled_accessibility_services`：那会把用户已启用的其它无障碍
   服务（输入法、屏幕朗读…）一起抹掉。所以是"读 → 去重 → 追加 → 写回"。
2. **必须同时写 `accessibility_enabled 1`**：只写列表不写总开关，系统不会去绑定服务
   —— 表现为"设置里看着是开的，其实没跑"。

⚠️ 降级必须彻底：`Shizuku.pingBinder()` 为 false / 请求授权失败 / 被拒 / 命令返回 null
时，一律 `onMessage(一句说明)` + 跳系统设置页。**点了没反应**是最糟的体验。

**实测依据（2026-10-10，Shizuku 13.1.5 运行中，无障碍当时是关的）**：

```
[Shizuku] 准备直接开无障碍：binder=true，已授权=true
[Shizuku] 命令结束：exitCode=0，输出 1 字          ← settings get 的空列表（只有一个换行）
[Shizuku] 追加后写回：'…/AdSkipAccessibilityService'
[Shizuku] 写回后读回 = '…/AdSkipAccessibilityService'
无障碍已连接                                       ← 写 secure settings 后系统立刻绑定
开始获取权限 → [1/20]…[20/20] → 权限已自动获取完成   ← 第 2~4 步自动接上
```

⚠️ **`newProcess` 是 private，必须走 `declaredMethods` + `setAccessible(true)`**：
第一版只查 `Shizuku::class.java.methods`（只含 public）→ 永远找不到 →
`exec` 返回 null → 界面提示"Shizuku 命令没有返回"，而权限明明是给过的。
`javap -p` 实测签名（api 13.1.5）：
`private static rikka.shizuku.ShizukuRemoteProcess newProcess(String[], String[], String)`。

⚠️ **仍未验证的一条**：这次 `enabled_accessibility_services` 本来就是空的，
所以走的是"追加"分支 —— "**不覆盖别人已启用的无障碍服务**"那段逻辑
（读 → 去重 → 追加 → 写回）没被真正验证到。要验它得在**已有其它无障碍服务开着**
的情况下点一次。

⚠️ **「获取应用列表」的判据是系统权限，不是 MIUI 那个同名开关**：

MIUI「其他权限」页确实有一项叫「获取应用列表」，但它**不暴露任何 appop** ——
实测把 `10008 / 10017 / 10020 / 10022 / 10045 / 10053` 逐个设为 `allow`，
那一行的状态**始终显示"拒绝"**。而且**在它显示"拒绝"时本应用读取包完全正常**，
说明真正的闸门是 Android 11 起的 `QUERY_ALL_PACKAGES`，那个开关多半是
Android 10 时代的遗留展示项。

用系统权限做判据还有个好处：**跨 ROM 一致** —— 非 MIUI 机器上没有那个开关，
但读的是同一个系统权限，结论照样对。详见 `AdSkipPermission.isAppListAllowed`。

⚠️ **「允许安装应用」为什么不能自动**：见 3.3 —— 那一页的开关压根不在无障碍树里
（ROM 把装载它的列表项标成了 `noHideDescendants`）。所以给的是
"跳过去 ＋ 悬浮提示 ＋ 用户打开后自动返回"（`startInstallGrantWatch`）。

**为什么需要「后台弹出界面」**：Android 10 起限制后台启动 Activity，HyperOS 另加了这个开关。不开的话，处理结束想把用户送回本应用会被**静默拦截**（不抛异常、不返回失败，界面不动）。

**返回用户的三段式**：
1. 发 `startActivity` / `PendingIntent.send()`；
2. **600ms 后验证前台包名** —— 只有真回来了才记"已切回本应用"（早期版本按"没抛异常"记成功，是骗人的）；
3. 没回来 → 按 `GLOBAL_ACTION_BACK` 往回退（**仅限安全名单内的包**：`AdSkipPermission.PERMISSION_PAGE_PACKAGES` = 系统设置 / 安全中心 / 权限控制器，以及当前任务的 `allowedPackages`）。

**触发的三个时机**（少一个，用户就会觉得"没反应"）：

| 时机 | 入口 |
|---|---|
| 会话结束（完成 / 失败 / 停止） | `AdSkipRuleEngine` 的三条结束路径 |
| 无障碍服务刚连上 | `onServiceConnected` |
| **用户去开「后台弹出界面」、开完** | `onAccessibilityEvent` 的 `!running` 分支 → `maybeReturnAfterGrant` |

⚠️ 第三条是后补的：前两条在"用户去授权"这个场景下**都不会发生**（服务不会重连，也没有会话在跑），
必须由窗口事件驱动 —— 只认 `未允许 → 已允许` 的**状态翻转**，
且只在系统权限页的事件上才去查（用户在自己别的应用里时保证零动作）。

---

### 3.5 UI：MD3E 对齐（结论已逐项核对 m3.material.io 规范）

全部使用 `androidx.compose.material3` 官方组件与 token，**全项目零硬编码色值**
（`Color(0x…)` 一处都没有）。四条红线：

**① 主题入口必须是 `MaterialExpressiveTheme`**

`MaterialTheme` 拿不到第 4 个子系 `motionScheme`，而 M3E 组件动画（形状 / 尺寸的弹簧物理）
全部从它取值。深浅色不对称是**官方设定**：浅色用 `expressiveLightColorScheme()`，
深色**没有** expressive 版，正确回退 `darkColorScheme()`（material3 源码原话）。
自定义动画一律取 `MaterialTheme.motionScheme.xxxSpec()`，**禁止**硬编码 `tween(300)`。

**② 形状只用八档 token（M3E）**

`shapes.extraSmall / small / medium / large / largeIncreased / extraLarge / extraLargeIncreased / extraExtraLarge`
（4 / 8 / 12 / 16 / 20 / **28** / 32 / 48dp）。
⚠️ `extraLarge` 官方是 **28dp** —— 旧版这里写的 24dp 是错的（M3 旧文档常见误抄）。
**禁止** `RoundedCornerShape(8.dp)` 这类魔法值 —— 差一档就用相邻 token，或走组件的 `shape` 参数。

**③ 颜色一律取 color role**

`primary` / `onPrimary` / `primaryContainer` / `onPrimaryContainer` / `surface*` / `outlineVariant` …
**成对使用**（容器色 + `on*` 前景色）。页面底用官方默认 `background`（`Scaffold` 默认），
**要加层次就加深卡片色，别动页面底**（顶栏 `surface`、导航栏 `surfaceContainer` 的分层都挂在它上面）。

**④ 组件选型优先 M3E 新件**

| 场景 | 用 | 不用 |
|---|---|---|
| 顶栏 | `Medium` / `LargeFlexibleTopAppBar`（随滚动折叠） | 旧的 `TopAppBar` |
| 导航 | `NavigationSuiteScaffold` + **`navigationItems`** 入口 | 旧的 `navigationSuiteItems` |
| 对话框内单选 | `ButtonGroup` + `toggleableItem`（连接式，组级编排） | `FilterChip` / 裸 `ToggleButton` |
| 开关 | `Switch` + `thumbContent` + `SwitchDefaults.IconSize` | 纯色 handle |

**两处踩过的具体偏差（已修）**：

- **Switch 的 handle 图标不能手写 tint**：官方为它单独定义了一个 color role
  「On primary container」（switch specs 里 6 个 role 的第 6 个），由 `SwitchDefaults`
  经 `LocalContentColor` 注入。手写 `primary` 会**盖掉官方 token**，正确做法是**不传 tint**。
- **`NavigationSuiteItem` 必须显式传 `navigationSuiteType`**：它的默认值基于固定常量
  `WindowAdaptiveInfoDefault`，不对应当前窗口；不传会导致 Item 与 Scaffold 形态不一致
  （宽侧栏里中文标签被压成竖排）。

**自适应纪律**：窗口判断只经 `ui/adaptive/Adaptive.kt` 这一个出口，
用 `currentWindowAdaptiveInfoV2()` + `WindowSizeClass` 断点；
**禁止** `isTablet` / `LocalConfiguration.screenWidthDp` 这类设备 / 物理屏判断。
大屏内容用 `AppAdaptiveContent` 限宽居中（列表 840dp、表单 640dp）。

**列表一律用官方的 expressive list**：

```kotlin
Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = i, count = n),
        colors = ListItemDefaults.colors(containerColor = colorScheme.surfaceContainer),
        leadingContent = ..., supportingContent = ..., content = ...,
    )
    // …
}
```

封装见 `SettingGroup`（容器）与 `SettingItem` / `SettingInfoItem`（行）——
行只做"参数 → 官方槽位"的映射，**不自己画任何东西**。

⚠️ **不要自己拼分段样式**。曾有一版自写容器（按 index 算圆角 + 自己跑按下动画 +
用 `Surface` 当卡片）—— **方向是对的**（就是在模拟官方 expressive list），
但手工值必然漂：实测圆角写成 **6dp**，而官方规范是 **4dp inner / 16dp outer**。
官方有现成组件（`SegmentedListItem`，1.5.0 起）时一律用官方。

#### 3.5.1 容器色阶 / 图标 / 文字：官方取值表（**改配色前先查这张表**）

⚠️ 下面每一格都来自 **material3 `1.5.0-beta01` 的 `tokens/*.kt` 源码**
（拿 `javap`/源码 jar 查，不凭观感）—— "哪个容器该用哪一档"正是最容易漂的东西：
列表行曾一度被写成 `surfaceContainerHighest`（跟卡片同色），把层级抹平了。

**容器色**（M3 的抬起**只能靠色阶**：列表行是 0dp，不靠阴影，见 `ListTokens.ItemContainerElevation`）

| 层 | 官方取值 | 来源 |
| --- | --- | --- |
| 页面底 | `Surface`（≡ `background`） | `Scaffold` 默认 |
| 顶栏·展开 | `Surface` | `AppBarTokens.ContainerColor` |
| 顶栏·滚动后 / 导航栏 | `SurfaceContainer` | `AppBarTokens.OnScrollContainerColor` / `NavigationBarTokens.ContainerColor` |
| **列表行（分段）** | **`SurfaceContainer`** | 官方示例 `ListSamples.SegmentedListItems` |
| 列表行（token **默认值**） | `Surface` | `ListTokens.ItemSegmentedContainerColor` |
| Elevated Card | `SurfaceContainerLow` | `ElevatedCardTokens.ContainerColor` |
| Filled Card（**官方默认；本应用不用**） | `SurfaceContainerHighest` | `FilledCardTokens.ContainerColor` |
| **本应用的卡片**（进度 / 权限 / 处理记录） | **`SurfaceContainer`**（与列表行、导航栏同档） | `appCardColors()`（`Tokens.kt`） |
| 对话框 | `SurfaceContainerHigh` | `DialogTokens.ContainerColor` |

→ 本应用的层次是**两级**：**页面 `Surface` ＜（卡片 / 列表行 / 导航栏 / 滚动后的顶栏）`SurfaceContainer`**。

⚠️ **官方 Filled Card 的默认容器是 `SurfaceContainerHighest`（比列表行还重一档），本应用刻意不用**：
主页是"卡片 + 列表行"混排，卡片重一档，同屏就看成两种东西、卡片显脏（**实测被明确否掉**）。
卡片与列表行同色之后，区分靠**形状与结构** —— 卡片是整块 12dp 圆角容器（内部有区块标题），
列表行是 `SegmentedListItem` 的分段形状（2dp 间隙）。官方 `cards/guidelines.md` 也说过：
*不要把内容硬塞进卡片；间距、标题、分隔线往往能给出更简单的层次*。

⚠️ 三张卡片**必须都走 `Tokens.kt` 的 `appCardColors()`**，别再各写 `CardDefaults.cardColors()` ——
那等于回到 Filled Card 的默认档，卡片又会比列表行重一档。

⚠️ 换配色类型时容易撞到的另一件事：**`rainbow` 的中性面是纯中性灰**（实测 `surfaceContainer = #EEEEEE`，
R=G=B），而 `tonalSpot` / `vibrant` 的面**带紫调**（`#F2EBFA` 一类）。也就是说"强调色有多鲜艳"
和"面是否带色"在官方类型里**绑在不同档上**，没法只要其一 —— 换类型时两件事会一起变，
换之前先在同一台机器上截图对比。

**配色类型（决定"鲜不鲜艳"；2026-10-11 改）**

⚠️ **官方的 `expressiveLightColorScheme()` 并不鲜艳** —— 本文件以前那句"M3E 重新调校过的
强调色序列"是**错的**。material3 1.5.0-beta01 源码 `ColorScheme.kt` 原文：

```kotlin
public fun expressiveLightColorScheme(): ColorScheme =
    lightColorScheme(
        // TODO: Replace palette references with color token references when available.
        onPrimaryContainer = PaletteTokens.Primary30,
        onSecondaryContainer = PaletteTokens.Secondary30,
        onTertiaryContainer = PaletteTokens.Tertiary30,
        onErrorContainer   = PaletteTokens.Error30,
    )
```

它只改了 **4 个 `on*Container`**，primary / primaryContainer 等角色与基线 M3 完全相同 ——
而基线用的是 **TonalSpot** 类型（primary 的 chroma 只有 36），所以整屏发灰。

**同一颗种子（品牌紫 `#6750A4`）的鲜艳度实测 —— 官方只有两档，没有中间档**：

| 类型 | primary | **chroma** | 当色块（按钮/进度条） | 当文字（14sp 小字） |
| --- | --- | --- | --- | --- |
| `tonal-spot` | `#65558F` | 36 | 发灰 | 很温和 |
| **`rainbow`（在用）** | **`#6750A4`** | **48** | 经典 M3 紫 | **不刺眼** ✓ |
| `vibrant` | `#6F19FF` | **87** | 好看、够冲 | ✗ **刺眼**（实测被否） |
| `expressive` | `#006B5A` | 37 | 主色换青绿 | 品牌紫消失 |

⚠️ **教训：鲜艳度是"色块"的属性，不是"文字"的属性**。同一条 `primary`，铺成按钮
（大色块 + 白字）很漂亮，拿去当 14sp 小字就刺眼（`复制全部`、`处理清单`、日志里的成功行
都是 primary 文字）。官方**没有中间档**（不是 36 就是 87），所以只有两条路：
① 换 `rainbow`（48，现在用的 —— 够紫、不刺眼）；② 保持饱和、把**文字**退回中性角色
（区块标题/正文用 `onSurfaceVariant`，`primary` 只留给色块与图标）。

**改配色 = 重新生成，不许手改色值**（`app/src/main/java/.../ui/design/theme/Color.kt`
里一共 96 个角色）：

```bash
cd tools && npm i
node gen_m3_scheme.mjs --scheme rainbow --seed '#6750A4' \
    --in-place ../app/src/main/java/org/dpdns/mfsky/skipad/ui/design/theme/Color.kt
```

（`--in-place` 只替换两个 `val` 块，文件里的 KDoc 与 `appColorScheme` 原样保留。）

深色端同样由它生成（官方**没有** `expressiveDarkColorScheme`，而深浅两端必须同种子、同类型，
否则切换时"像换了个应用"）。工具见 `tools/gen_m3_scheme.mjs`，并且**自检过**：
重新生成的 96 个角色与本仓库 `Color.kt` 逐字一致 ✓。

⚠️ **与动态取色（Material You）互斥**：设置里一旦打开，走的是系统按壁纸算的 TonalSpot
（比本方案灰）—— 所以默认关闭，见 `Color.kt` 的 `appColorScheme`。

**形状 / 图标 / 文字**

| 项 | 官方取值 | 来源 |
| --- | --- | --- |
| 分段行圆角 / 行间隙 | `segmentedShapes(i, n)` / `SegmentedGap = 2dp` | `ListItemDefaults` |
| 列表行图标 | **24dp** | `ListTokens.ItemLeadingIconSize` / `ItemTrailingIconSize` |
| chip 图标 | **18dp** | `AssistChipTokens.IconSize`（Filter/Input 同为 18dp） |
| 行标题 / 行描述 | `BodyLarge` / `BodyMedium` | `ListTokens.ItemLabelTextFont` / `ItemSupportingTextFont` |
| 对话框正文 / 形状 | `BodyMedium` / `CornerExtraLarge` | `DialogTokens.SupportingTextFont` / `ContainerShape` |

⚠️ **行的文字不写 `style`、行的图标不写 `tint`** —— `ListItem` 内部用
`ProvideTextStyleFromToken` 下发文字样式、`LocalContentColor` 注入图标色
（源码可见）。写死会盖掉 token，等于自己制造"和官方不一致"。

**应用层的三种"标题"（M3 没有对应 token，靠这里的约定统一）**

| 用途 | 取值 | 出现在 |
| --- | --- | --- |
| 卡片**内**的区块标题 | `titleSmallEmphasized`（14sp / 字重 Bold） | 「权限获取」「处理记录」 |
| 卡片**内**的内容块标题 | `titleMediumEmphasized`（16sp / 字重 Bold） | 「权限获取」卡里的当前步骤块 |
| 页面级的组标题 | `titleSmall`，颜色 `primary` | 「处理清单」（主页）、`SettingGroup` 的标题（设置页） |

⚠️ **要强调就用官方的 emphasized 字阶，别手写 `fontWeight`**。M3E 给 `Typography`
一次加了 15 个 emphasized 字阶（`titleSmallEmphasized` 等，KDoc 原话
*an emphasized version of [titleSmall]*），源码 `TypeScaleTokens` 的实际取值为：

| 字阶 | 字号 | 字重 |
| --- | --- | --- |
| `titleSmall` / `titleSmallEmphasized` | 14sp / 14sp | Medium 500 / **Bold 700** |
| `titleMedium` / `titleMediumEmphasized` | 16sp / 16sp | Medium 500 / **Bold 700** |

手写的 `SemiBold` **两边都不沾**（既不是 baseline 的 500，也不是 emphasized 的 700）——
这正是两张卡表头"看着像两个组件"的根源之一。层级靠**字号**拉开，不靠加粗堆叠。

⚠️ 区块标题（14sp）**必须小于**它统领的内容（条目 16sp）—— 这是 M3 自己的做法
（官方 Settings 的区块标签就比条目小）。所以卡片内"内容块"的标题用 16sp 是对的。

**卡内表头的内边距**：两张卡共用 `HomeScreen.kt` 的 `CardHeaderPadding` /
`CardHeaderPaddingWithActions`（`start=20, top=16, bottom=8`，走 8dp 栅格；
右侧挂 `TextButton` 时 `end=8` —— 按钮自带 12dp 内边距，**视觉右边界同样是 20dp**）。
官方排版规范对这件事有原话：*Ensuring consistency between similar-looking editorial
moments, creating tokens for each where needed*。

⚠️ **KDoc 里别写 `/**`**（例如 `（14sp/**Bold**）`）：**Kotlin 的块注释是嵌套的**，
这个 `/*` 会再开一层注释，于是**从那里起到文件末尾全部被吞掉**，编译器报的是
`Syntax error: Unclosed comment`，外加一堆位置**更早**的 `Unresolved reference`
（后半段的私有声明集体"消失"）。见到"文件后半部分声明全没了"就先查注释配对。

**庆祝撒花**（处理全部成功后放一场）—— `ui/design/component/CelebrationFireworks.kt`

⚠️ **自己实现，不引入任何撒花库**。第三方撒花库的发射器有语义坑：
按"时长 + 数量"配置会**无限刷粒子** —— 真机第一次触发就 `OutOfMemoryError` 闪退。

改自绘 Canvas 后的三条纪律：

1. **粒子总数必须是常数**（= `SHOTS` 各发 count 之和，约 830）——
   这是"绝不可能 OOM"的根本保证；不写任何"按时间无限发射"的逻辑。
2. **粒子是可变对象、只在出生时 new 一次**，每帧只改字段 + swap-remove 回收：
   整场动画**零分配**（60fps 下每帧重建列表是 GC 抖动的常见来源）。
3. **重绘靠一个"帧时钟"状态**（`clockMs`）：绘制 lambda 读它（真心用在算淡出上），
   Compose 只失效**绘制**阶段、不触发重组。物理量按屏高缩放（速度单位 = 每秒几个屏高）。
   另：`dt` 必须封顶（切后台回来帧间隔可能是几秒，不封顶一帧就把粒子甩出屏幕）。

⚠️ **测试触发**：连点底部导航「主页」**10 下** = 手动放一次
（`SkipAdApp.kt` 的 `NAV_TAP_TRIGGER`，与系统"连点 7 下开开发者选项"同一个思路）。

---

### 3.6 处理进度与「继续处理」

**目标**：处理到一半中断（手动停止 / 服务被解绑 / 进程被杀）后，再点开始
**不会把已经关好的开关再关一遍**。

**为什么不靠幂等就够了**：`ToggleOff` 本来就幂等（开关是关的 → 直接算成功、不点击），
所以重复跑**不会出错**；但一个 9 步的任务重跑照样要走完 9 步的等待与匹配。
跳过必须是**任务粒度**的 —— 连页面都不打开。

**数据流**（两个方向）：

| 方向 | 位置 |
|---|---|
| 读：DataStore → 内存 | `SkipAdApp` 里 `LaunchedEffect` 订阅 → `AdSkipRuntime.setDoneTaskIds` |
| 写：任务成功 → DataStore | `AdSkipRuleEngine` 里 `progressScope.launch { markDone }` |
| 用：会话开始时过滤 | `startSession()` 中 `BuiltinAdTasks.filterNot { it.id in done }` |

⚠️ **引擎是同步 API，读不了 suspend 的 DataStore** —— 所以由**界面层当桥**，
把进度预先同步进 `AdSkipRuntime`（内存 StateFlow），引擎只读内存。
这也是 `startSession()` 里那个 `queue` 是"过滤后的快照"的原因。

⚠️ 落盘用独立 `progressScope`（IO），**不能 `runBlocking`** —— 引擎跑在主线程
（`AutomationRunner` 用主 Looper 的 Handler），阻塞等磁盘会掉帧甚至 ANR。

**只记成功的**：失败的任务不记 —— 下次还要再试（失败多是一次性的时序问题）。
记录只增不减（除非用户清空），所以不存在"记错了导致永远跳过"的风险。

**三种按钮语义**（`HomeScreen` 的 `StatusCard`）：

| 历史进度 | 标题 | 按钮 |
|---|---|---|
| 一项都没跑过 | 准备就绪 | 开始处理 |
| 跑过一部分 | 可以继续处理 | **继续处理（剩 N 项）** |
| 全部跑完 | 全部已处理 | **重新处理全部**（先清空进度） |

⚠️ 「重新处理全部」的清空顺序是**先落盘清空 → 再清内存 → 最后启动**：
引擎读的是内存，不先清的话它会因为"没有待处理项"而直接跳过。

---

### 3.7 未安装的应用直接跳过

**背景**：清单里的项散落在好几个**独立应用**里（天气、日历、画报、应用商店、钱包、
搜狗输入法……），而用户完全可以只卸载其中一部分。没有这道过滤的话，那些项会在第 1 步
"打开 XX"就卡住（应用不存在、Intent 解析不到），干等到超时，
然后**在清单里留一条永远失败的红项** —— 用户既没法处理，也不知道为什么失败。

**判据从哪来**：`AdTask.requiredPackages` 是个计算属性，从步骤里的 `Launch` **自动推导**：

| 启动方式 | 能否推出包 |
|---|---|
| `component = "包/类"` | ✓ 取 `/` 前段（如 `com.xiaomi.market`）|
| `appPackage = "包"` | ✓ 直接用（如 `com.miui.weather2`）|
| `action = "系统 action"` | ✗ 推不出 → **不检查**（那类都是系统标准页面，一定存在）|

**为什么不手写清单**："要启动哪个应用"这件事步骤里本来就写着，手写一份等于
**同一事实记两处** —— 改了一处忘另一处，就会出现"任务其实换应用了、但可用性还按老包名判断"。

**为什么不复用 `allowedPackages`**：那是"防呆"判据，列的是**路径上会经过**的所有包
（如系统广告任务里的 `com.xiaomi.account` 只是小米账号服务）。拿它当"必需存在"会误判。

⚠️ **判据除了"没装"，还包括"被停用"**：MIUI 允许停用预装应用，停用后包信息仍在
`packageManager` 里，但组件一律启动不了 —— 只看"能不能查到"会漏掉这种，照样白等超时。

⚠️ **整套判据有个硬前提：`QUERY_ALL_PACKAGES`**（见 3.4 与坑 #16）。
少了它，`getApplicationInfo()` 会对所有没在清单里声明过的包抛
`NameNotFoundException` —— 与"真的没装"**完全无法区分**，
`isPackageUsable` 会把"装了"读成"没装"。**而且全程不报错**，
看上去就像那些应用真的不在（实测后果：进度环显示 `3/3`）。
**改动这一块之前，先确认 `AndroidManifest.xml` 里那条声明还在。**

**引擎与界面共用同一份判据**（`usableAdTasks`）：不可用项不进队列、**不计入待处理数**
（否则进度环永远到不了"全部完成"）、清单里标"未安装"且不可点。

⚠️ **刻意不写进"已完成"记录**：用户哪天把天气装回来了，这一项应当**重新回到待处理**。
若记成"已完成"，那个开关就永远没人处理了。

⚠️ **清单里"处理中"的序号要在可用列表里数**：引擎的队列是过滤后的，
拿全清单的 `index` 去比 `currentTaskIndex`，卸载过应用的机器上会**标错行**。

---

## 四、实测踩过的坑（血泪清单）

**改代码前务必读一遍，很多"看起来多余的代码"都是这些坑的补丁。**

1. **首页搜索框与搜索页输入框同 id、同占位文字**，且首页那个本身也是可点击的 `EditText` → `AwaitFor` 会在页面切换的 12ms 内**误命中**，把字写进首页那个不触发查询的框。**唯一稳定差异是焦点** → `focused = true`。
2. **MIUI 搜索框的查询挂在 IME 上** → 必须先用真实触摸唤醒软键盘（`NodeClicker.touch`，按压 120ms；30ms 太短唤不醒）。
3. **`ACTION_CLICK` 对 MIUI 安全中心的列表行完全无效**（返回 true 无反应）→ 见 3.3。
4. **系统设置是单 Activity 架构**（首页/搜索页/详情页都是同一个 Activity + Fragment）→ `Launch` 必须带 `Intent.FLAG_ACTIVITY_CLEAR_TASK`，否则设置停在子页面时，`NEW_TASK` 只把任务提到前台，界面还是子页面（表现为"点了开始什么都没发生"）。
5. **结果列表里有两条相似文本**：`settings_search_item_name` = "系统广告"、`settings_search_item_path` = "**设置**系统广告"。`Contains` 会同时命中 → 必须 `Exact` + 限定 viewId。
6. **搜索结果异步渲染**（先骨架后数据）→ 点击前要稳定性确认（节点连续存在 400ms），否则点到即将失效的节点（"点了没反应然后卡住"）。
7. **`shouldSkip` 曾经从未被调用**（写好了但没人用）→ 点击失败会每 300ms 狂点同一位置。现在 `doClick`/`doToggleOff` 都接上了闸门。
8. **关开关后必须读回验证**：`performAction` 返回 true ≠ 开关真的被切换。早期"点完就报成功"是假成功报告。
9. **重跑任务前必须 `clicker.resetSession()`**：否则上一轮点过的节点全被去重闸门挡下，重试一步都点不动。
10. **厂商 ROM 会在后台冻结本应用**（MIUI / HyperOS 实测）：跑自动化时应用会长时间待在后台（它自己去打开了系统设置 / 下载管理），系统据此把整个 **uid** 冻住 —— 现象是**所有线程停止调度**：日志停在一行、界面完全不动，连应用自己的看门狗和心跳线程都不再触发。
    - **从进程内部查不出来**：进程状态仍是 `S`、没有 `SIGSTOP`、CPU 占用 0%。只有从外部读 cgroup 才看得到：`/sys/fs/cgroup/apps/uid_xxx/cgroup.freeze` = `1`（注意 `pid_xxx` 子目录是 `0`，冻结按层级继承）。
    - **前台服务挡不住**：实测 `isForeground=true` 时 `cgroup.freeze` 仍是 `1`，MIUI 绕过了 Android 标准的"有前台服务就不冻结"规则。
    - **解法**：自动化期间挂一个 **1×1 像素、全透明的悬浮窗**（见 `SkipAdAutomationService`）—— 厂商的冻结判据基本是"这个应用还有没有可见窗口"。加上之后 `cgroup.freeze` 变 `0`，完整两项由"卡死"变成 **2.7 秒跑完**。
    - **反向铁证**：手动把应用切回前台，`cgroup.freeze` 立刻变 `0`、日志当场恢复 —— 这正是用户反馈里"手动返回后它自己接着跑"的机制。
    - ⚠️ 与「自启动」「省电策略」都无关：实测自启动关掉同样能成功。
10. **返回键兜底的安全闸门用 `queue` 判断，而服务刚连上时 `queue` 是空的** → 无障碍设置页被误判为"不安全"→ 返回键一次都没按过。修复：固定名单 `com.android.settings` + `com.miui.securitycenter`。
11. **`AccessibilityNodeInfo.ACTION_IME_ENTER` 在编译 SDK 上解析不到** → 改用 `AccessibilityAction.ACTION_IME_ENTER.id`。
12. **`AppOpsManager.checkOpNoThrow` 的 int 重载在编译期解析不到** → 反射调用。
13. **「后台弹出界面」的 op 号是 10021**（`adb shell appops get <pkg>` 里的 `MIUIOP(10021)`），无障碍树里查不到，`settings` 三个命名空间也没有。
14. **系统广告开关的值不在任何标准 settings 里** → 存在小米账号私有存储，`adb`/`Shizuku` 都改不了，**只能点 UI**。（`global/passport_ad_status` 不是它，别被名字骗了。）
15. **`SystemAdActivity`（`com.xiaomi.account`）`not exported`** → 连 `adb shell am start` 都被 `SecurityException` 拒绝，只能靠模拟操作走过去。

**—— 以下是后续几轮新增的 ——**

16. **软件包可见性会让"没装"和"看不见"长得一模一样**（本清单里**最容易静默出错**的一条）。
    Android 11（API 30）起，`getApplicationInfo()` / `getInstalledPackages()` /
    `queryIntentActivities()` 对**没在清单里声明过**的包一律抛
    `NameNotFoundException` —— 与我们想识别的"真的没装"**完全无法区分**。
    - **后果实测**：清单里大半任务被误判成"未安装"直接跳过，主页进度环显示 `3/3`，
      而机器上其实一项都没少装。**且全程不报错**，看起来就像那些应用真的不在。
    - **解法**：声明 `QUERY_ALL_PACKAGES`（普通权限，声明即生效）。
    - ⚠️ **不用 `<queries>` 逐包声明**：那要求把包名再维护一份到 Manifest、**跨文件**，
      漏抄一个不编译报错、只会静默跳过 —— 正是上面那个事故的成因。
    - ⚠️ **注意 MIUI 的干扰项**：它的「其他权限」页有个同名开关「获取应用列表」，
      但那个**不暴露任何 appop**（把 `10008/10017/10020/10022/10045/10053` 逐个设 allow
      它都显示"拒绝"），而且显示"拒绝"时读取依然正常。判据要用系统权限。
17. **`exported=false` 的 Activity 连 adb 都起不来**，而 `PendingIntent.send()` 发它
    **不抛异常** —— 日志只会打一句"启动请求已受理"，然后页面纹丝不动、白等超时。
    实测证据：`not exported from uid 10146`。**遇到"点了没反应"先查这个，不要去调匹配条件。**
    已知的：应用商店功能设置页、搜狗输入法设置页、钱包功能设置页、
    `com.android.packageinstaller` 的设置页、小米账号的系统广告页、
    系统设置的 `HttpInvokeAppSettings`（网页链接调用服务）。
    ⚠️ **判断 `exported` 最省事的办法就是拿 `am start -n <包>/<类>` 试一次** ——
    成功会打 `Starting:`，未导出直接抛 `Permission Denial`。
    别去翻 `dumpsys package` 的 Activity Resolver 表：**查不到 ≠ 没导出**，
    那只说明它没有 intent-filter（下载管理的两个设置页就是这种，`exported=true`）。
18. **`content-desc` 的 `Contains` 会先命中树里靠前的节点**。
    钱包主页找底部导航「我的」时，`我的钱包` / `我的消息 9` / `我的头像` /
    `我的资产 *****` 都排在它前面，而 `findAnchor` 是**命中即返回** →
    点到了一个完全无关的地方。加 `descMatch = MatchType.Exact` 解决。
19. **`shouldSkip` 把「节流」和「点不动」混成一个 `true`**，而调用方对这两种情况的反应
    应当**完全相反**（前者要等一会儿再试，后者才该放弃）。没区分时，
    正常但还没生效的开关被记进"放弃名单" → 表现是"**跳着关、漏掉一堆**"。
    加 `NodeClicker.isExhausted` 把两者分开。
20. **点击失败不记账会静默卡死**。`click()` 返回 false 时如果什么都不做：
    `shouldSkip` 查不到这个节点 → 返回 false → 下一轮原样重选 → 无限循环，
    而且这条分支**既不打日志也不推进**，用户看到的是"关了几个之后就没动静了"。
21. **屏幕外节点的 bounds 会被裁剪成 `top > bottom` 的无效矩形**。
    RecyclerView 把屏幕外的 item 也放进节点树，bounds 形如
    `Rect(60, 3302 - 1380, 3110)`（屏幕高才 3110）—— 中心点算出来 3206，
    点下去必然落在屏幕外。**必须按"中心点是否在屏内"过滤**。
    ⚠️ 别用 `isVisibleToUser`：它对这些屏幕外 item 也报 `true`。
22. **手势是异步的，队列会堵**。`dispatchGesture` 在上一个手势还没 `onCompleted`
    时再派发**会被系统拒绝**（`click()` 返回 false）。所以紧密连续点击会越来越容易失败 ——
    这也解释了"前面几次都成功、后面突然全失败"。
23. **`ACTION_SCROLL_FORWARD` 返回 false ≠ 已经到底**。手势没跑完时它同样返回 false。
    必须**连续失败几次**才收手。实测踩到：滚动只成功一次就被判定到底，而屏幕上还有一大半没关。
24. **外层 ScrollView 会抢在 RecyclerView 前面被选中**。页面为了标题栏折叠常套
    CoordinatorLayout / NestedScrollView，它们**也**声明可滚动，对外层发滚动未必有效。
    解法是**挨个试所有可滚容器**，谁成功算谁 —— 而且日志要打印**实际生效的容器名**，
    那是"滚不动"时最关键的线索。
    （⚠️ 本机实测最终生效的**是外层那两个 ScrollView**，RecyclerView 压根没进候选 ——
    所以"优先 RecyclerView"只是排序，**不等于**只能用它。）
25. **"多轮操作"型步骤不能用默认超时**。`DEFAULT_TIMEOUT_MS` 是 5 秒，
    按"单个动作 ＋ 等页面"设的；而 `ToggleOffAll` 要关二十多个开关还要滚动 ——
    实测"才关了几个就报失败"。
26. **每几十毫秒全树扫几百个节点会把主线程拖垮**（日志里 `Skipped 5x frames` 刷屏）。
    而那不只是"卡一下"：主线程被拖住会连累 `dispatchGesture` 派发和滚动动作，
    让它们更容易被拒 —— 形成"越卡越失败、越失败越卡"的恶性循环。加扫描节流。
27. **有确认弹窗的开关，不点弹窗开关不会真的关**。小米画报的「锁屏轮播」「锁屏右滑」
    都是弹框等确认，而「早安画报」点一下直接生效 ——
    所以这个差别只能由脚本按开关逐个声明（`ToggleOff.confirm`）。
    给没有弹窗的开关硬加一个，会让脚本在那儿干等一个永远不出现的按钮。
28. **同一页面的 Switch 可能共享同一个混淆 id、且没有 desc**。搜狗输入法那页 6 个开关
    全叫 `c0a`；钱包那页全叫 `switchWidget`。这种只能靠"标题文本 ＋ `SwitchInSameRow`"定位。
29. **「输入法管理」这类页面，一行上有两个独立热区**：左侧 `id=head`（点它进设置）、
    右侧 `Switch`（点它启用/停用）。**点错会把输入法停掉，用户就打不了字了**。
    好在这类行分得很干净 —— `ClickableAncestor` 找到的祖先右边界够不到右边的开关。
30. **有些设置页的项会随开关状态增减**。小米画报那页：「锁屏轮播」开着时多出
    「使用流量加载图片」，关着时多出「轮播设置」「我的壁纸」「浏览历史」。
    所以**不要把这页当成固定列表**，也别让任何步骤靠"前面有几行"来定位。

**一类共性（值得单独记住）**：上面 #19 #20 #21 #22 #23 全是**同一个模式** ——
某个返回值或状态**同时表示多件事**（"失败了"vs"还没到"/"被节流"vs"坏了"），
而调用方只按其中一种理解。这类 bug **不崩、不报错，只会安静地做错事**，
所以每次都要靠日志反推。写这类判据时先问一句：**这个 false 有几种可能的含义？**

31. **去重表的键不含"页面身份"，跨页面会互相污染**。
    `NodeClicker` 的 `nodeKey` 只由 `类名 | viewId | left/8,top/8` 组成，
    而同一族页面的布局常常高度相似 —— 实测「搜索」的四个热榜子页
    （热搜 / 生活 / 娱乐 / 话题）**第一个开关的 bounds 完全相同**
    （`[44,595][1396,834]`），于是它们在去重表里是**同一个 key**。
    - **症状**：微博点过 → `count=1`；笔记点过 → `count=2`；轮到短剧时
      `isExhausted`（阈值 2）已成立 → **刚进页面就被判成"反复点都不生效"而跳过**。
    - **铁证**：日志里那句"有一个开关反复点都不生效，跳过它"出现在
      **步骤开始后 3ms** —— 3 毫秒内不可能真的点过两次。
    - **解法**：`beginStep()` 里 `clicker.resetSession()`。它**不会**破坏
      `ToggleOffAll` 的内部重试（那是在一个步骤内跨多轮，而 `beginStep` 只跑一次）。
32. **"手势派发被拒"和"点击发出去了但没反应"要分开计数**。
    前者是**暂时性**的（`dispatchGesture` 在上一个手势还没 `onCompleted` 时会被拒），
    等一会儿再发就能成；后者才说明控件可能真点不动。
    - **症状**：混在一起记时，每页**第一个**被处理的开关常被误判成"点不动"——
      因为它前面刚做完别的页面的动作（`PressBack` ＋ 页面切换），手势还没跑完。
    - **解法**：`Attempt` 拆成 `count`（成功发出）和 `dispatchFails`（派发被拒）
      两个字段，放弃条件用 `isExhausted || isDispatchExhausted`，
      而**派发失败的额度给到 5 次**（比点击失败的 2 次宽得多）——
      误放弃的代价是直接漏掉一个开关。
    - ⚠️ 别和 #19 搞混：#19 是"**节流 vs 点不动**"（同一个计数器的两种含义）；
      这条是"**两种失败**该用两个计数器"。
33. **手动走出来的路径未必是最优路径 —— 每一层都值得单独测一次"能不能直达"**。
    「网页链接调用服务」那一项，用户口述的路径是
    「设置首页 → 隐私与安全 → 安全 tab → 更多安全设置 → …」，
    照着手动走一遍是 **13 步**（含 3 次滚动）。逐层实测后发现：
    - `HttpInvokeAppSettings`（终点）✗ `not exported from uid 1000`，
      连 adb 都起不来 → 这最后一段**确实必须走 UI**；
    - `security.PrivacySafetyActivity` ✓ 可直达（但最终用不着它了）；
    - **「更多安全设置」那页 ✓ 有现成的标准 action** ——
      `android.settings.SECURITY_SETTINGS`，一行直接落到那页。
    - 最终 **5 步** 完成，省掉两次滚动和两次跳转。
    - ⚠️ 那个 action 是 **AOSP 标准**的（不是 MIUI 私有扩展），所以比
      "用 `SubSettings --es :settings:show_fragment <内部类名>` 直达"更值得依赖 ——
      后者实测也 work（Fragment 是 `com.android.settings.security.SecuritySettings`），
      但依赖内部类名，版本一变就断。
    **教训**：拿到一条路径后，**逐层问一句"这一层有没有更直接的入口"**。
    优先级是：**标准 action（`android.settings.*`）→ `component` → 一层层点**。
    代价只是几条 `am start`，收益常常是十几个步骤。
    ⚠️ 另外：**用户口述的路径要当成"线索"而不是"规格"** —— 他可能只是
    恰好那样点进去过一次，未必知道有没有捷径。
34. **`component` 直达会清空目标应用的任务栈，于是"按返回键回主页"会直接退出那个应用**。
    浏览器那一项：第 ① 步 `Launch(component = "…BrowserSettingsActivity")`
    （默认带 `CLEAR_TASK`）→ 浏览器任务栈里**只剩这个设置页**，
    `BrowserActivity`（主页）压根不在栈里。接着在设置页按两次返回：
    第一次退回「设置」主页（**同一个 Activity 内切 Fragment，不动栈**），
    第二次就出栈 → **栈空 → 退出浏览器**，画面回到本应用，
    后面几步全落在自己主页上，一路等到超时。
    - **症状**：日志里 `mAppVisible = true` ＋ `Activity = …本应用.MainActivity`，
      紧接着就是"等待 XXX 页超时"。
    - **解法**：回"别人的主页"要用 `Launch(appPackage = …)`
      （等价于点桌面图标），**不要用 `PressBack`**。
      实测在任务栈已经被清空的情况下，它照样稳定落到目标应用的 launcher activity。
      用 `clearTask = false`：那是别人的任务栈，没有理由去清空它。
    - ⚠️ **别信自己手动试出来的栈状态**：我手动试时"两次返回正好回主页"，
      那是因为**浏览器是我自己开过的**，栈里有 `BrowserActivity`。
      任务里走的是直达，栈里从来没有它 ——
      **手动验证必须复现任务的启动方式，否则测的是一个不存在的场景。**
35. **系统设置里的「应用信息」页在本 ROM 上无法直达 —— 两条路都证伪**。
    目标是智能服务（`com.miui.systemAdSolution`）那个**谷歌原生**的应用信息页
    （不是安全中心那个小米自己的页面）。从 APK 清单里**确实**查到了独立组件：
    - `E: activity name="com.android.settings.applications.InstalledAppDetailsTop"` `exported=true`
    - 同名 activity-alias `InstalledAppDetails` 指向它。

    **① adb 的 `am`** —— 这一页靠 `package:` 这个 **data URI** 拿包名，而 `am`
    传的 data 到不了它手里，页面**立刻自己退出**（焦点还停在上一个界面）。
    六种写法结果一样：`-d` 分开传 / 整串传 / 设备端加引号 /
    `cmd activity start-activity` / `--es package`（extras **确实进去了**，
    日志里有 `(has extras)`）/ data 放最前。

    **② App 自己发**（`Launch.data`，不经过 shell，data 一定带得上）——
    **实测进到的页面还是不对**。

    - 附带排除的：`-a android.settings.APPLICATION_DETAILS_SETTINGS -d package:…`
      会被 MIUI 抢走，路由到**安全中心**的
      `com.miui.appmanager.ApplicationsDetailsActivity`；
      `-n …/.applications.appinfo.AppInfoDashboardActivity` 报 `Error type 3`（本 ROM 无此组件）。
    - **唯一能走的路**：所有应用 → 底部「更多」→「显示系统应用」→ 滚动查找 → 点击。
    - ⚠️ **顺带记一个会误导调试的陷阱**：`am` / `cmd activity` 打印 Intent 用的是
      `Intent.toString()` → `Uri.toSafeString()`，它**本来就会省略 URI 内容**
      （`-d https://example.com` 也只显示成 `dat=https://example.com/...`）。
      所以那行 `dat=package:` **既不能证明 data 丢了、也不能证明 data 在** ——
      我把它当成"data 没传进去"，白查了好几轮。
36. **高频无障碍事件里做 binder 查询会把日志刷屏、并拖垮主线程**。
    `onAccessibilityEvent` 的 `!running` 分支里会查一次「后台弹出界面」状态
    （`AppOpsManager.checkOpNoThrow`，一次 binder 调用），用来识别"用户刚授权"。
    而滚动列表 / 页面刷新会瞬间产生几百个 `TYPE_WINDOW_CONTENT_CHANGED` ——
    实测**同一毫秒查了几十次**，日志被 `后台弹出界面 op=10021 mode=0` 刷屏
    （用户随便一贴就是几百行），主线程也被这些调用拖住、连累后面的滚动和点击。
    - **解法**：加 1 秒节流（`GRANT_CHECK_INTERVAL_MS`）。用户动作本来就是秒级的，
      而"只认翻转"的逻辑**不依赖高频采样** —— 一次翻转只会被消费一次。
    - ⚠️ 排查这类现象时注意：**"回到本应用"未必是权限判断被误触发** ——
      先看那段刷屏**前面**会话是不是已经结束了（`running == false`）。
      大多数情况下那只是**任务失败后的正常收尾**，别去改返回逻辑。
37. **系统的「打开其他应用」确认弹窗会被"跑偏检测"误判成走偏，把任务拖死**。
    跨应用启动（脚本里几乎每个任务的第一步）在 MIUI/HyperOS 上会触发系统确认框：
    「澎湃SkipAd」想要打开「天气」→ **本次允许 / 始终允许 / 拒绝**。
    - **症状**：任务"卡一会儿"，然后**莫名其妙回到本应用**。根因是弹窗一出，
      前台就变成系统包（不在 `allowedPackages` 里），`isOffTrack` 在 1.5 秒宽限期后
      判定走偏 → 整个任务重跑 → 弹窗又出现 → 又走偏；重试耗尽后报失败、
      结束会话并把用户拉回本应用 —— 而那个页面其实**差一步就打开了**。
    - **它不是"跑偏"**：弹窗是**我们自己的启动请求**引出来的，属于正常路径。
    - **解法**（`AutomationRunner.handleJumpDialog`）：认出它 → 点「始终允许」
      （选过之后系统就记住，这块石头只会挡一次）→ 并在 `isOffTrack` 里放行
      弹窗所在的系统包。
    - ⚠️ **识别条件不能用「始终允许」**：MIUI 权限页那个
      「拒绝 / 仅在使用中允许 / 始终允许」三选一里也有它（见 `PermissionTasks`，
      那里连行尾图标都是 `desc="始终允许"`），拿它当判据会在权限页上误命中。
      要用**「本次允许」**和标题里的**「想要打开」**——这两个只有该确认框才有。
    - ⚠️ **必须在走偏判定之前处理**，顺序反了照样误判。
    - ⚠️ 三个时序细节：① 弹窗是模态的，要给它补一点当前步骤的超时时间
      （否则 5 秒的步骤光等它就被耗光）；② 刚发出 `Launch` 的那一刻前台
      **还停在旧页面**（会话开始时就是本应用自己），所以那之后 3 秒内不做
      "前台不是系统包就跳过"的快速筛；③ 点击额度按**步骤**重置，一个任务里
      连着跨好几次应用时，每次都得有完整的点击机会。
    - ⚠️ **只在会话运行期间**处理：会话结束后再弹出来的框不点 ——
      那是"服务连上后什么都不做"这条铁律的地盘。
    - ⚠️ **本轮新加，待真机复测**。
38. **`Launch(component = …)` ＋ `clearTask = false` 会"恢复"到应用上次停留的页面 ——
    若那个 Activity 是多个页面的宿主，脚本走到第二步就找不到东西了**。
    应用商店那一项（实测第二次跑整项失败）：`MarketTabActivity` 是**所有页面的宿主**
    （各页是它内部的 Fragment）。上一轮跑完时商店停在「功能设置」页，于是下一轮的第 ① 步
    只把**那个已存在的任务提到前台**，界面照旧是「功能设置」：
    ```
    [1/9] 打开应用商店 → 正在启动：…/.ui.MarketTabActivity（flags=0x10000000）
    [2/9] 点「我的」   → （5 秒后）失败快照：action_bar_title_expand='功能设置'
    ```
    第 ② 步要在**主页**上找底部标签「我的」，而「功能设置」是整屏子页、根本没有底部导航
    → 白等 5 秒超时。
    - **判据**：`NEW_TASK` 只是"提到前台"，**不保证**回到主页；`clearTask = false`
      更是主动放弃了"重置到已知页面"的唯一手段。
    - **解法**：凡"第一步要的是**确定性地回到主页**"的项，就用 `appPackage`
      （等价于点桌面图标）**＋ 默认的 `clearTask = true`**：先清掉**它自己的**任务栈
      再启动 launcher Activity，必然落在主页。
    - ⚠️ **"跨应用带 `CLEAR_TASK` 会卡住不返回"不适用于 `appPackage` 这种方式** ——
      同一次日志里「天气」用的就是 `appPackage` ＋ `flags=0x10008000`（含 `CLEAR_TASK`），
      启动后直接点「城市管理」成功。那条结论是 `component` 直达那批实测出来的。
    - ⚠️ **反例对照**：`component` **直达某个具体 Activity** 的项（下载管理 / 搜索 /
      负一屏 / 日历 / 小米画报…）**没有**这个问题 —— 那些页面是独立 Activity，
      启动它就是要停在它上面。**只有"宿主 Activity ＋ Fragment 子页"才踩这个坑。**
      自查办法：`Launch` 的第一步是"回主页"，而不是"直达某个设置页"时，都要过一遍这条。
    - ⚠️ **这条实际漏改过两次**，共同点是"写法在 `component` 那批里是对的，
      搬到 `appPackage` 上就没跟着改"：
      **钱包**（`clearTask = false` → 上一轮跑完停在「功能设置」子页，下一轮第 ① 步
      只把任务提到前台，第 ② 步在子页上找主页的「首页」标签 → 20 秒超时
      ＋ `retryable` 整项重跑 → 用户看到的是"卡住了、一直在等、也不报错"）和
      **浏览器**（同一个写法，只是还没暴露）。两处现已统一为默认的 `clearTask = true`。
    - 💡 顺带一个**排查经验**：`AwaitFor` 的 `retryable = true` 会让"超时"表现为
      **日志里反复出现"仍在等待…"**（每轮都从头重来），而不是一条明确的失败 ——
      看到"等了好久还不失败"时，先去看那一步的 `timeoutMs` × 重试次数，
      别急着以为引擎没有超时机制。
39. **节点边界 ≠ 热区；而且"用户自己关掉广告"会**改变布局**，同一个锚点就失效了**。
    钱包「我的」页那一项实测（第二次跑整项失败）：
    - **形态变了**：广告**没关**时，设置是个**独立**的图标节点、`desc` 恰好是 `"设置"`；
      广告**关掉之后**，标题「我的钱包」和齿轮被自绘框架**合并成同一个节点** ——
      `desc='我的钱包\n设置'`、bounds 覆盖整条顶栏 `[0,0]-[1440,411]`、`clickable=true`。
      而脚本里写的是 `descMatch = Exact`，于是 `"我的钱包\n设置" ≠ "设置"` →
      `[4/9] 等待「我的」页` 白等 10 秒超时、整项重跑。
    - **热区也不在中心**：那个合并节点点中心 `(720,205)` **毫无反应**，
      能进设置的是右侧偏下那一小块（`adb shell input tap 1370 290` 实测进得去）。
      "节点即热区"的假设在自绘页面上不成立。
    - **解法**：① 锚点一次写**两个形态**（`Exact` 在前、`Contains` 兜底）；
      ② 新增 `Match.clickFraction`（`0.95f to 0.70f`）声明"手势落点用节点内的
      相对位置"—— 指定后点击**直接走真实手势**、跳过 `ACTION_CLICK` 两级。
    - ⚠️ **教训（比这条坑本身更重要）**：凡是"用户操作会改变界面"的页面
      （关掉广告位、开启某功能之后布局重排），锚点都得按**两种形态**写，
      否则就是"第一次跑能过、第二次必挂"。
    - ⚠️ `clickFraction` 只在确有必要时用：比例写死，布局一变就会点偏。
40. **`ACTION_SCROLL_FORWARD` 会"返回 true 但几乎不挪"；判"到底了"同样不能看返回值**。
    MIUI「所有应用」那页（130+ 个应用、拼音排序，「智能服务」在最末尾）实测：
    - **症状**：`ScrollTo(maxSwipes = 30)` 报"滚到页底也没找到"，失败快照停在
      **T~W 段**（天气 / 铁路12306 / …/ 外部存储设备 / 万能遥控）—— 离末尾还差一大截。
    - **根因**：那个列表上 `ACTION_SCROLL_FORWARD` **每按一次只挪一两行**（约 2 个条目），
      而且照样返回 true。30 次 ≈ 60 个条目，从 A 段也只走到 T 段。
      同一页用**真实手势**一次能滑一整屏（≈14 个条目，`adb shell input swipe` 实测）。
    - **另一个细节**：那一页**滚到最底部之后还要再滑一次**，「智能服务」那段才露出来
      （分段渲染）。所以"一到看似到底就收手"会把最后一段漏掉。
    - **解法**（都在 `AutomationRunner.doScrollTo`）：
      ① 用 `scrollFingerprint(container)`（容器内头 3 + 末 3 条文字）判断
        **"内容到底动没动"**，不再信 `ACTION_SCROLL_FORWARD` 的返回值；
      ② 一旦发现"上一轮滚完内容没变"，就**切到真实手势**滑一屏
        （`NodeClicker.swipeUp`，落点 80% → 20%）；
      ③ 连续 [MAX_SCROLL_STUCK=3] 轮内容完全没变，才判"已到列表底部"；
      ④ `ScrollTo` 的步骤超时 30 秒 → 60 秒（`maxSwipes` 大的脚本要够用），
        该项的 `maxSwipes` 30 → 45。
    - ⚠️ 这条把坑 #23（"返回 false ≠ 到底"）又推进了一层：**返回值在"进度"上两个方向
      都不可信** —— 可能"true 但没动"，也可能"false 但动了"。判进度只能看**内容**。
41. **"这一刻找不到 X" 不等于 "永远没有 X" —— 列表重新加载的中间态会把步骤判死**。
    「所有应用」页点完「显示系统应用」之后，MIUI 会把列表**清空重载**，那一瞬间页面是：
    ```text
    @loading_container / @loading_progress (ProgressBar) / text='正在加载...'
    @apps_list_container childCount=0        ← RecyclerView 还没重建
    ```
    而 `ScrollTo` 开头那句"找不到可滚容器 → 页面不可滚动，判失败"在**步骤开始后 77ms**
    就下结论了，一次本来能成的滚动查找就此判死（用户看到的是"刚点完就报失败"）。
    - **判据**：这类"找不到就立刻失败"的判定全都可疑 —— 它把**临时中间态**当成了
      **页面属性**。这和坑 #23 / #40 是同一个思维错误。
    - **解法**：改成**有界重试**（`MAX_SCROLL_NO_CONTAINER_RETRIES = 8`，按节流 ≈3 秒），
      连续多轮都没有才认定"这页真不可滚动"，并且日志里写清"页面可能在重新加载"。
    - ⚠️ 自查办法：任何 `fail(...)` 的触发条件，先问一句
      **"这个条件会不会因为页面正在加载/切换而暂时成立？"**
42. **列表会"带着上次的滚动位置"进来 —— 只往下找，永远找不到在上方的目标**。
    「所有应用」页实测（第 ⑥ 步失败）：快照里列表明明在、还有 11 个子项，
    引擎却报"连续 9 次都没有可滚动容器"：
    ```text
    · @apps_list androidx.recyclerview.widget.RecyclerView Rect(0, 0 - 1440, 2803) childCount=11
    · 中国农业银行 / 中国移动云盘 / … / 转转 / 自动连招     ← 这是列表**末尾**那一段
    · 连续 9 次都没有可滚动容器，且未找到目标
    ```
    - **两个原因叠在一起**：
      ① [findAllScrollables] 的判据是 `isScrollable` **且** `actionList` 里有
         `ACTION_SCROLL_FORWARD` —— 而**列表停到底部时这两个都不成立**（没得往下滚了）；
      ② 那一页**继承了上一次会话滚到底的位置**，而目标就在**视口上方一点** ——
         只往下翻永远碰不到它（上一次跑完时正是滚到底找它的）。
    - **解法**（都在 `AutomationRunner`）：
      ① `findScanTarget`：找不到"能往后滚的容器"时退一步用 `findListLike`
        （按类名找 RecyclerView / ListView / ScrollView）—— 目的是**还能用手势翻它**，
        因为真实手势不受 `actionList` / `isScrollable` 这些标记的限制；
      ② "往下推不动"不再直接判失败，而是**换方向**：`scrollSearchBackward`
        → 用 `NodeClicker.swipeDown` 回头往上找，上下都翻过还没有才失败。
    - ⚠️ 教训：**"翻不动"和"找不到"是两件事** —— 前者只说明这个方向到头了，
      而目标可能就在对侧。这和坑 #23 / #40 / #41 是同一族错误：
      **别把"某个手段失效"当成"结论"**。
43. **Android 14+：没声明 `android:isAccessibilityTool` 的服务读不到"数据敏感"的界面**。
    - **症状**：「应用信息 → 通知」那一页
      （`com.android.settings.Settings$AppNotificationSettingsActivity`）上
      `rootInActiveWindow` **恒为 null**，一路等到超时（实测 18 秒+）；用户看到的是
      **"卡住，然后莫名其妙回到软件里"**。
    - **它有多能骗人**：同一页 `uiautomator dump` 读得到「允许通知」「悬浮通知」「锁屏通知」，
      系统日志里那一页 `Displayed … +168ms`（渲染完全正常），窗口 `fl=` 里也**没有**
      `FLAG_SECURE` —— 页面一点毛病没有，就是**我们读不到**。
    - **假线索**：失败快照里"另一个窗口"只剩状态栏。那是**假象** ——
      快照代码 `val root = window?.root ?: continue` 把"根读不到"的窗口**静默跳过**了
      （已修：现在会把这类窗口连同 `type`/`title`/`根=读不到` 一起列出来）。
      真相是窗口**在**，只是它的节点树取不到。
    - **成因**：Android 14 起，被 `AccessibilityNodeInfo.setAccessibilityDataSensitive(true)`
      标记的界面**只对声明为「无障碍工具」的服务开放**，而承载**通知内容**的页面正是
      典型例子。实测系统：`Android 17 / SDK 37 / MIUI V816`。
    - **解法**：`app/src/main/res/xml/ad_skip_accessibility_config.xml` 里加
      **`android:isAccessibilityTool="true"`**。
      ⚠️ 这条属性的语义是"本服务是给残障用户用的无障碍工具"，Google Play 对此有政策；
      **本项目要上架时需要重新评估这一条**。
    - **实测验证**（改前/改后是同一页）：
      · 改前：18 秒恒 null，任务被拖死；
      · 改后：`[刚连上] 前台=com.android.settings，窗口 2 个 · id=38683 type=1 title=设置 根=com.android.settings`；
      · 并且 #16 整条任务 **21 步全过、成功 1 · 失败 0**（第 ⑪ 步真的把「允许通知」关掉了）。
    - ⚠️ 教训：**"读不到界面" ≠ "页面没到"**。日志里只剩一串"仍在等待…"时，必须能区分
      ①服务没连上、②窗口在但读不到、③页面没切过来 —— 这三者的处置完全相反。
      为此 `AdSkipAccessibilityService` 现在会在**连上时**与**连上后首个事件**时
      打一条 `probeScreen` 基线日志（前台包名 + 全部窗口含"根=读不到"的那些）。
44. **无障碍组件名有两种写法，只认一种会让「开始处理」按钮凭空消失**。
    - 同一个服务，系统会在两种写法之间**自动改写**，而 `Settings.Secure` 里存的
      恰恰是**缩写式**：
      ```text
      org.dpdns.mfsky.skipad/org.dpdns.mfsky.skipad.adskip.AdSkipAccessibilityService
      org.dpdns.mfsky.skipad/.adskip.AdSkipAccessibilityService      ← settings get 拿到的是这个
      ```
      而 `dumpsys accessibility` 的 `Enabled services` 显示的是**展开式** ——
      两边不一致，很容易拿其中一边"证明"自己是对的。
    - **症状**：服务明明跑着，首页却显示"无障碍未开启"、**「开始处理」按钮根本不出现**
      （连报错都没有，用户只会以为应用坏了）。
    - **解法**：比较前把两边都**展开**（`包名/.类名` → `包名/包名.类名`），
      见 `AdSkipPermission.componentMatches` / `expandComponent`。
      同一个坑在 Shizuku 那条直达里也有：不展开就每次都以为"列表里没有"，重复追加。
    - ⚠️ 自查（adb，两条命令的写法**必然不同**）：
      ```powershell
      adb shell settings get secure enabled_accessibility_services
      adb shell dumpsys accessibility | Select-String 'Enabled services'
      ```
45. **主页滑动"卡一下"：一大块内容挤在一个 `LazyColumn` item 里 ＋ 顶栏折叠让内容槽每帧重跑**。
    - **现象**：主页往下滑、到「处理清单」附近时顿一下。
    - **两个机制叠在一起**（只修一个都没用）：
      ① `处理清单` 是 `LazyColumn` 的**一个 item**，里面 `SettingGroup` 把 15 行**一次性**
         建出来 → 那一整块必须**同一帧里组合完**，滑进视口时掉帧；
      ② 顶栏是 `exitUntilCollapsedScrollBehavior()`（**随滚动折叠**），它每帧都在改高度，
         而 `Scaffold` 的内容槽会因此**每帧被重新调用** —— 那一块的入参里有
         `List` / `Set`（Compose 眼里**不稳定**）→ **整块 15 行每帧重组**。
    - **改法**：清单拆成"**一个任务 = 一个 item**"（`itemsIndexed(key = id)`），并把每行的
      展示数据先算成 `TaskRowUi`（字段只有 `String` / `Boolean`，**全稳定**）→ 行能被
      Compose **跳过**；行点击回调只吃 `id`（`onTaskClick(id)`）——
      **不要**现造捕获 `AdTask` 的 lambda，捕获不稳定对象会让整行重新变得不可跳过。
    - ⚠️ **这一条没有拿到干净的量化结论**：`dumpsys gfxinfo` 在"adb 注入滑动"下的噪声
      （卡顿率 ±0.2%、尾巴 28~77ms 都有）盖过了改动本身，**最终以手感为准**。
      量法（可复现）：
      ```powershell
      adb shell dumpsys gfxinfo <pkg> reset
      # 来回滑几次，然后看 Janky frames 与 HISTOGRAM 尾巴
      adb shell dumpsys gfxinfo <pkg>
      ```
    - ⚠️ **若还卡，按这个顺序继续**（同类机制，同一个修法）：
      · `LogCard` —— 最多 20 行挤在一个 item 里，且 `buildReport` 捕获了 `outcomes`；
      · `StatusCard` —— 它的回调捕获了 `Context`（不稳定）。
      两者都可以用 `@Immutable` 包装类（如把 `LogRows` / 报告入参裹一层）再拆 item。
    - ✅ **已按此修完的地方**（2026-10-10）：
      · 主页「处理清单」—— 拆成每行独立 item + `TaskRowUi`；
      · **设置页三组（权限 / 外观 / 关于）** —— 拆成"标题 item + 每行一个 item"
        （`SettingsScreen.settingsHeader` / `settingsRow` 两个 `LazyListScope` 扩展），
        行点击统一收敛到 `remember` 出来的 `onRowClick(id)`，行 lambda 只捕获
        "一个稳定单例 + 一个常量"。
      · 仍**未做**：`LogCard`（20 行一个 item）。

## 五、当前进度

### ✅ 已完成

- **广告关闭引擎全链路跑通**，**16 条任务全部真机验证通过**（#16 于 2026-10-10 跑通，见坑 #43）：

| # | 任务 | 入口情况 |
|---|---|---|
| 1 | 系统工具广告 | 走系统设置的账号入口（不走搜索，见坑里的历史原因）|
| 2 | **系统设置 · 网页链接调用服务** | **AOSP 标准 action `android.settings.SECURITY_SETTINGS` ＋ `ScrollTo`**（见坑 #33）|
| 3 | **系统设置 · 隐私与广告开关** | 同一个 action 打开「安全」页 ＋ **两次 `component` 直达子页**；三项**都不需要滚动** |
| 4 | 下载管理 · 信息流 | `component` 直达（该页 `exported=true`）|
| 5 | 应用包管理组件 · 资源推荐 | ⚠️ 先进安装界面，再进右上角设置（设置页未导出）|
| 6 | 垃圾清理 · 推荐内容 | `component` 直达 |
| 7 | 天气 · 内容推广 | `appPackage`（等价于点桌面图标）|
| 8 | 日历 · 内容推广 | `component` 直达 |
| 9 | 应用商店 · 福利与抽奖 | 走 UI：主页 →「我的」→ 齿轮 → 滚动找「功能设置」；⚠️ **主页必须用 `appPackage` ＋ `CLEAR_TASK` 重建**（见坑 #38）|
| 10 | 小米画报 · 锁屏推广 | `component` 直达（**没有桌面图标**，只能直达）；两个开关要 `confirm` 点掉确认弹窗 |
| 11 | 搜狗输入法 · 节日活动提醒 | 走 UI：系统设置 → 语言与输入法 → 输入法管理 → … |
| 12 | 钱包 · 首页推荐位 | 走 UI：钱包主页 →「我的」→ 设置 → 功能设置 ＋ `ToggleOffAll`；⚠️ 「设置」入口在**关广告前后是两种布局**（独立图标 / 与标题合并成整条顶栏），锚点要两种都写 ＋ `clickFraction`，见坑 #39 |
| 13 | 搜索 · 首页展示模块与热榜 | `component` 直达搜索设置页（**无桌面图标**）；四个热榜子页各一个 `ToggleOffAll` |
| 14 | 负一屏 · 推荐卡片与热门活动 | `component` 直达（**没有桌面图标**，只能直达）|
| 15 | 浏览器 · 主页简洁版与首页模块 | `component` 直达设置页 → 选简洁版 → **`appPackage` 重新打开主页**（不能用返回键，见坑 #34）→ 关两个首页模块 |
| 16 | 智能服务 · 通知与高级权限 | 走 UI：所有应用 → 底部「更多」→「显示系统应用」→ 滚动查找「智能服务」（**该页无法直达**，两条路都证伪，见坑 #35）；列表 130+ 项、目标在最末尾，滚动走真实手势 ＋ `maxSwipes = 45`（坑 #40），并在翻不动时**回头往上找**（坑 #42）|

✅ **#16 已在真机上跑通**（2026-10-10 第 7 轮，`21 步全过 · 成功 1 · 失败 0`）。
关键的最后一环是坑 #43：那页「通知」在 Android 14+ 上属于"数据敏感"界面，
不声明 `android:isAccessibilityTool` 就**恒定读不到**（页面明明是好的）：

| 轮次 | 卡在哪一步 | 由此修的东西 |
|---|---|---|
| 第 1 轮 | 滚到「智能服务」之前 | 第 ③ 步改挑 `clickable=false` 的文字节点；修坑 #36 的刷屏 |
| 第 2 轮 | 第 ⑥ 步「滚到页底也没找到」 | 坑 #40：内容指纹判进度 + 手势回退 + `maxSwipes` 30 → 45 |
| 第 3 轮 | 第 ⑥ 步**过了**（滚 39 次命中），卡在第 ⑩ 步「等待通知页」（`根节点为空`） | "读不到界面"可见化 + 因它超时时给该步一次延时 |
| 第 4 轮 | 第 ⑥ 步「页面不可滚动」——点完「显示系统应用」列表正在重载 | 坑 #41：改成有界重试 |
| 第 5 轮 | 第 ⑥ 步「连续 9 次都没有可滚动容器」——列表停在底部，`ACTION_SCROLL_FORWARD` 已消失，而目标在**上方** | 坑 #42：按类名兜底拿到列表 + 翻不动就换方向（往上找） |
| 第 6 轮 | 第 ⑩ 步「等待通知页」——`根节点为空` 死等 18 秒后失败（页面其实好着） | 坑 #43：声明 `android:isAccessibilityTool` + 连上时打屏幕基线日志 |

**✅ 第 7 轮跑通**：`21 步全过 · 成功 1 · 失败 0`（第 ⑪ 步真的关掉了「允许通知」）。
排查它的过程顺带产出了坑 #35、#36、#40、#41、#42、#43。

⚠️ **第 2 位是用户指定的**（他想优先处理它），别按"整理顺序"挪到后面去。
它也是**唯一只用标准 action 就能直达目标页**的任务 ——
把 `android.settings.*` 这类公开 action 排在 `component` 之前的理由见坑 #33。

⚠️ **#2 与 #3 同族，但刻意没有合并成一个任务**。两者都在
`android.settings.SECURITY_SETTINGS` 这一族页面里，乍看"打开一次全办完"更省事，
但实测下来两边**形态不同**：

| | 目标 | 能否直达 | 要不要滚动 |
|---|---|---|---|
| #2 | 网页链接调用服务 | ✗ `HttpInvokeAppSettings` 未导出 | ✓ 要滚到页底 |
| #3 | 用户体验改进计划 | —（在「安全」页首屏）| ✗ |
| #3 | 使用情况和诊断数据 | ✓ `UsageAndDiagnosticsActivity` | ✗ |
| #3 | 广告服务 → 个性化广告推荐 | ✓ `ad.AdServiceSettings` | ✗ |

合并会把逻辑变成"先在页首办三件、再滚到页底办一件"两段，而 #2 已经跑通验证过。
拆开的代价只是多打开一次「安全」页（约一秒）。
**这类"看起来该合并"的判断，先逐个测一遍"能不能直达"再决定**（见坑 #33）。

**入口情况的三种类型都齐了**（可直达 / 只能走 UI / 有图标但设置页未导出），
每个任务的注释里都写了**实测依据**，不要凭直觉改成另一种。

**目前最复杂的一条是 #12**（26 步，含 4 次页面往返 ＋ 4 次 `ToggleOffAll`）——
它也是坑 #31 #32 的来源：同一族页面布局高度相似，暴露了"去重键不含页面身份"
和"两种失败共用一个计数器"这两个隐患。**新增同类任务时先看这两个坑。**

- **多层冗余**（见 3.2）全部实现并实测。
- **跨应用启动的系统确认弹窗**（「想要打开」）自动点「始终允许」——
  见 3.3 与坑 #37（**本轮新加，待真机复测**）。
- **无障碍工具声明**（坑 #43）：`android:isAccessibilityTool="true"` ——
  Android 14+ 上这是读到"数据敏感"界面的**必要条件**，否则「通知」那页恒定读不到。
- **连上时的屏幕基线日志**：服务每次连接打一条"前台 + 全部窗口（含 `根=读不到` 的那些）"，
  用来一眼区分 ①服务没连上 ②窗口在但读不到 ③页面没切过来
  （`AdSkipAccessibilityService.probeScreen`）。
- **批量操作**：`ScrollTo`（滚动查找）、`ToggleOffAll`（关掉整页开关，可排除总开关）。
- **未安装即跳过**（见 3.7）：从步骤自动推导依赖包，引擎与界面共用判据。
- **日志体系**：每步带命中候选序号与读回结果；同步输出 logcat（tag = `SkipAd`）；主页日志卡片支持**一键复制**（排查时整段发出去）；上限 150 条。
- **页面快照**：任务失败时自动把"失败那一刻的界面节点"追加进日志，用来定位"为什么没找到"。
- **权限体系（5 项）**：见 3.4 —— 无障碍 / 获取应用列表 / 后台弹出界面 / 显示悬浮窗 / 允许安装应用；
  前 4 项可检测，中间 3 项可自动获取；返回用户的三段式（发送 → 验证 → 返回键兜底 ＋ 安全名单）。
- **Shizuku 快捷通道**：本机装了并运行着 Shizuku 时，「无障碍服务」那一行
  **点一下直接把无障碍开起来**（见 3.4.1）；没有它一律降级回系统设置页，功能不受影响。
- **UI**：主页（状态卡 / 权限步骤流 / 日志卡片 / 待处理清单带结果徽章）、设置页（权限实时状态 / 外观 / 关于）。
- 代码 0 warning，lint 0。

### ⬜ 未完成 / 待决策

1. **`ToggleOffAll` 的效率还能提**。钱包那页实测约 20 秒关完全部开关，
   其中相当一部分花在 `NodeClicker` 的 500ms 节流上（手势点击必须限速，否则会被系统判异常输入）。
   - 可试的方向：如果那些 Switch 的**父容器** `clickable`，第 2 级 `ACTION_CLICK` 就能命中，
     **完全绕开手势和节流**，能快好几倍。
   - 需要先 dump 确认父容器（`widget_frame`）的 `clickable` 状态。
2. **`ToggleOffAll` 会留下几个"反复点都不生效"的开关**（实测每页 2~4 个）。
   日志会如实说"跳过它"，但要弄清是"父容器不可点"还是"手势没落对位置"。
3. **Shizuku 引擎**（**已部分采用**，范围严格受限）：
   - **已做**：只用于「无障碍服务」那一行的**快捷通道**（点一下直接开，见 3.4.1）——
     走 `Shizuku.newProcess` 以 shell 身份跑两条 `settings` 命令。
     ⚠️ `newProcess` 是隐藏方法，只能反射（Shizuku 社区已有多个项目长期这么用）。
   - **仍未做**（当初否掉的理由不变）：`input tap` 去点 MIUI 安全中心、
     `pm suspend` / `pm disable`（用户明确不要"停服务"那条路）。
   - 定位仍是"**可选加速通道**"：没装 / 没运行 / 没授权时，功能一个不少，
     只是退回到"跳系统设置手动拨开关"。**不让 Shizuku 成为任何必需环节。**
4. **选择器语法**：暂不引入层级语法（`>`、`@`）。
   现有 `Match` = 属性 AND + 多候选 OR + 3 种目标解析，实测够用。
   真遇到"必须靠父子层级定位"的页面再说（`ToggleOffAll` 里的"按行排除"已经是个苗头）。
5. 上架/发布相关（图标、商店描述、签名）未处理。

---

## 六、开发约定（红线）

1. **不碰用户的其它应用**：任何自动化动作只允许在"当前任务的 `allowedPackages`"和固定名单（系统设置、MIUI 安全中心）内进行。返回键尤其要小心。
2. **不做 `pm suspend` / `pm disable`**（用户明确不要）。关广告只走"点 UI 开关"。
3. **失败绝不跳过**：宁可上报失败让用户看到，也不"猜着往下点"。
4. **不硬编码颜色**：全部走 Material 3 官方 token（`primaryContainer` / `surfaceContainerHighest` / `errorContainer`…）。
5. **第三方源码不改**：本项目无第三方 UI 库依赖，全部自研。
6. **改动 `AdTask.id` = 让用户重新处理一遍**，非必要不改。
7. 依赖统一在 `gradle/libs.versions.toml` 管理（**不要**在模块里写死版本；AGP 已由 `:app` 带入 classpath，`:miuix` 那类本地模块要无版本声明）。

---

## 七、调试方法

### 7.1 看引擎日志（最快）

```powershell
# 实时
adb logcat -s SkipAd
# 一次性导出
adb logcat -d -s SkipAd
```

应用内也能看：主页「处理记录」卡片 → **「复制全部」**。

#### 「处理记录」与「复制全部」

| 项 | 内容 |
|---|---|
| 界面显示 | 只铺**最近 20 条**（`MAX_VISIBLE_LOGS`）—— 全铺会把页面撑得极长 |
| 复制的内容 | **完整日志 + 诊断头**，由 `buildDiagnosticsReport` 组装 |
| 日志存在哪 | `filesDir/logs/skipad.log`（**落盘**；512KB 后轮转，另存 `skipad.log.1` 一份历史） |
| 为什么必须落盘 | 内存只留最近 150 条，且进程一重启（MIUI 会解绑/杀）就全没了 —— 而"用户出了问题再回头复制"经常发生在重启之后 |

诊断头把**排查要问的东西一次列全**，不用再来回问一轮：

```text
━━━━━━━ 澎湃SkipAd 诊断报告 ━━━━━━━
导出时间 / 应用版本 / 包名 / 系统(Android + API) / 机型 / 构建号 / 无障碍状态 / 会话状态
本次结果：成功 N · 失败 M（共 X 项）
失败明细：· 任务标题：失败原因
────────── 完整日志 ──────────
2026-10-10 23:23:02  ──────── 应用启动 ────────
2026-10-10 23:23:07  [失败] 智能服务 · 通知与高级权限：第 2 步「点「我的」」等待超时
```

⚠️ 文件里的行格式与界面**故意不同**：带**完整日期**（导出内容会跨好几天）、
成功/失败用**文字标记**（颜色在纯文本里会丢）。
⚠️ 诊断头**不采集**账号，也**不列**用户装了哪些应用 —— 那属于隐私，排查本应用的问题用不到。

⚠️ 落盘入口有两处，缺一不可：`MainActivity.onCreate` 与
`AdSkipAccessibilityService.onServiceConnected`（进程可能是被无障碍服务拉起来的，
那时界面从没打开过）。

### 7.2 失败时的页面快照

任何一步失败，日志会自动追加 `↳ 页面快照（失败时刻）：` + 当时界面上所有有信息的节点（text/desc/id/类名/坐标，上限 40 个）。**先看这个，再怀疑代码。**

### 7.3 用 adb 复现/验证（调试期很好用）

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

# 看权限状态
& $adb shell appops get org.dpdns.mfsky.skipad
& $adb shell settings get secure enabled_accessibility_services

# dump 当前界面（注意：自绘列表可能 dump 不到子节点）
& $adb shell uiautomator dump /sdcard/x.xml
& $adb pull /sdcard/x.xml "$env:TEMP\x.xml"

# 坐标点击（系统级，安全中心也点得动）
& $adb shell input tap <x> <y>

# ⚠️ 坑：PowerShell 里 [int]$a + [int]$b 要显式转换，
#    否则字符串拼接，坐标会变成 135300 这种天文数字
```

`adb shell input text` **只支持 ASCII**（打不出「广告」）——这正是本项目必须依赖无障碍的原因之一。

### 7.4 常见现象对照

| 现象 | 优先怀疑 |
|---|---|
| 卡在某一步超时 | 看失败快照：锚点文字变了 / 页面没到 / 控件在别的包 |
| "点了没反应然后卡住" | 节点还在异步渲染 → 加大稳定性确认时间；或该控件只认系统级触摸 |
| 界面显示"已切回本应用"但实际没回 | 旧版本会骗日志，现在会验证前台包名；看是不是"借助返回键"那条 |
| 处理完停在系统页面不回来 | 「后台弹出界面」没开 → 引导用户去开（主页/设置页都有入口）|

---

## 八、环境与构建

| 项 | 值 |
|---|---|
| 语言/UI | Kotlin + Jetpack Compose（Material 3 Expressive）|
| minSdk / targetSdk | 33 / 37 |
| compileSdk | **37（minorApiLevel = 2）** —— 需要装对应版本的 SDK Platform |
| Java 源码级别 | 21 |
| Gradle daemon JDK | **25**（`gradle/gradle-daemon-jvm.properties`，由 foojay 自动下载）|
| AGP / Kotlin | 9.5.0-alpha07 / 2.4.21-RC |
| Gradle | 9.8.0（wrapper 管理）|
| 依赖管理 | `gradle/libs.versions.toml` |

```powershell
.\gradlew.bat :app:assembleDebug     # 编译
.\gradlew.bat :app:installDebug      # 装到设备
```

### 8.1 新机器拉取后的起步清单（第一次跑必读）

仓库里**已包含** `gradle/wrapper/gradle-wrapper.jar`、`gradlew(.bat)`、
`libs.versions.toml`、`settings.gradle.kts` 等全部构建脚本，**拉下来直接就能构建**。
需要注意的只有下面三件事：

**① `local.properties` 不在仓库里（这是故意的）**

它记录的是**本机 SDK 路径**，每台机器都不同，已被 `.gitignore` 排除。

- **用 Android Studio 打开项目** → AS 会**自动生成**，不用管。
- **纯命令行构建** → 会报 `SDK location not found`，此时二选一：
  - 自己建一个 `local.properties`，内容一行：
    `sdk.dir=C\:\\Users\\<你的用户名>\\AppData\\Local\\Android\\Sdk`
    （冒号和反斜杠都要转义；斜杠方向也可写成 `C:/用户目录/...`）
  - 或设环境变量 `ANDROID_HOME` 指向 SDK 目录。

**② SDK Platform 37（minorApiLevel 2）必须存在**

`compileSdk` 用得很新（37 + minor 2）。新机器如果没装过，AS 会提示下载；
命令行可先跑一次 `sdkmanager "platforms;android-37"` 之类。
**这是最可能在"换台电脑"时卡住的地方** —— 报 `Failed to find target with hash string 'android-37'` 就是它。

**③ JDK 不用手动装**

`gradle/gradle-daemon-jvm.properties` 声明了 `toolchainVersion=25` 并带上
foojay 各平台下载地址，Gradle 会**自己下载**对应 JDK。
前提是能访问 `api.foojay.io`（网络不好时可能超时，那就本地装个 JDK 25 或让 AS 用它自带的）。
`gradle.properties` 里没有指定 `org.gradle.java.home`，所以不锁机器路径 —— 这点是刻意的。

### 8.2 一句话结论

> **代码和构建脚本是完整的，拉下来就能编译。**
> 唯一要现场处理的是 ① `local.properties`（AS 会自动搞定）和
> ② 本机是否装了 API 37 的 SDK 平台。除此之外不需要任何手工配置。

### 8.3 发布与加固（R8 / 签名）

**现状（2026-10-10 起）**：release 已打开 R8（代码收缩 + 混淆 + 优化）与资源收缩，
规则在 `app/proguard-rules.pro`；实测产物 **`澎湃SkipAd_release_1.0.apk` = 2.57 MB**，
真机冒烟（启动 / 导航 / DataStore 持久化 / 诊断日志 / 无障碍绑定）全部正常。

**开关在哪、语义是什么（AGP 9.5 新 DSL，别沿用旧写法）**：

```kotlin
release {
    optimization {
        enable = true                        // = 旧 isMinifyEnabled，默认 false
        packageScope = setOf("**")           // 参与优化的包，**默认就是 "**"**
        keepRules { includeDefault = true }  // 等价于 proguard-android-optimize.txt
    }
    isShrinkResources = true
    proguardFiles("proguard-rules.pro")
    // 签名：RELEASE_* 四个键齐了才挂（见下）
}
```

⚠️ **`packageScope` 千万别改小**：它一度被写成
`setOf("androidx.**", "kotlin.**", "kotlinx.**")` —— 那是"**只**优化这三个包"，
结果是**本项目自己的代码反而不混淆**，与"上 R8 加密"的目的正好相反。
一眼验证：`mapping.txt` 里 `AutomationRunner ->?`，右边是短名（如 `zb`）才对。

**必须保留的两条（都有代码依据，不是保险起见）**：

| 保留 | 依据 | 不写会怎样 |
|---|---|---|
| `-keep class rikka.shizuku.** { *; }` | `util/ShizukuShell.kt` 反射调用 **private** 的 `Shizuku.newProcess` | 「点一下直接开无障碍」**静默失效**（异常被 `runCatching` 吃掉） |
| `$$serializer` / `Companion` / `serializer(...)` | `ui/navigation/TopLevelDestination.kt` 的 `@Serializable data object *Key : NavKey` | 配置变更 / 进程被杀后**返回栈恢复不了**，且只在运行期暴露 |

**不需要写规则**：Compose / Navigation / DataStore / AndroidX 都有自带 consumer 规则；
无障碍服务与 `MainActivity` 由 manifest 引用、AGP 自动保名（另加一条 `-keepnames`
作零成本保险，见 `proguard-rules.pro` ③）。

**怎么验证（不用装包）**：`app/build/outputs/mapping/release/` 下三份报告 ——
`seeds.txt`（被保下来的，**该**在里面）、`usage.txt`（被删掉的，**不该**在里面）、
`mapping.txt`（反解线上堆栈）。
⚠️ **改完规则必须打 release 并真机启动一次**：debug 不混淆，这类问题**只有 release 才暴露**
（典型例子：引入 Room / WorkManager 后第一次打 release，崩在生成类的构造器上）。

**签名与密钥纪律（⚠️ 正式上线前再做，现在不阻塞开发）**：

> **现状**：仓库里**没有** keystore，也没配口令 —— 所以现在 `assembleRelease` 出的是
> **未签名包**（能编译、能用 `apksigner` 临时签了本地冒烟，但**不能对外分发**）。
> 决定正式上线时，按下面三步做，十分钟的事。

**第 ① 步 · 生成 keystore**（放**仓库外**，比如 `%USERPROFILE%\keys\`）：

```powershell
mkdir "$env:USERPROFILE\keys" -Force
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v `
  -keystore "$env:USERPROFILE\keys\skipad.jks" `
  -alias skipad -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
```

- 别名用 `skipad`；会提示输两次口令（keystore 口令与 key 口令**设成同一个**最省事）；
- `-validity 10000` ≈ 27 年 —— **别用默认的 90 天**，否则将来"签名过期"很麻烦；
- 其余姓名/组织信息随便填，不影响使用。

**第 ② 步 · 口令写进本机属性**（`~\.gradle\gradle.properties`，**不是**项目里的那份）：

```properties
RELEASE_STORE_FILE=C:/Users/<你的用户名>/keys/skipad.jks
RELEASE_STORE_PASSWORD=自己定的口令
RELEASE_KEY_ALIAS=skipad
RELEASE_KEY_PASSWORD=自己定的口令
```

写完之后 `.\gradlew.bat :app:assembleRelease` 出来的就是**已签名**包（四个键齐了才挂签名，
这是 `app/build.gradle.kts` 里的软开关）。也可以用环境变量（CI 场景）。

**第 ③ 步 · 立刻备份 + 验签**：

```powershell
# 验签：应打印出 Signer #1 certificate DN 与 SHA-256
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.1.0\apksigner.bat" verify --print-certs `
  "app\build\outputs\apk\release\澎湃SkipAd_release_1.0.apk"
```

- keystore **备份到仓库之外的两处**（如移动硬盘 + 私有云盘），**口令与文件分开存**；
- ⚠️ 两个不可逆：**丢了** → 再也发不了覆盖升级包（只能让用户卸载重装）；
  **泄露了** → 别人能用你的名义发"升级包"。所以它既不能进仓库，也不能只存一份。
- `.gitignore` 已排除 `*.jks` / `*.keystore` / `keystore.properties`（防手滑）。

**发布前检查单（按优先级）**：

1. **keystore**：⚠️ **正式上线前再做，现在不阻塞**（生成 + 配口令 + 备份的完整步骤
   见上面的「签名与密钥纪律」）。现在没有它也能编译，只是出未签名包。
2. **`appVersionCode` 每次发版 +1**（Android 靠它判断能否覆盖升级），`versionName` 语义化；
   两者只在 `app/build.gradle.kts` 顶部一处（APK 命名也用同一个值）。
3. **debug 隔离**（已做）：`applicationIdSuffix = ".debug"`，否则调试包会**覆盖正式包**
   （同一 `applicationId`，用户数据一起被换）。⚠️ 因此**首次**用新配置跑 debug 时，
   手机上那个老包（id 无后缀）会与它并存，老包可以卸载掉。
   ⚠️ 后缀会把**写死的包名**暴露成"假成功"（已修，记下来免得重犯）：
   `PermissionTasks.PKG` 原本是编译期常量 `"org.dpdns.mfsky.skipad"`，于是 debug 包里
   「自动获取权限」会去开 **release 包**的应用信息页 —— 页面照常打开、看着像成功，
   授权却授到了另一个包上（**典型的假成功**，符合坑 #26 那一族）。
   现在改用**占位符** `AutomationStep.SELF_PACKAGE`（`%SELF%`），由引擎在启动时
   替换成 `service.packageName`，`data` / `component` / `appPackage` 三处都支持。
   同类问题还有一处，已一起改掉：`AdSkipPermission.serviceComponent(context)`
   （组件名里的包名不再写死）。
   ✅ 真机验证方式（行为证据比日志可靠）：在 debug 包里走一次「自动获取」，然后
   ```powershell
   adb shell appops get org.dpdns.mfsky.skipad.debug SYSTEM_ALERT_WINDOW   # 刚被授（time=+Ns ago）
   adb shell appops get org.dpdns.mfsky.skipad SYSTEM_ALERT_WINDOW         # release 包没被碰
   ```
4. **打包后真机跑一遍**：启动 → 三个页面各切一次 → 真跑 1 条任务。
5. **归档 `mapping.txt`**：每次发版一起留档（仓库外；若要公开，注意别把源码结构一起送出去）——
   混淆后线上崩溃堆栈**只能靠它**反解。
6. **日志策略**：当前 release **保留日志**（本项目排查就靠 logcat + 处理记录）。
   若要"发布包不带日志"，**另开一个 buildType**，别直接把 `Log.d` 裁掉。
7. **崩溃可见性**：混淆后用户报"闪退"往往给不出堆栈 → 可把"崩溃落盘 + 一键复制"
   做进应用（本项目的「处理记录 + 复制」已经是雏形，扩一下即可）。
8. **`.gitignore` 复查**（已齐）：`*.jks` / `*.keystore` / `keystore.properties` /
   `local.properties` / 本地参考资料目录。
9. **发版时同步文档**：本节 +「五、当前进度」。
10. **可选**：`-repackageclasses` / 字符串加密 / 字典混淆（收益有限、release 风险上升，
    且开了必须保留无障碍服务类名）；baseline profile（AGP 9 里挂在
    `buildType.baselineProfile {}`）；CI。



---

## 九、给 AI 的一句话交接

> 引擎已经稳定（四层冗余 + 每一步的验证与快照），**不要再重构引擎**。
> 加功能就在 `BuiltinAdTasks.kt` 里按模板追加任务；
> 遇到"点了没反应"，先读第四节的血泪清单，再看失败快照，**大概率是 MIUI 的反自动化或时序问题，不是逻辑 bug**。
