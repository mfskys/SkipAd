package org.dpdns.mfsky.skipad.ui.feature.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.roundedfilled.Apps
import com.composables.icons.materialsymbols.roundedfilled.Code
import com.composables.icons.materialsymbols.roundedfilled.Info
import com.composables.icons.materialsymbols.roundedfilled.Palette
import com.composables.icons.materialsymbols.roundedfilled.Download
import com.composables.icons.materialsymbols.roundedfilled.Layers
import com.composables.icons.materialsymbols.roundedfilled.Search
import com.composables.icons.materialsymbols.roundedfilled.Verified_user
import kotlinx.coroutines.launch
import org.dpdns.mfsky.skipad.adskip.AdSkipAccessibilityService
import org.dpdns.mfsky.skipad.adskip.AdSkipPermission
import org.dpdns.mfsky.skipad.ui.adaptive.AppAdaptiveContent
import org.dpdns.mfsky.skipad.ui.adaptive.AppContentWidth
import org.dpdns.mfsky.skipad.ui.design.component.AppPageScaffold
import org.dpdns.mfsky.skipad.ui.design.component.LocalSegmentedListItemShapes
import org.dpdns.mfsky.skipad.ui.design.component.AppearanceSettingsDialog
import org.dpdns.mfsky.skipad.ui.design.component.PROJECT_REPO_LABEL
import org.dpdns.mfsky.skipad.ui.design.component.PROJECT_REPO_URL
import org.dpdns.mfsky.skipad.ui.design.component.SettingInfoItem
import org.dpdns.mfsky.skipad.ui.design.component.SettingItem
import org.dpdns.mfsky.skipad.ui.design.component.openUrl
import org.dpdns.mfsky.skipad.ui.design.component.rememberAccessibilityEnabled
import org.dpdns.mfsky.skipad.ui.design.component.rememberAppListAllowed
import org.dpdns.mfsky.skipad.ui.design.component.rememberBackgroundStartPermission
import org.dpdns.mfsky.skipad.ui.design.component.rememberInstallAllowed
import org.dpdns.mfsky.skipad.ui.design.component.rememberOverlayAllowed
import org.dpdns.mfsky.skipad.ui.design.component.rememberShizukuAccessibilityOpener
import org.dpdns.mfsky.skipad.ui.design.theme.ThemeMode
import org.dpdns.mfsky.skipad.ui.design.theme.ThemeRepository

