package org.dpdns.mfsky.skipad.ui.adaptive

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.window.core.layout.WindowSizeClass

/**
 * 全项目唯一的自适应决策出口。
 *
 * 决策依据是**当前窗口空间**，不是设备类型：禁止 `isTablet` /
 * `LocalConfiguration.screenWidthDp`；也禁止业务组件各自调 `currentWindowAdaptiveInfoV2()`。
 * 窗口尺寸是动态状态（旋转、分屏、折叠），全部 `@Composable` 读取，不缓存。
 */

/**
 * 顶层导航形态（M3E 四形态）。
 *
 * | 值 | 语义 |
 * | --- | --- |
 * | `ShortNavigationBarCompact` | 短底栏 —— 宽度 Compact |
 * | `ShortNavigationBarMedium` | 短底栏（较高）—— 高度 Compact 或桌面姿态 |
 * | `WideNavigationRailCollapsed` | 折叠的宽侧栏（仅图标） |
 * | `WideNavigationRailExpanded` | 展开的宽侧栏（图标 + 横排文字） |
 *
 * ⚠️ `NavigationSuiteScaffold` 用 `navigationItems` 入口，且每个 Item 都显式传
 * `navigationSuiteType`（默认值基于固定常量，不传会与 Scaffold 形态不一致）。
 */
@Composable
fun currentNavigationSuiteType(): NavigationSuiteType {
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val sizeClass = adaptiveInfo.windowSizeClass

    val widthCompact = !sizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
    )
    val widthExpanded = sizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
    )
    val heightCompact = !sizeClass.isHeightAtLeastBreakpoint(
        WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND,
    )

    return when {
        // 宽度不够（手机竖屏）→ 短底栏
        widthCompact -> NavigationSuiteType.ShortNavigationBarCompact

        // 高度不够或桌面姿态（手机横屏、折叠屏半折立起）→ 短底栏（较高版）
        heightCompact || adaptiveInfo.windowPosture.isTabletop ->
            NavigationSuiteType.ShortNavigationBarMedium

        // 宽度已达 Expanded（≥840dp）→ 展开的宽侧栏
        widthExpanded -> NavigationSuiteType.WideNavigationRailExpanded

        // 其余（600~840dp）→ 折叠的宽侧栏
        else -> NavigationSuiteType.WideNavigationRailCollapsed
    }
}
