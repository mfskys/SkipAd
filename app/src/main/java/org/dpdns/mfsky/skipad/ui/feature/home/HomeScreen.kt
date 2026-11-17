package org.dpdns.mfsky.skipad.ui.feature.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.roundedfilled.Apps
import com.composables.icons.materialsymbols.roundedfilled.Check_circle
import com.composables.icons.materialsymbols.roundedfilled.Download
import com.composables.icons.materialsymbols.roundedfilled.Layers
import com.composables.icons.materialsymbols.roundedfilled.Search
import com.composables.icons.materialsymbols.roundedfilled.Verified_user
import kotlinx.coroutines.launch
import org.dpdns.mfsky.skipad.adskip.AdSkipAccessibilityService
import org.dpdns.mfsky.skipad.adskip.AdSkipPermission
import org.dpdns.mfsky.skipad.adskip.AdSkipRuntime
import org.dpdns.mfsky.skipad.adskip.BuiltinAdTasks
import org.dpdns.mfsky.skipad.adskip.SkipLogEntry
import org.dpdns.mfsky.skipad.adskip.SkipProgressRepository
import org.dpdns.mfsky.skipad.adskip.SkipSession
import org.dpdns.mfsky.skipad.adskip.TaskOutcome
import org.dpdns.mfsky.skipad.adskip.usableAdTasks
import org.dpdns.mfsky.skipad.ui.adaptive.AppAdaptiveContent
import org.dpdns.mfsky.skipad.ui.design.component.AppPageScaffold
import org.dpdns.mfsky.skipad.ui.design.component.LocalSegmentedListItemShapes
import org.dpdns.mfsky.skipad.ui.design.component.SettingItem
import org.dpdns.mfsky.skipad.ui.design.component.rememberAppListAllowed
import org.dpdns.mfsky.skipad.ui.design.component.rememberBackgroundStartPermission
import org.dpdns.mfsky.skipad.ui.design.component.rememberAccessibilityEnabled
import org.dpdns.mfsky.skipad.ui.design.component.rememberInstallAllowed
import org.dpdns.mfsky.skipad.ui.design.component.rememberOverlayAllowed
import org.dpdns.mfsky.skipad.ui.design.component.rememberShizukuAccessibilityOpener
import org.dpdns.mfsky.skipad.ui.design.theme.appCardColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主页 —— **一次性处理工具**的操作台。
 *
 * ## 这个界面的核心语义
 *
 * 它不是"拦截器开关面板"，而是"开始/结束一次自动处理"：
 *
 * ```
 * 权限没齐      →  （不给按钮）                （先按下面的流程把权限备齐）
 * 权限齐、未开始 →  [开始处理]                （★ 不点它，引擎绝不动屏幕）
 * 自动获取权限中 →  "正在自动获取权限" + 当前步骤 + [停止]
 * 处理中        →  第 N/M 项 + 当前步骤 + [停止]
 * 已完成        →  成功/失败汇总 + [关闭无障碍] [重新处理]
 * ```
 *
 * ⚠️ **打开无障碍后不会自动开始**。无障碍服务可以一直连着，
 * 但在用户点「开始处理」之前，引擎连节点树都不会去读。
 * 因为关掉的内置广告开关是**持久设置**，一次跑完就永久生效，
 * 没必要（也不应该）常驻监视。
 *
 * ## 页面分区（自上而下）
 *
 * | 区块 | 内容 | 何时出现 |
 * | --- | --- | --- |
 * | **状态卡** | 唯一的操作入口（开始 / 继续 / 停止 / 关闭无障碍 / 重新处理全部） | 恒常 |
 * | **权限准备** | 获取四项权限的**流程引导**（第 1 步 → 第 4 步） | **仅权限不全时** |
 * | **处理记录** | 完整日志 + 复制 / 清空 —— **全应用唯一展示日志的地方** | **仅有日志时** |
 * | **处理清单** | 内置脚本内容 + 进度；结果**直接挂在对应那一行上**（徽章 + 描述） | 恒常 |
 *
 * ## 「继续处理」
 *
 * 状态卡的按钮有**三种语义**，由历史进度决定：
 *
 * | 历史进度 | 标题 | 按钮 |
 * | --- | --- | --- |
 * | 一项都没跑过 | 准备就绪 | 开始处理 |
 * | 跑过一部分 | 可以继续处理 | **继续处理（剩 N 项）** ＋ 重新处理全部 |
 * | 全部跑完 | 全部已处理 | **重新处理全部**（会先清空进度再跑） |
 *
 * ⚠️ 「跑过一部分」时**两个入口都要给**：主按钮是「继续处理」（只跑剩下的），
 * 但用户可能想**从头再跑一遍**（比如上次中途出错、或系统升级后想重新确认），
 * 这时没有入口就无路可走。清进度那个走 `TextButton`，
 * 与「处理完成」那档保持一致的层级关系。
 *
 * 已完成的任务是**整项被跳过**的（见 `AdSkipRuleEngine.startSession`），
 * 不是靠每一步的幂等"走个过场" —— 连页面都不会打开。
 * 进度持久化在 `SkipProgressRepository`，所以中断（停止 / 服务断开 / 进程被杀）
 * 之后重进应用，剩下的项接着跑。
 *
 * ⚠️ 其中两块是**条件出现**的（缺权限才提示、有日志才显示记录）——
 * 这是刻意的"渐进披露"：页面默认只有状态卡 + 清单两块，
 * 用户不必被已经配好的信息或空记录占位。
 *
 * ⚠️ 权限块放在状态卡**之后**：状态卡是主操作，必须先被看到；
 * 权限是它的前提，缺了紧随其后提示、一步到位。都配好后它自动消失 ——
 * 完整状态在**设置页**常驻，主页不重复。
 *
 * ## ⚠️ 权限没齐之前，「开始处理」是**不给**的
 *
 * 不是 disabled，而是**根本不渲染**那个按钮 —— 缺权限时任何一项都跑不动
 * （整套自动化本身就建立在无障碍之上），放个按不动的按钮只会让用户反复点、
 * 以为程序坏了。状态卡这时改说"需要先完成权限准备 / 下面还有 N 步要做"，
 * 把视线交给紧邻的下方一块。
 *
 * 这也是主页从"还需要开启（一张待办清单）"改为**流程引导**的原因：
 * 清单只回答"缺什么"，流程还要回答"**先做哪个**、**哪些能自动做**"。
 *
 * ## 样式纪律（MD3E 官方）
 *
 * - **不做任何颜色硬编码**：状态卡用 `primaryContainer` / `primary` / `errorContainer` /
 *   `surfaceContainerHighest` 这组官方 token 表达"进行中 / 全部成功 / 有失败 / 待处理"，
 *   深浅色都由主题负责。
 * - **一次只给一个主操作**：按钮随状态切换，用户永远不需要"猜该点哪个"。
 * - 卡片容器一律走 `Card` 的默认色，页面底保持官方 `background`。
 * - 水平内边距**由各区块自己给**：所以这里 `contentPadding` **只设上下**。
 * - 页面级瞬时反馈用 **Snackbar**（经 `snackbarHost`），**不用 `Toast`** ——
 *   官方组件清单里没有 Toast。
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val session by AdSkipRuntime.session.collectAsState()
    val outcomes by AdSkipRuntime.outcomes.collectAsState()
    val currentTaskIndex by AdSkipRuntime.currentTaskIndex.collectAsState()
    val currentStepLabel by AdSkipRuntime.currentStepLabel.collectAsState()
    val currentGuide by AdSkipRuntime.currentGuide.collectAsState()
    val logEntries by AdSkipRuntime.entries.collectAsState()
    val doneTaskIds by AdSkipRuntime.doneTaskIds.collectAsState()

    // ⚠️ 带 `refreshKey`：用 Shizuku 直接打开无障碍时（见 openAccessibility），
    //    用户**没离开过本应用** —— `ON_RESUME` 不会来，状态得手动刷一次。
    var accessibilityRefreshKey by remember { mutableIntStateOf(0) }
    val accessibilityEnabled = rememberAccessibilityEnabled(accessibilityRefreshKey)
    // 「读取应用列表」—— 判断某个预装应用装没装时要用它，
    // 见 AdSkipPermission.isAppListAllowed（判据是系统的 QUERY_ALL_PACKAGES）。
    val appListAllowed = rememberAppListAllowed()
    val backgroundStartAllowed = rememberBackgroundStartPermission()
    // 「显示在其他应用上层」—— 自动化期间靠一个 1×1 透明悬浮窗保活，
    // 否则厂商 ROM 会在后台把整个进程冻住（实测，详见 rememberOverlayAllowed 的 KDoc）。
    val overlayAllowed = rememberOverlayAllowed()
    // 「安装未知应用」—— 只有「应用包管理组件」那一项需要它，但它同样进
    // "权限准备"流程：这里的目标是"一次把前置条件备齐"而不是"最小可用"，
    // 少一项就有一项任务跑不动，不如一并办掉（而且这三项本来就是同一条路径）。
    val installAllowed = rememberInstallAllowed()

    val permissionSession by AdSkipRuntime.permissionSession.collectAsState()

    // 页面级瞬时反馈。官方规范里这类反馈用 **Snackbar**（不是 Toast）：
    // 由 Scaffold 承载、贴在底部，自动排队，且不遮挡正在看的内容。
    //
    // ⚠️ 声明在权限步骤**之前**：下面那条"点一下用 Shizuku 开无障碍"的分流
    //    要用它们讲清"没有 Shizuku / 授权被拒"。
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 「无障碍服务」那一步的动作：本机有可用 Shizuku 就直接开，
    // 否则（没装 / 没运行 / 授权被拒）自动降级为打开系统无障碍设置页 —— 与老行为一致。
    //
    // ⚠️ 成功后必须把 `accessibilityEnabled` **手动刷一次**（key 自增）：
    //    这条路不离开本应用，`ON_RESUME` 永远不会触发；不刷的话界面一直显示"未开启"，
    //    而下面那段"无障碍刚开启 → 自动接上第 2~4 步"的 LaunchedEffect 也就不会触发。
    val openAccessibility = rememberShizukuAccessibilityOpener(
        onEnabled = { accessibilityRefreshKey++ },
        onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
        onFallback = { AdSkipPermission.openSettings(context) },
    )

    // 权限准备的 5 步。顺序写在这里（而不是从别处导出）是因为它**同时**决定
    // 界面顺序和"哪些能自动完成"的判断，拆成两处只会让两者慢慢走偏。
    //
    // ⚠️ 中间那三步的顺序**跟着系统页上的顺序**走：MIUI 的「其他权限」页里
    //    「获取应用列表」在「后台弹出界面」「显示悬浮窗」之前，脚本也按同一顺序
    //    处理。两边顺序不一致的话，用户对照着看会觉得"怎么倒着来的"。
    val permissionSteps = listOf(
        PermissionStepUi(
            title = "无障碍服务",
            description = "自动操作依赖它",
            icon = MaterialSymbols.RoundedFilled.Verified_user,
            granted = accessibilityEnabled,
            actionLabel = "去开启无障碍",
            // ⚠️ 不再无条件跳系统设置：本机有可用 Shizuku 时**直接开**
            //（首次会弹 Shizuku 的授权框），开完状态一刷，下面"自动接上第 2~4 步"
            // 立刻接管 —— 用户一个开关都不用拨。没有 Shizuku 时行为与之前完全一致。
            // 见 rememberShizukuAccessibilityOpener 的三分支说明。
            onClick = openAccessibility,
        ),
        PermissionStepUi(
            title = "获取应用列表",
            // ⚠️ 必须说清"为什么"：一个关广告的应用要读应用列表，不说用途很可疑。
            //    真实用途是判断天气 / 日历 / 画报这些应用**还在不在**，
            //    没装的直接跳过，而不是白跑一遍再报失败。
            description = "认出没安装的应用",
            icon = MaterialSymbols.RoundedFilled.Search,
            granted = appListAllowed,
            actionLabel = "自动获取",
            onClick = { AdSkipAccessibilityService.startPermissionSession() },
        ),
        PermissionStepUi(
            title = "后台弹出界面",
            description = "处理完自动返回",
            icon = MaterialSymbols.RoundedFilled.Apps,
            granted = backgroundStartAllowed,
            actionLabel = "自动获取",
            onClick = { AdSkipAccessibilityService.startPermissionSession() },
        ),
        PermissionStepUi(
            title = "显示悬浮窗",
            // ⚠️ 必须说清"为什么"：它申请的其实是**悬浮窗**权限，不说用途的话
            //    用户会以为本应用要弹窗打扰自己。真实用途是自动化期间挂一个
            //    1×1 的透明窗口，避免进程被厂商 ROM 冻住。
            description = "防止处理被系统冻结",
            icon = MaterialSymbols.RoundedFilled.Layers,
            granted = overlayAllowed,
            actionLabel = "自动获取",
            onClick = { AdSkipAccessibilityService.startPermissionSession() },
        ),
        PermissionStepUi(
            title = "允许安装应用",
            description = "「应用包管理组件」需要",
            icon = MaterialSymbols.RoundedFilled.Download,
            granted = installAllowed,
            actionLabel = "去开启",
            // ⚠️ 这一步只能**手动**：那一页的开关压根不在无障碍树里
            //（ROM 把装载它的列表项标成了 `noHideDescendants`），读不到就点不到。
            // 所以走"跳过去 + 在那边显示提示 + 用户打开后自动返回"这条路，
            // 见 AdSkipAccessibilityService.startInstallGrantWatch。
            onClick = { AdSkipAccessibilityService.startInstallGrantWatch() },
        ),
    )

    // "还差几步"正是主页要回答的核心问题 —— 一次算清，状态卡与卡片共用同一个结果。
    val grantedPermissionCount = permissionSteps.count { it.granted }
    val missingPermissionCount = permissionSteps.size - grantedPermissionCount
    val permissionsReady = missingPermissionCount == 0

    // ── 第 1 步刚做完（无障碍从未开启变为已开启）→ 剩下的**自动接上** ──
    //
    // 用户刚在系统设置里拨完那个开关、回到本应用，此刻正是一次**前台交互的延续**；
    // 从这里发起的页面跳转不会被"后台启动限制"拦掉（见 startPermissionSession 的说明）。
    // 所以这一刻正是自动接着做第 2~4 步的时机 —— 用户不必再点任何东西。
    //
    // ⚠️ 只在"未开启 → 已开启"这一次**翻转**上触发。若写成"无障碍已开且权限没齐
    //    就启动"，那每次回到主页、每次启动应用（权限本来就开着）都会被重新拉起一遍。
    var lastAccessibilityEnabled by remember { mutableStateOf(accessibilityEnabled) }
    LaunchedEffect(accessibilityEnabled) {
        val justGranted = accessibilityEnabled && !lastAccessibilityEnabled
        lastAccessibilityEnabled = accessibilityEnabled
        if (justGranted && !permissionsReady) {
            AdSkipAccessibilityService.startPermissionSession()
        }
    }

    // 本机**实际能处理**的任务 —— 依赖的应用没装 / 被停用的项不在其中。
    //
    // `remember(context)` 是必要的：这里要查 `packageManager`，
    // 而本函数每次重组都会跑（进度、日志、会话状态一变就重组）。
    // 包信息在应用生命周期内不会变，缓存住即可。
    val availableTasks = remember(context) { usableAdTasks(context) }
    val availableTaskIds = remember(availableTasks) { availableTasks.mapTo(mutableSetOf()) { it.id } }

    // ⚠️ 分母用**可用项数**而不是 `BuiltinAdTasks.size`。
    //    否则用户卸载了天气，进度环就永远停在 6/7、永远到不了"全部完成"。
    val total = availableTasks.size

    // 还有多少项**从没处理过** —— 这就是「继续处理」要跑的数量。
    // 从 doneTaskIds 现算而不是读一个普通 val：它是 StateFlow，
    // 进度一变这里就跟着重组。
    val pendingCount = availableTasks.count { it.id !in doneTaskIds }
    val allDone = pendingCount == 0

    // 已关闭的项数（跨会话累积）—— 概览卡那个进度环的分子。
    // 同样从 StateFlow 现算：处理完一项它会立刻反映到环上。
    val closedCount = total - pendingCount

    val okCount = outcomes.count { it.success }
    val failCount = outcomes.count { !it.success }

    // ── 清单行的**数据模型**：先把"每一行长什么样"算成一份稳定数据 ──
    //
    // ⚠️ 这一步是**性能修复**，不是整理代码。原来那 15 行是在 `LazyColumn` 的
    //    **一个 item** 里现算现组合的，于是：
    //      · 那一整块必须在**同一帧**里组合完 → 滑进视口时掉帧；
    //      · 顶栏是 `exitUntilCollapsed`（随滚动折叠），滚动期间 Scaffold 的内容槽
    //        **每一帧都会被重新调用**，而那一块的入参里有 `List`/`Set`（Compose 眼里
    //        不稳定）→ 整块 15 行**每帧重组**。
    //    拆成"每行一个 item + 只吃稳定字段"之后，行能被跳过，滚动时不再有这笔开销。
    //    详见 [TaskRowUi]。
    val taskRows = remember(
        outcomes,
        doneTaskIds,
        availableTasks,
        availableTaskIds,
        session,
        currentTaskIndex,
        permissionsReady,
    ) {
        buildList {
            BuiltinAdTasks.forEach { task ->
                val outcome = outcomes.firstOrNull { it.taskId == task.id }
                // 上次（或更早）已经处理成功过 —— 这就是「继续处理」会跳过的那些。
                val doneBefore = task.id in doneTaskIds
                // 这一项依赖的应用在这台机器上不存在（或已被停用）。
                val unavailable = task.id !in availableTaskIds
                // 正在处理的是第 currentTaskIndex 项（从 1 开始）。它还没进 outcomes，
                // 所以要用这个条件单独标记 —— 否则"处理中"和"还没轮到"外观完全一样。
                //
                // ⚠️ 序号要在**可用列表**里数，不能用清单里的 index：引擎的队列是
                //    过滤后的，两边用同一个序号才对得上（卸载过应用的机器上会标错行）。
                val queueIndex = availableTasks.indexOfFirst { it.id == task.id }
                val inProgress = session == SkipSession.Running &&
                    queueIndex >= 0 &&
                    currentTaskIndex == queueIndex + 1 &&
                    outcome == null

                add(
                    TaskRowUi(
                        id = task.id,
                        title = task.title,
                        // 只给"路径"这一条信息：未安装时说明原因；成功/失败时
                        // outcome.detail 覆盖它；其余一律显示 guide（用户能照着走）。
                        description = if (unavailable) {
                            "本机没有这个应用，已跳过"
                        } else {
                            outcome?.detail ?: task.guide
                        },
                        statusText = when {
                            unavailable -> "未安装"
                            outcome?.success == true -> "已完成"
                            outcome != null -> "失败"
                            inProgress -> "处理中"
                            doneBefore -> "已完成"
                            else -> null
                        },
                        // 只有"确实做成了"才算正面状态："未安装"不能给对勾，
                        // 那会让用户以为这一项已经处理好了。
                        statusPositive = outcome?.success == true ||
                            (outcome == null && doneBefore),
                        // 三种情况都不给点：应用没装 / 已有会话在跑 / 权限没齐。
                        clickable = !unavailable &&
                            session != SkipSession.Running &&
                            permissionsReady,
                    ),
                )
            }
        }
    }

    // 点击某一行的**稳定回调**：只吃 id，不捕获 `AdTask`（它带 List，属不稳定类型）——
    // 捕获不稳定对象会让整行无法被跳过，又退回"每帧重组"。
    val onTaskClick: (String) -> Unit = remember {
        { taskId -> AdSkipAccessibilityService.startSingleTask(taskId) }
    }

    AppPageScaffold(
        title = "澎湃SkipAd",
        subtitle = "澎湃First扩展小工具",
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        // 平板/桌面窗口下限宽居中（840dp），手机上等价 fillMaxSize
        AppAdaptiveContent {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // ⚠️ 只设上下：水平内边距由各区块自己给（见 KDoc 的样式纪律）。
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                // ── 状态卡：唯一的操作入口 ──
                item {
                    StatusCard(
                        session = session,
                        permissionsReady = permissionsReady,
                        missingPermissionCount = missingPermissionCount,
                        permissionSession = permissionSession,
                        closedCount = closedCount,
                        okCount = okCount,
                        failCount = failCount,
                        total = total,
                        pendingCount = pendingCount,
                        currentTaskIndex = currentTaskIndex,
                        currentStepLabel = currentStepLabel,
                        currentGuide = currentGuide,
                        onOpenAccessibilitySettings = { AdSkipPermission.openSettings(context) },
                        onStart = { AdSkipAccessibilityService.startSession() },
                        onResetAndStart = {
                            scope.launch {
                                // ⚠️ 顺序要紧：先落盘清空、再清内存缓存、最后才启动。
                                //    引擎读的是内存里的 doneTaskIds —— 不先清空的话，
                                //    「重新处理全部」会因为没有待处理项而被直接跳过。
                                SkipProgressRepository.clear(context)
                                AdSkipRuntime.setDoneTaskIds(emptySet())
                                AdSkipAccessibilityService.startSession()
                            }
                        },
                        onStop = { AdSkipAccessibilityService.stopSession() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                // ── 权限准备：**一个卡片、里面一步一步**，未完成时出现 ──
                //
                // 这一块只回答一个问题："要开始处理，还差哪几步？"
                // 所以它是**步骤流**（第 1 步 → 第 4 步），而不是权限列表 ——
                // 权限的完整状态与入口在**设置页**有常驻的一份（那里才是"看设置"的地方）。
                //
                // ⚠️ 顺序是有讲究的：
                //    · 第 1 步（无障碍）必须最先 —— 它是第 2~4 步能自动完成的**前提**；
                //    · 第 2~4 步在无障碍就绪后**自动接上**（见上面那个 LaunchedEffect），
                //      也可以在卡片里点一下手动补跑。它们本来就是同一条路径
                //      （应用信息 → 权限管理 → 其他权限），所以一次全办掉，
                //      而不是让用户点三次、看三次跳页；
                //    · 第 5 步只能**手动**：那一页的开关不在无障碍树里
                //      （详见 PermissionTasks 的类文档），所以给的是直达入口。
                if (!permissionsReady) {
                    item {
                        PermissionStepCard(
                            steps = permissionSteps,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }

                // ── 处理记录：全应用唯一展示日志的地方 ──
                if (logEntries.isNotEmpty()) {
                    item {
                        LogCard(
                            entries = logEntries,
                            onClear = { AdSkipRuntime.clearLog() },
                            // 「复制」给的是**完整日志 + 诊断头**，而不是列表里这 20 条：
                            // 用户出问题时要发出来的是一份别人能直接读懂的报告。
                            buildReport = { reportContext ->
                                buildDiagnosticsReport(reportContext, session, outcomes)
                            },
                            onCopied = { lines ->
                                scope.launch {
                                    snackbarHostState.showSnackbar("已复制完整日志（$lines 行）")
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }

                // ── 待处理清单：结果直接挂在对应行上 ──
                //
                // 没轮到的显示引导路径；已处理的行尾挂「已完成/失败」徽章、
                // 描述替换为结果详情 —— 一处看完"做了什么、成没成"。
                //
                // ⚠️ **一个任务 = 一个 item**，不要再退回"一个 item 里塞 15 行"：
                //    · 塞在一块 → 那一整块必须**在同一帧里组合完**，滑进视口时掉帧；
                //    · 顶栏随滚动折叠 → 滚动期间内容槽每帧被重新调用，
                //      只有"入参稳定的独立 item"才可能被跳过（原因见 [TaskRowUi]）。
                item(key = "task_list_header") {
                    Text(
                        text = "处理清单",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        // 与设置页分组标题内边距对齐：外层 16 + 组内 16。
                        modifier = Modifier.padding(start = 32.dp, top = 8.dp, bottom = 8.dp),
                    )
                }
                itemsIndexed(taskRows, key = { _, row -> row.id }) { index, row ->
                    // 分段形状（首/中/尾圆角）由容器下发 —— 与官方 SegmentedListItem 示例同一套实现。
                    CompositionLocalProvider(
                        LocalSegmentedListItemShapes provides
                            ListItemDefaults.segmentedShapes(
                                index = index,
                                count = taskRows.size,
                            ),
                    ) {
                        SettingItem(
                            // ⚠️ 不给图标：每行都挂同一个图标等于没有信息量。
                            title = row.title,
                            description = row.description,
                            statusText = row.statusText,
                            statusPositive = row.statusPositive,
                            // ⚠️ 这里**不要**现造捕获 `task` 的 lambda：捕获不稳定对象
                            //    会让这一行无法被跳过，又退回"每帧重组"。
                            onClick = if (row.clickable) {
                                { onTaskClick(row.id) }
                            } else {
                                null
                            },
                            modifier = Modifier.padding(
                                start = 16.dp,
                                end = 16.dp,
                                // 行间间隙用官方 token（与设置页列表同一份）；
                                // 首行不补，末行补组尾那 8dp。
                                top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap,
                                bottom = if (index == taskRows.lastIndex) 8.dp else 0.dp,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「权限准备」里的一步（纯展示数据）。
 *
 * @param granted 这一步是否已完成。卡片据此把它收成"一行对勾"，
 *   而**未完成的后续步骤根本不展示**（见 [PermissionStepCard]）。
 * @param actionLabel **当前这一步**那个按钮上的文字
 *   （"去开启" = 手动跳系统设置；"自动获取" = 交给无障碍自动点完）。
 */
private data class PermissionStepUi(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val granted: Boolean,
    val actionLabel: String,
    val onClick: () -> Unit,
)

/**
 * 「卡内区块标题」（卡片内部的分区标题，如「权限获取」「处理记录」）的内边距。
 *
 * ## 为什么抽成常量
 *
 * 官方排版规范里正有一条 best practice 对应这个问题（m3.material.io/styles/typography/
 * editorial-treatments）：
 *
 * > Ensuring consistency between similar-looking editorial moments,
 * > **creating tokens for each where needed**
 *
 * 两个表头语义相同，就不该各写各的魔数 —— 之前一张 `top=16/bottom=8`、
 * 另一张 `top=10/bottom=2`，肉眼看就是"上下两个不像同一个组件"。
 * 取值走 **8dp 栅格**（M3E 的间距系统就是 8dp 一档）。
 *
 * ## end 为什么有两份
 *
 * 目的只有一个：**视觉右边界都落在 20dp**，与卡内各行的右边界对齐。
 *
 * | 表头右侧是 | 用哪份 | 为什么 |
 * | --- | --- | --- |
 * | 纯文本（「权限获取」卡的 `4/5`） | [CardHeaderPadding] | 20dp 直接贴齐 |
 * | `TextButton`（「处理记录」卡的「复制全部」） | [CardHeaderPaddingWithActions] | 8dp + 按钮自带 12dp 内边距 = 视觉 20dp |
 *
 * ## 字阶（两条一起看，别拆开改）
 *
 * 卡内区块标题一律用 **官方 emphasized 字阶** `MaterialTheme.typography.titleSmallEmphasized`
 * （14sp，字重 Bold）、内容块标题用 `titleMediumEmphasized`（16sp，字重 Bold）——
 * ⚠️ **不要手写 `fontWeight`**：手写的 `SemiBold` 两边都不沾
 * （baseline `titleSmall` 是 Medium 500，M3E 的 emphasized 是 Bold 700），
 * 而官方给 emphasized 字阶的意义正是"要强调时用它，而不是自己调字重"
 * （`Typography` KDoc：*an emphasized version of [titleSmall]*）。
 * 层级靠**字号**区分（表头 14sp < 内容块 16sp），不靠随手加粗。
 */
private val CardHeaderPadding =
    PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)

/** 带右侧操作（`TextButton`）的表头内边距 —— 见 [CardHeaderPadding] 的说明。 */
private val CardHeaderPaddingWithActions =
    PaddingValues(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)

/**
 * 权限获取卡片 —— **一个卡片，四步都看得见，当前那一步最显眼**。
 *
 * ## 为什么四步都摆出来，而不是只显示"下一步"
 *
 * 只显示当前一步会让人**不知道还要走多远** —— 而权限准备恰恰是"要连着做几件事"
 * 的场景，用户需要先看到总量才好决定现在做不做。所以四步全列，靠**视觉权重**
 * 分出主次：当前那步占一整块高亮容器，其余压成一行。
 *
 * ## 三种状态怎么表达（全部落在官方组件的容器与色角色上）
 *
 * | 状态 | 表现 |
 * | --- | --- |
 * | 当前 | `Surface` 高亮块（`primaryContainer`）+ 说明 + 整宽按钮 |
 * | 已完成 | 一行文字 + `Check_circle`（`primary`） |
 * | 还没轮到 | 一行文字 + 功能图标，统一压到 `onSurfaceVariant` |
 *
 * ⚠️ 标题**不带**「第 N 步」：这四行本来就按顺序自上而下排，
 * 序号只会让每行更长，却不能提供"顺序"之外的任何信息。
 *
 * @param steps 顺序即显示顺序。
 */
@Composable
private fun PermissionStepCard(
    steps: List<PermissionStepUi>,
    modifier: Modifier = Modifier,
) {
    val grantedCount = steps.count { it.granted }
    // 当前这一步 = 第一个还没完成的。它之后的步骤照常展示，但没有操作入口。
    val currentIndex = steps.indexOfFirst { !it.granted }
    val haptic = LocalHapticFeedback.current

    // 卡片容器色统一走 [appCardColors]（与列表行、导航栏同档）—— 见该函数说明。
    Card(modifier = modifier, colors = appCardColors()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 标题行：左标题、右进度。官方 lists 规范里列表没有 header 组件，
            // 这属于应用层排版 —— 与设置页分组标题用同一套字色。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(CardHeaderPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "权限获取",
                    // 与「处理记录」卡的表头同一份定义（字阶 + 内边距）—— 见 [CardHeaderPadding]。
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "$grantedCount/${steps.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            steps.forEach { step ->
                if (step === steps.getOrNull(currentIndex)) {
                    // ── 当前这一步：高亮容器 + 整宽按钮 —— 全卡唯一的操作区 ──
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            // ⚠️ 水平外边距必须与卡内其它内容一致（**20dp**：表头、已完成行、
                            //    日志行、任务行都是 20）。曾写成 16dp —— 这张"当前步骤"块的
                            //    左边缘比它上下的两行**偏出 4dp**，肉眼就是"没对齐"。
                            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = step.title,
                                // 内容块的标题 = 表头的**下一级**：同样是官方 emphasized 字阶，
                                // 层级靠字号区分（表头 14sp / 这里 16sp），不靠随手加粗。
                                style = MaterialTheme.typography.titleMediumEmphasized,
                            )
                            Text(
                                text = step.description,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.size(12.dp))
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    step.onClick()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(step.actionLabel)
                            }
                        }
                    }
                } else {
                    // ── 已完成 / 还没轮到：压成一行，只留图标 + 标题 ──
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = if (step.granted) {
                                MaterialSymbols.RoundedFilled.Check_circle
                            } else {
                                step.icon
                            },
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (step.granted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                // 还没轮到的步骤统一退到次要色 —— 它是"待办"，不是"结果"。
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            text = step.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 底部留白，别让最后一行贴着卡片圆角。
            Spacer(modifier = Modifier.size(8.dp))
        }
    }
}

/**
 * 顶部概览卡 —— 主页的**进度概览 + 主操作区**。
 *
 * ## 布局
 *
 * 左边是官方的**圆形波浪进度指示器**（M3E 的 `CircularWavyProgressIndicator`），
 * 环心压一个"已关闭 / 总数"；右边是标题与一句话状态；最下面是当前可用的操作。
 * 扫一眼就知道"一共几项、关了几项、现在该干什么"。
 *
 * ## 配色刻意从简
 *
 * 卡片一律用**中性容器**（`surfaceContainerHighest`），把"状态"交给进度环的
 * 颜色和文案去表达：
 *
 * | 状态 | 进度环 |
 * | --- | --- |
 * | 进行中 / 已完成 | `primary` |
 * | 有失败 | `error` |
 *
 * ⚠️ 之前这里是按状态**整卡换容器色**（`primaryContainer` / `errorContainer`），
 * 视觉上太重、还会跟下面几张卡片抢注意力。而"进行到哪"本来就是进度指示器
 * 该表达的事 —— 官方 expressive 组件里专门干这个的正是它。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusCard(
    session: SkipSession,
    /** 四项权限是否都已就绪 —— 没齐之前**不给**「开始处理」。 */
    permissionsReady: Boolean,
    /** 还差几项权限（用在提示文案里）。 */
    missingPermissionCount: Int,
    /** 本次会话是不是「自动获取权限」（只有它需要不同的运行中文案）。 */
    permissionSession: Boolean,
    /** 历史上已关闭的项数（跨会话累积）—— 也就是进度环的分子。 */
    closedCount: Int,
    okCount: Int,
    failCount: Int,
    total: Int,
    /** 还有多少项**从没处理过**（= 0 表示历史进度里全部已完成）。 */
    pendingCount: Int,
    currentTaskIndex: Int,
    currentStepLabel: String?,
    currentGuide: String?,
    onOpenAccessibilitySettings: () -> Unit,
    onStart: () -> Unit,
    /** 清空历史进度后重新跑全部（「重新处理全部」）。 */
    onResetAndStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = session == SkipSession.Running
    val finished = session == SkipSession.Finished
    val allSucceeded = finished && failCount == 0

    // 历史进度：没有任何待处理项 —— 也就是"上次已经全部跑完了"。
    val allDone = pendingCount == 0

    // ── 容器：**中性**，状态交给进度指示器与文案 ──
    //
    // ⚠️ 这里一度按状态**整卡换容器色**（`primaryContainer` / `errorContainer` /
    //    `surfaceContainerHigh`），与上面那段 KDoc 记录的结论**正好相反** ——
    //    代码与文档矛盾，而且实测观感就是 KDoc 里写的那两句："太重、会跟下面几张
    //    卡片抢注意力"。现在按 KDoc 执行：卡片只当**容器**，状态由进度指示器表达。
    //
    // 取值：**官方 Filled Card 的默认容器 `surfaceContainerHighest`** ——
    // `Card` 不传 `colors` 就是它，于是页面里三张卡片同档
    //（色阶依据见 PROJECT.md 3.5.1）。

    // 进度条的颜色 —— **状态在这里表达**：失败走错误色、进行中/完成走主色、
    // 待机压成中性（不喧宾夺主）。
    val accent = when {
        finished && !allSucceeded -> MaterialTheme.colorScheme.error
        running || finished -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // ── 文案纪律 ──
    //
    // ⚠️ 这里**只陈述状态**，绝不解释操作。
    //
    // 反例（都删了）：
    //   · "共 5 项，已全部关闭 —— 想再跑一遍就点下面"
    //     → 按钮上写着「重新处理全部」，用户看得懂，不需要旁白。
    //   · "成功 2 项，失败 0 项。建议关闭无障碍服务"
    //     → "建议"是说教，而且用户未必想现在就关。
    //   · "当前步骤：" / "目标位置：" / "正在准备…"
    //     → 前缀词和占位语全是噪音，直接给内容。
    //
    // 一句话原则：**用户扫一眼就知道现在什么状态**，而不是读一段说明。
    // 标签 —— 一句话说明"上面那个数字是什么"。
    //
    // 它和状态是**同一个位置**：空闲时是"已关闭"、处理中是当前步骤、失败时是"有失败"。
    // 这样卡片上只有一处需要读，用户不必先看标题再看说明。
    val label = when {
        !permissionsReady -> "还差 $missingPermissionCount 步"
        running && permissionSession -> "获取权限中"
        running -> currentStepLabel ?: "处理中"
        finished && allSucceeded -> "处理完成"
        finished -> "有失败"
        else -> "已关闭"
    }

    // 副行：**只在有额外信息时**才出现。
    // 空闲态不写"共 5 项，已关闭 3 项" —— 大数字已经把这件事说完了。
    val sub = when {
        running -> currentGuide
        finished -> "成功 $okCount · 失败 $failCount"
        else -> null
    }

    // 进度环比例。total 为 0 时给 0 —— 别让进度值变成 NaN。
    val progress = if (total == 0) 0f else closedCount.toFloat() / total

    // ── 卡片：Typography 主导，进度条当配角 ──
    //
    // M3E 的设计策略里明确有一条"用排版引导注意力"：**关键信息应该靠字号和字重
    // 跳出来**，而不是靠一个图形。对"5 项里关了 3 项"来说，一个 36sp 的「3 / 5」
    // 比一圈进度弧**更准确、也更有冲击力** —— 读数字不需要去量弧长，
    // 而项数少的时候弧长本来就分不出差别。
    //
    // 进度条退到下面当**辅助**，并且改用线性的：它的长度由容器决定，
    // 没有圆环那种"内部固定 48dp 几何、一放大曲线就失真"的毛病。
    Card(modifier = modifier, colors = appCardColors()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    // 中等强调就用 **`onSurfaceVariant`**（官方 token），
                    // 不要对 `onSurface` 调 alpha —— 那不在 token 体系里，
                    // 换个容器色就得重新猜透明度的观感。
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$closedCount / $total",
                    // 同上：要强调就用**官方 emphasized 字阶**（`displaySmall` 是
                    // Regular 400 → `displaySmallEmphasized` 是 Medium 500）。
                    // ⚠️ 手写的 `SemiBold`(600) 比官方 emphasized **还重**，
                    //    已经越过"小字号别堆字重"的界线；而这里真正让它跳出来的是
                    //    **36sp 的字号**（见上方 KDoc 的策略说明），不需要再加码字重。
                    style = MaterialTheme.typography.displaySmallEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // 副行只在真的有额外信息时才占位置 —— 空闲态什么都不显示。
                if (sub != null) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            LinearWavyProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = accent,
                trackColor = accent.copy(alpha = 0.16f),
            )

            // ── 主操作：同一时刻只给一个，避免用户不知道点哪个 ──
            when {
                // 权限没齐：**不给**「开始处理」。
                //
                // 缺权限时任何一项都跑不动（整套自动化本身就建立在无障碍之上），
                // 放个按钮只会让用户点了没反应、以为程序坏了。
                // 该做的引导就在紧邻的下方一块，描述文字里已经指向它。
                !permissionsReady -> Unit

                running -> {
                    OutlinedButton(onClick = onStop) {
                        Text("停止")
                    }
                }

                session == SkipSession.Finished -> {
                    // 卡片底是 `primaryContainer` / `errorContainer` 这类官方**容器色**，
                    // 与按钮的默认配色天然错开 —— 所以这里直接用默认按钮，**无需任何换色**。
                    //
                    // （此前"全部成功"那档用 `primary` 实色，与 `Button` 的默认底色同色，
                    //   逼得这里把按钮反转成 onPrimary；改用 primaryContainer 后，
                    //   `filledColors` / `plainColors` 与两处 `ButtonDefaults` 一起删掉了。）
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onOpenAccessibilitySettings) {
                            Text("关闭无障碍")
                        }
                        // ⚠️ 「再处理一次」的语义取决于结果，不能一概调 onStart：
                        //
                        //   · **全部成功** → 所有项都记入了"已完成"，此时直接 start
                        //     会因为没有待处理项而**整场跳过、点了没反应**。
                        //     必须先清空进度（onResetAndStart）才能真的重跑一遍。
                        //   · **有失败** → 失败的项**没有**记进度，直接 start 就是
                        //     只跑它们 —— 而这正是用户想要的（成功的不用再跑一遍）。
                        TextButton(
                            onClick = if (allSucceeded) onResetAndStart else onStart,
                        ) {
                            Text(if (allSucceeded) "重新处理全部" else "重试失败项")
                        }
                    }
                }

                else -> {
                    // 三种入口，文案对应三种状态 —— 用户一眼就知道这次点下去会发生什么：
                    //   初次 → 全跑；继续 → 只跑剩下的；全跑完了 → 清空进度重跑。
                    when {
                        allDone -> Button(onClick = onResetAndStart) {
                            Text("重新处理全部")
                        }

                        // 处理过一部分：主按钮是「继续处理」，但**必须**同时给出
                        // 「重新处理全部」—— 只留一个按钮的话，用户想从头再跑一遍
                        // 就无路可走（实测反馈原话："只有一个继续处理的按钮"）。
                        //
                        // 层级关系与「处理完成」那档完全一致：主操作填色（Button）、
                        // 清进度重跑走文字按钮（TextButton）。
                        pendingCount < total -> Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(onClick = onStart) {
                                Text("继续处理（剩 $pendingCount 项）")
                            }
                            TextButton(onClick = onResetAndStart) {
                                Text("重新处理全部")
                            }
                        }

                        else -> Button(onClick = onStart) {
                            Text("开始处理")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 处理记录卡片 —— 全应用**唯一**展示日志的地方。
 *
 * ## 为什么从"列表项"改成"卡片"
 *
 * 之前用的是和其它信息一样的 `SettingInfoItem`（每行一个图标 + 标题 + 描述），
 * 但日志的**时间戳是主信息**、而且行数会快速堆积，套进那种行布局后：
 * 时间被迫当标题、显得喧宾夺主，行高也白白吃掉一大截空间。
 *
 * 现在改成"标题栏 + 紧凑日志流"：
 * - 时间用**等宽字体**单列对齐，扫一眼就能看出先后顺序；
 * - 消息按结果上色（成功 / 失败 / 普通），不用额外图标也能分辨；
 * - 顶部「清空」常驻，不用再去设置页找。
 *
 * ⚠️ **界面显示的最新 [MAX_VISIBLE_LOGS] 条，与「复制全部」拿到的内容不是一回事**：
 * 界面为了不把页面撑得极长只铺最近几条；而复制出去的是
 * **完整日志（落盘文件，跨重启保留）+ 设备/版本/失败项头部**。
 * 因为用户复制它是为了**发给别人看**，只发一半等于没发。
 */
@Composable
private fun LogCard(
    entries: List<SkipLogEntry>,
    onClear: () -> Unit,
    /** 组装「复制全部」的内容：完整日志 + 诊断头部（见 [buildDiagnosticsReport]）。 */
    buildReport: suspend (Context) -> String,
    /** 复制完成后的反馈回调（行数）。由页面用 Snackbar 呈现 —— 见下方说明。 */
    onCopied: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier, colors = appCardColors()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // ⚠️ 与「权限获取」卡的表头**共用同一份定义**（[CardHeaderPadding]）——
                    //    之前这张是 `top=10/bottom=2`、那张是 `top=16/bottom=8`，
                    //    两个语义相同的表头高度不同，一页里就是"上下不像同一个组件"。
                    //    `end = 8` 的由来（按钮自带 12dp 内边距 → 视觉仍是 20dp）见该常量说明。
                    .padding(CardHeaderPaddingWithActions),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "处理记录",
                    // 与「权限获取」表头同一个字阶：官方 emphasized（14sp/Bold）。
                    // ⚠️ 别手写 `fontWeight` —— 理由见 [CardHeaderPadding]。
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    modifier = Modifier.weight(1f),
                )
                // 一键复制：排查问题时把整段日志发出去，比截图好读得多。
                //
                // ⚠️ 反馈走回调交给页面用 **Snackbar** 呈现，而不是在这里 `Toast`：
                //    官方组件清单里瞬时反馈是 `Snackbar`（Toast 是平台遗留组件，
                //    不在 Material 规范内）。Snackbar 由 Scaffold 承载，
                //    位置固定、可排队，也不会像 Toast 那样飘在屏幕中间。
                TextButton(onClick = {
                    // ⚠️ 复制的是 **buildReport()**（完整日志 + 诊断头），
                    //    而不是屏幕上这 [MAX_VISIBLE_LOGS] 条 —— 见 [LogCard] 的说明。
                    //    读盘在 IO 线程（buildReport 是 suspend），不占主线程。
                    scope.launch {
                        val text = buildReport(context)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("SkipAd 诊断日志", text))
                        onCopied(text.count { it == '\n' } + 1)
                    }
                }) {
                    Text("复制全部")
                }
                TextButton(onClick = onClear) {
                    Text("清空")
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                entries.take(MAX_VISIBLE_LOGS).forEach { entry ->
                    LogRow(entry)
                }
            }

            // 折叠说明**常驻**（不再"只在超出时才显示"）：它同时回答两个容易混淆的问题 ——
            // "为什么界面上的记录比这次跑出来的少"（只铺最近 N 条）；
            // "复制能拿到多少"（全部，含历史）。这段字不能省：界面显示与复制内容
            // **不是一回事**，不写清用户会以为复制也只有这些。
            Text(
                text = "界面只显示最近 $MAX_VISIBLE_LOGS 条 · " +
                    "「复制全部」可拿到完整日志（含历史记录与设备信息）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
        }
    }
}

/** 一条日志：等宽时间 + 按结果上色的消息。 */
@Composable
private fun LogRow(entry: SkipLogEntry) {
    val messageColor = when (entry.handled) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = formatClock(entry.timeMs),
            style = MaterialTheme.typography.labelSmall,
            // 等宽字体让每行的时间宽度一致，纵向扫下来是齐的。
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = messageColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 日志卡片里最多显示几行（最新的在前）。 */
private const val MAX_VISIBLE_LOGS = 20

/** 只显示"时:分:秒"—— 短期日志里日期没有意义。 */
private fun formatClock(epochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

/**
 * 组装「复制全部」用的**诊断报告**：设备/版本头部 + 失败项 + 完整日志。
 *
 * ## 为什么值得单独做这个
 *
 * 用户出问题时的动作是"点复制 → 把内容发出来"（留存或贴到问题反馈里）。
 * 之前复制出去的**只有界面上那 20 条**，于是对方看到一堆 `↳ 等待…` 却没有上下文：
 * 哪个版本、哪台机器、什么系统、当时无障碍开着没有、前面哪一项先失败的 —— 全得再问一轮。
 *
 * 这份报告把**一次性要问的都放进头部**；正文是 [AdSkipRuntime.fullLogText] 给出的
 * **完整日志**（落盘文件，跨重启保留、不受内存 150 条上限影响）。
 *
 * ⚠️ 头部只放"诊断必需且不敏感"的信息：**不采集**账号、也不列用户装了哪些应用 ——
 *   那属于隐私，排查本应用的问题根本用不到。
 */
private suspend fun buildDiagnosticsReport(
    context: Context,
    session: SkipSession,
    outcomes: List<TaskOutcome>,
): String {
    // 版本号从 PackageManager 读，不引 BuildConfig：本项目没开 buildConfig 特性
    // （AGP 8+ 默认关闭），为一个字符串去开它不划算。
    val version = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("未知")

    val failed = outcomes.filter { !it.success }
    val okCount = outcomes.size - failed.size

    return buildString {
        appendLine("━━━━━━━ 澎湃SkipAd 诊断报告 ━━━━━━━")
        appendLine("导出时间：${formatStamp(System.currentTimeMillis())}")
        appendLine("应用版本：$version")
        appendLine("包名　　：${context.packageName}")
        appendLine("系统　　：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        appendLine("机型　　：${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("构建号　：${Build.DISPLAY}")
        appendLine("无障碍　：${if (AdSkipPermission.isEnabled(context)) "已开启" else "已关闭"}")
        appendLine("会话状态：${sessionLabel(session)}")
        appendLine("本次结果：成功 $okCount · 失败 ${failed.size}（共 ${outcomes.size} 项）")
        if (failed.isNotEmpty()) {
            // 失败项**提到头部**：这是对方第一眼要找的东西，
            // 埋在几百行日志里等于没有。
            appendLine("失败明细：")
            failed.forEach { appendLine("　· ${it.taskTitle}：${it.detail}") }
        }
        appendLine("────────── 完整日志 ──────────")
        append(AdSkipRuntime.fullLogText())
    }
}

/** 会话状态的中文说法（报告头部用）。 */
private fun sessionLabel(session: SkipSession): String = when (session) {
    SkipSession.Idle -> "未开始"
    SkipSession.Running -> "处理中"
    SkipSession.Finished -> "已结束"
}

/**
 * 诊断报告头部用的完整时间戳。
 *
 * ⚠️ 与界面上的 [formatClock] 不同：导出的内容会跨越好几天，没有日期对不上时间。
 *    格式与 `AdSkipRuntime` 落盘日志**保持一致**，方便两处对照。
 */
private fun formatStamp(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))

/**
 * 「处理清单」**一行**的展示数据。
 *
 * ## 为什么要有这个类（是性能修复，不是洁癖）
 *
 * Compose 判断"一个 item 能不能**跳过**重组"看的是**入参稳不稳定**。原来那 15 行
 * 直接读 `outcomes: List` / `doneTaskIds: Set` / `availableTasks: List` 这些
 * **不稳定类型**，于是：
 *
 * - 整块清单挤在 `LazyColumn` 的**一个 item** 里 → 必须**同一帧组合完**，
 *   滑进视口时掉帧；
 * - 顶栏是 `exitUntilCollapsed`（**随滚动折叠**），滚动期间 Scaffold 的内容槽
 *   **每一帧都会被重新调用** → 入参不稳定 → **整块 15 行每帧重组**。
 *
 * 把"长什么样"先算成这个类（字段全是 `String` / `Boolean`，Compose 眼里**稳定**），
 * 每行再拆成独立 item，滚动时就只剩布局，不再有 15 行的重组。
 *
 * ⚠️ **不要往这个类里加 `onClick` 之类的函数字段**：函数类型的稳定性推导看它的捕获，
 * 一旦捕获了不稳定对象，**整个类都会变不稳定**，就白拆了。回调统一走
 * `onTaskClick(id)`（见 [HomeScreen] 里的那个 `remember`）。
 */
@Immutable
private data class TaskRowUi(
    /** 任务 id，同时用作 `items` 的 key。 */
    val id: String,
    val title: String,
    val description: String,
    /** 行尾徽章文字；null = 不显示。 */
    val statusText: String?,
    /** 徽章配色：true → 正面（"确实做成了"）。 */
    val statusPositive: Boolean,
    /** 这一行现在能不能点（应用没装 / 有会话在跑 / 权限没齐 → 不能）。 */
    val clickable: Boolean,
)