/**
 * 设置页 —— 拼接式分组列表（官方 `SegmentedListItem` 的分段样式）。
 *
 * ## 分组结构
 *
 * | 分组 | 条目 | 状态 |
 * | --- | --- | --- |
 * | **权限** | 无障碍服务 | ✅ 查系统设置，行尾显示状态徽章 |
 * | **权限** | 获取应用列表 | ✅ 查 `QUERY_ALL_PACKAGES`（**不是** MIUI 那个同名开关，见下） |
 * | **权限** | 后台弹出界面 | ✅ 查 AppOps 的 MIUI 扩展 op 10021 |
 * | **权限** | 显示悬浮窗 | ✅ 查 `Settings.canDrawOverlays` |
 * | **权限** | 允许安装应用 | ✅ 查 `PackageManager.canRequestPackageInstalls` |
 *
 * ## ⚠️ 「获取应用列表」为什么查的不是 MIUI 页面上那个开关
 *
 * MIUI「其他权限」页确实有一项叫「获取应用列表」，但它**不暴露任何 appop** ——
 * 实测把 `10008 / 10017 / 10020 / 10022 / 10045 / 10053` 逐个设为 allow，
 * 那行状态**始终是"拒绝"**。而且**在它显示"拒绝"时，本应用读取包完全正常**。
 *
 * 所以真正的闸门是系统的 `QUERY_ALL_PACKAGES`（Android 11 起），
 * MIUI 那个开关多半是旧版本遗留的展示项。
 *
 * 这个判据还有个好处：**跨 ROM 一致** —— 非 MIUI 机器上没有那个开关，
 * 但读的是同一个系统权限，结论照样对。
 *
 * 详见 `AdSkipPermission.isAppListAllowed`。
 * | **外观** | 主题与样式 | ✅ |
 * | **关于** | 开源项目（GitHub）＋ 版本号 | ✅ 仓库行可点，版本只读 |
 *
 * ## 三条权限为什么能放在同一组
 *
 * 曾经只有无障碍能查（读 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`），
 * 「后台弹出界面」查不到 —— 当时分成"能检测"与"手动确认"两组，
 * 免得一条有状态、一条没有，让人误以为"没显示 = 没开启"。
 *
 * 后来发现它其实是 HyperOS 挂在**标准 AppOps 服务**上的一个扩展 op（编号 10021），
 * 用 `AppOpsManager` 传编号就能查（见 `AdSkipPermission.isBackgroundStartAllowed`）。
 * 三条都成了可检测项，于是合回同一组 —— 用户一眼看到所有权限的当前状态。
 *
 * ## 关于「允许后台运行」（系统里叫"显示在其他应用上层"）
 *
 * ⚠️ 这条**一度被删掉过**，当时的理由是"无障碍服务自身的豁免够用、
 * 悬浮窗从未被用到"。**那个结论是错的** —— 后来实测发现：
 *
 * 自动化期间本应用长时间待在后台（它自己去打开系统设置 / 下载管理），
 * 厂商 ROM 会把这样的进程判为可冻结、连整个 uid 一起冻住，届时
 * **所有线程停止调度**：任务卡死半路，连日志和心跳线程都不再输出。
 * 实测证据：`/sys/fs/cgroup/apps/uid_xxx/cgroup.freeze` 一直是 `1`。
 *
 * 对策是自动化期间挂一个 **1×1 像素的透明悬浮窗**（见 `SkipAdAutomationService`）——
 * 厂商的冻结判据基本是"这个应用还有没有可见窗口"。加上之后 `cgroup.freeze`
 * 变成 `0`，完整两项由"卡死"变成 **2.7 秒跑完**。
 *
 * ⚠️ 它**不是**用来"处理完自动切回本应用"的（那件事确实靠无障碍豁免就够），
 * 所以条目文案写「允许后台运行」而不是权限的系统名字 ——
 * 说清用途，用户才不会以为本应用要弹窗打扰自己。
 *
 * ⚠️ 这里**不放"开始处理"**：那是主页的核心操作，放两处会让用户困惑。
 * ⚠️ 这里**也不放日志**：日志属于主页的工作产出，全应用只有主页展示。
 *
 * ## 视觉要点（改错就"看不出是列表"）
 *
 * - **底色全部走官方默认值**，本页不做任何颜色覆盖。
 * - 一个分组至少 **2~3 行**才看得出拼接效果（权限与外观、关于组合后整体不单薄）。
 * - 组内不用分隔线；组与组之间也不加额外间距。
 *
 * ⚠️ 列表的**水平内边距由 `settingsRow` 自己给**（16dp），
 * 这里的 `contentPadding` 只设上下 —— 两者叠加会双重缩进。
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val themeMode by ThemeRepository
        .themeMode(context)
        .collectAsState(initial = ThemeMode.FollowSystem)

    var showAppearanceDialog by remember { mutableStateOf(false) }

    // ⚠️ 带 `refreshKey`：用 Shizuku 直接打开无障碍时**不会离开本应用**，
    //    `ON_RESUME` 不会触发，状态得手动刷一次（见下面的 openAccessibility）。
    var accessibilityRefreshKey by remember { mutableIntStateOf(0) }
    val accessibilityEnabled = rememberAccessibilityEnabled(accessibilityRefreshKey)

    val snackbarHostState = remember { SnackbarHostState() }

    // 「无障碍服务」那一行：本机有可用 Shizuku 就**直接开**（首次弹它的授权框），
    // 否则自动降级为打开系统设置页 —— 与老行为一致。
    // 见 rememberShizukuAccessibilityOpener 的分支说明。
    val openAccessibility = rememberShizukuAccessibilityOpener(
        onEnabled = { accessibilityRefreshKey++ },
        onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
        onFallback = { AdSkipPermission.openSettings(context) },
    )
    // 「读取应用列表」—— 判断某个预装应用装没装时要用它，
    // 见 AdSkipPermission.isAppListAllowed（判据是系统的 QUERY_ALL_PACKAGES）。
    val appListAllowed = rememberAppListAllowed()
    val backgroundStartAllowed = rememberBackgroundStartPermission()
    // 「显示在其他应用上层」—— 自动化期间靠 1×1 透明悬浮窗保活，见页面顶部说明。
    val overlayAllowed = rememberOverlayAllowed()
    // 「安装未知应用」——只有「应用包管理组件 · 资源推荐」那一项需要它
    //（那个设置页没有导出，只能从安装界面进去，见 AdSkipPermission 的说明）。
    val installAllowed = rememberInstallAllowed()
    val versionName = remember(context) { appVersionName(context) }

    // ── 行点击的**统一稳定入口** ──
    //
    // ⚠️ 为什么要有它（性能，不是整洁）：设置页的每一行现在是**独立的 LazyColumn item**，
    //    而"能不能被跳过"取决于行 lambda 的**捕获**稳不稳定。内联写
    //    `{ AdSkipPermission.openOverlaySettings(context) }` 会捕获 `context`
    //    （Compose 眼里不稳定）→ 每帧重组。收敛到这一个 `remember` 出来的入口之后，
    //    行里只写 `{ onRowClick(ROW_X) }`：捕获的是一个稳定单例 + 一个常量 → 可跳过。
    //
    // ⚠️ 这里捕获的 `openAccessibility` 是**首次组合**那一份实例，之所以安全：
    //    它内部用 `rememberUpdatedState` 读回调（见 rememberShizukuAccessibilityOpener），
    //    所以旧实例照样会调到最新的 lambda。
    val onRowClick: (String) -> Unit = remember {
        { id ->
            when (id) {
                ROW_ACCESSIBILITY -> openAccessibility()
                ROW_APP_LIST, ROW_BACKGROUND_START -> {
                    AdSkipPermission.openAppPermissionSettings(context)
                }
                ROW_OVERLAY -> AdSkipPermission.openOverlaySettings(context)
                ROW_INSTALL -> AdSkipPermission.openInstallUnknownAppsSettings(context)
                ROW_APPEARANCE -> showAppearanceDialog = true
                // 开源仓库：交给系统浏览器。打不开时给一句 Snackbar ——
                // 点一下毫无反应会被当成界面坏了。
                ROW_REPO -> if (!openUrl(context, PROJECT_REPO_URL)) {
                    scope.launch { snackbarHostState.showSnackbar("没有可用的浏览器") }
                }
            }
        }
    }

    AppPageScaffold(
        title = "设置",
        modifier = modifier,
        // 「无障碍服务」那一行在"没有可用 Shizuku / 授权被拒"时要给一句明确反馈，
        // 否则点了没反应会让人以为程序坏了。
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        AppAdaptiveContent(maxWidth = AppContentWidth.Form) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 4.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                // ── 权限 ──
                //
                // 两条都能自动检测（无障碍查系统设置；后台弹出界面查 AppOps
                // 的 MIUI 扩展 op 10021），状态在 ON_RESUME 时重读。
                //
                // ⚠️ **一个组 = 一个标题 item + 每行一个 item**（不要再退回
                //    "一个组塞进一个 item"）：顶栏是 `exitUntilCollapsed`（随滚动折叠），
                //    滚动期间 Scaffold 内容槽**每帧被重新调用**，只有"入参稳定的独立 item"
                //    才可能被跳过。详见 PROJECT.md 坑 #45 与 [onRowClick] 的说明。
                settingsHeader(key = "permissions", title = "权限")
                settingsRow(key = "perm-accessibility", index = 0, count = PERMISSION_ROWS) {
                    SettingItem(
                        icon = MaterialSymbols.RoundedFilled.Verified_user,
                        title = "无障碍服务",
                        description = "处理时自动操作系统设置",
                        // 有可用 Shizuku 时**直接开**，没有才跳系统设置页。
                        onClick = { onRowClick(ROW_ACCESSIBILITY) },
                        statusText = if (accessibilityEnabled) "已开启" else "未开启",
                        statusPositive = accessibilityEnabled,
                    )
                }
                settingsRow(key = "perm-app-list", index = 1, count = PERMISSION_ROWS) {
                    SettingItem(
                        icon = MaterialSymbols.RoundedFilled.Search,
                        // ⚠️ 标题用**系统权限的原名**（MIUI 的「其他权限」页里
                        //    就叫「获取应用列表」），理由同下一条。
                        title = "获取应用列表",
                        description = "认出没安装的应用，自动跳过",
                        onClick = { onRowClick(ROW_APP_LIST) },
                        statusText = if (appListAllowed) "已允许" else "去允许",
                        statusPositive = appListAllowed,
                    )
                }
                settingsRow(key = "perm-background", index = 2, count = PERMISSION_ROWS) {
                    SettingItem(
                        icon = MaterialSymbols.RoundedFilled.Apps,
                        title = "后台弹出界面",
                        description = "处理完自动返回才能生效",
                        onClick = { onRowClick(ROW_BACKGROUND_START) },
                        statusText = if (backgroundStartAllowed) "已允许" else "去允许",
                        statusPositive = backgroundStartAllowed,
                    )
                }
                settingsRow(key = "perm-overlay", index = 3, count = PERMISSION_ROWS) {
                    SettingItem(
                        icon = MaterialSymbols.RoundedFilled.Layers,
                        // ⚠️ 标题用**系统权限的原名**，别改写成"允许后台运行"之类 ——
                        //    用户点进去要在系统设置页里找到那一条，名字对不上就得靠猜。
                        //    这个权限在本应用里真正的用途写在 description 里。
                        title = "显示悬浮窗",
                        description = "防止被系统冻结",
                        onClick = { onRowClick(ROW_OVERLAY) },
                        statusText = if (overlayAllowed) "已允许" else "去允许",
                        statusPositive = overlayAllowed,
                    )
                }
                settingsRow(key = "perm-install", index = 4, count = PERMISSION_ROWS) {
                    SettingItem(
                        icon = MaterialSymbols.RoundedFilled.Download,
                        title = "允许安装应用",
                        // 说明适用范围 —— 它只服务其中一项，
                        // 不说清的话用户会以为整个工具都要这个敏感权限。
                        description = "「应用包管理组件」那一项需要它",
                        onClick = { onRowClick(ROW_INSTALL) },
                        statusText = if (installAllowed) "已允许" else "去允许",
                        statusPositive = installAllowed,
                    )
                }

                // ── 外观 ──
                settingsHeader(key = "appearance", title = "外观")
                settingsRow(key = "appearance-theme", index = 0, count = APPEARANCE_ROWS) {
                    SettingItem(
                        icon = MaterialSymbols.RoundedFilled.Palette,
                        title = "主题与样式",
                        description = appearanceSummary(themeMode),
                        onClick = { onRowClick(ROW_APPEARANCE) },
                    )
                }

                // ── 关于 ──
                //
                // 两行，都是"一眼能看完"的信息：
                //   ① 开源仓库 —— 可点，跳浏览器；
                //   ② 版本号 —— 只读（没有可做的动作，所以不给箭头、不响应点击）。
                //
                // ⚠️ 不再为这两条信息开「关于」弹窗：用户要多点一次才能看到
                //    本来两行就能放下的事，纯属多一层。
                settingsHeader(key = "about", title = "关于")
                settingsRow(key = "about-repo", index = 0, count = ABOUT_ROWS) {
                    SettingItem(
                        icon = MaterialSymbols.RoundedFilled.Code,
                        title = "开源项目",
                        description = PROJECT_REPO_LABEL,
                        onClick = { onRowClick(ROW_REPO) },
                    )
                }
                settingsRow(key = "about-version", index = 1, count = ABOUT_ROWS) {
                    SettingInfoItem(
                        icon = MaterialSymbols.RoundedFilled.Info,
                        title = "版本",
                        description = versionName,
                    )
                }
            }
        }
    }

    if (showAppearanceDialog) {
        AppearanceSettingsDialog(
            currentThemeMode = themeMode,
            onDismiss = { showAppearanceDialog = false },
            onConfirm = { newMode ->
                scope.launch {
                    ThemeRepository.setThemeMode(context, newMode)
                }
                showAppearanceDialog = false
            },
        )
    }

}

/**
 * 行描述：把当前状态拼成一句，用户不点进去也能看到现在是什么。
 *
 * 所以**不另开"行尾当前值"文本** ——
 * 因为中文提示词较长，行尾文本在窄屏上会被压断行，反而更难读。
 */
private fun appearanceSummary(themeMode: ThemeMode): String = when (themeMode) {
    ThemeMode.FollowSystem -> "跟随系统"
    ThemeMode.Light -> "浅色"
    ThemeMode.Dark -> "深色"
}

/**
 * 读取应用版本名。
 *
 * 用 API 33 起的 `PackageInfoFlags` 重载，避开 `getPackageInfo(String, Int)` 的弃用告警
 * （本项目 minSdk 已是 33，可以无条件用新重载）。
 */
private fun appVersionName(context: Context): String = runCatching {
    context.packageManager
        .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        .versionName
}.getOrNull() ?: "—"

// ---------------------------------------------------------------------------
// 「关于」分组里的外链
// ---------------------------------------------------------------------------
//
// 仓库地址与 `openUrl` 都在 `ui/design/component/ExternalLinks.kt`（唯一来源）。

// ---------------------------------------------------------------------------
// 设置列表的铺法（**一行一个 item**；性能纪律见 PROJECT.md 坑 #45）
// ---------------------------------------------------------------------------

/** 「权限」组的行数 —— 分段形状（首/中/尾圆角）按它算，**加行时必须同步改**。 */
private const val PERMISSION_ROWS = 5

/** 「外观」组的行数。 */
private const val APPEARANCE_ROWS = 1

/** 「关于」组的行数 —— **两行**（开源项目 + 版本号）。 */
private const val ABOUT_ROWS = 2

// 行的动作 id：只给 [SettingsScreen] 里那个 `onRowClick` 分发器用 ——
// 这样每行的 lambda 只捕获"一个稳定单例 + 一个常量"，行才可能被 Compose 跳过。
private const val ROW_ACCESSIBILITY = "accessibility"
private const val ROW_APP_LIST = "app-list"
private const val ROW_BACKGROUND_START = "background-start"
private const val ROW_OVERLAY = "overlay"
private const val ROW_INSTALL = "install"
private const val ROW_APPEARANCE = "appearance"
private const val ROW_REPO = "repo"

/**
 * 组标题 item —— 字色与内边距与主页分组标题保持一致
 * （外层 16dp + 组内 16dp = 32dp 起始）。
 */
private fun LazyListScope.settingsHeader(key: String, title: String) {
    item(key = "$key-header") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 32.dp, top = 8.dp, bottom = 8.dp),
        )
    }
}

/**
 * 组内**一行**的 item。
 *
 * ⚠️ 为什么不把整组塞进一个 item 里（本文件曾经就是那样）：顶栏是
 *    `exitUntilCollapsed`（随滚动折叠），滚动期间 `Scaffold` 的内容槽**每帧被重新调用** ——
 *    只有"入参稳定的独立 item"才可能被跳过，整组塞一块就只能每帧整块重组。
 *    完整推导见 PROJECT.md 坑 #45。
 *
 * @param index / count 决定分段形状（首 / 中 / 尾圆角），走官方
 *   `ListItemDefaults.segmentedShapes`，与官方 `SegmentedListItem` 示例同一套实现。
 */
private fun LazyListScope.settingsRow(
    key: String,
    index: Int,
    count: Int,
    content: @Composable () -> Unit,
) {
    item(key = key) {
        CompositionLocalProvider(
            LocalSegmentedListItemShapes provides
                ListItemDefaults.segmentedShapes(index = index, count = count),
        ) {
            Box(
                modifier = Modifier.padding(
                    start = 16.dp,
                    end = 16.dp,
                    // 行间间隙用官方 token（与主页任务列表同一份）；
                    // 首行不补，末行补组尾那 8dp。
                    top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap,
                    bottom = if (index == count - 1) 8.dp else 0.dp,
                ),
            ) {
                content()
            }
        }
    }
}
