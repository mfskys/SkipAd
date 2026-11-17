package org.dpdns.mfsky.skipad.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import org.dpdns.mfsky.skipad.adskip.AdSkipRuntime
import org.dpdns.mfsky.skipad.adskip.SkipProgressRepository
import org.dpdns.mfsky.skipad.adskip.SkipSession
import org.dpdns.mfsky.skipad.ui.adaptive.currentNavigationSuiteType
import org.dpdns.mfsky.skipad.ui.design.component.CelebrationFireworks
import org.dpdns.mfsky.skipad.ui.feature.home.HomeScreen
import org.dpdns.mfsky.skipad.ui.feature.settings.SettingsScreen
import org.dpdns.mfsky.skipad.ui.navigation.HomeKey
import org.dpdns.mfsky.skipad.ui.navigation.SettingsKey
import org.dpdns.mfsky.skipad.ui.navigation.TopLevelDestination

/**
 * 应用骨架 —— Material 3 Expressive 原生。
 *
 * | 层 | 实现 |
 * | --- | --- |
 * | 骨架 | `NavigationSuiteScaffold`（按窗口宽度在底部短栏 / 宽侧栏之间自适应） |
 * | 导航 | Material3 `NavigationSuiteItem`（四形态：ShortNavigationBar / WideNavigationRail 折叠与展开） |
 * | 页面 | `NavDisplay` + Nav3 返回栈 |
 *
 * ## 两个要点
 *
 * 1. 用 `navigationItems` 入口（不是旧的 `navigationSuiteItems`）—— 只有新入口才能
 *    拿到 ShortNavigationBar* / WideNavigationRail* 这几种新形态。
 * 2. **返回栈是唯一状态源**：选中态从 `backStack.lastOrNull()` 反查，
 *    不再另存一份 `mutableStateOf(当前标签)`，避免两份状态不同步。
 */
@Composable
fun SkipAdApp(modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(HomeKey)
    val suiteType = currentNavigationSuiteType()

    // ── 把持久化的处理进度同步进运行期状态 ──
    //
    // 引擎是同步 API，读不了 suspend 的 DataStore，所以由这里当"桥"：
    // 订阅一次、长期有效（切主题 / 旋转屏幕都不会重复订阅）。
    // 反向（任务完成时落盘）在引擎里做，两边合起来才是完整闭环。
    val context = LocalContext.current
    LaunchedEffect(context) {
        SkipProgressRepository.doneTaskIds(context).collect { ids ->
            AdSkipRuntime.setDoneTaskIds(ids)
        }
    }

    // ── 庆祝撒花（MD3E 的 hero moment，全应用只此一处）──
    //
    // ⚠️ 渲染在**应用根**、盖在整个 NavigationSuiteScaffold 之上 —— 只有这样才能真"全屏"
    //    （顶栏与底部导航栏一起铺到）。曾放在主页 AppPageScaffold 的 overlay 槽里，
    //    那只覆盖"页面内容区"，永远铺不满整屏。
    //
    // 两个触发源：
    //   ① 正常：会话 Finished 且**一项都没失败**（有失败还撒花是反讽；中途停止不算完成）。
    //      是否已庆祝记在**会话**上（`AdSkipRuntime.celebrated`）而**不是**这里的 remember ——
    //      否则切页面 / 旋转屏幕会重复触发。
    //   ② 手动：连点底部导航的「主页」[NAV_TAP_TRIGGER] 下，方便随时验证效果。
    var celebrate by rememberSaveable { mutableStateOf(false) }
    val session by AdSkipRuntime.session.collectAsState()
    val outcomes by AdSkipRuntime.outcomes.collectAsState()
    LaunchedEffect(session, outcomes) {
        if (session == SkipSession.Finished) {
            val failed = outcomes.count { !it.success }
            val ok = outcomes.count { it.success }
            if (!AdSkipRuntime.celebrated && failed == 0 && ok > 0) {
                AdSkipRuntime.markCelebrated()
                celebrate = true
            }
        }
    }

    // 连点「主页」的计数（触发源 ②）。
    var homeTaps by remember { mutableIntStateOf(0) }

    Box(modifier = modifier.fillMaxSize()) {
        NavigationSuiteScaffold(
            // ⚠️ `modifier` 已经交给外层 Box —— 它才是这一层的根节点。
            modifier = Modifier.fillMaxSize(),
            navigationSuiteType = suiteType,
            navigationItems = {
                TopLevelDestination.entries.forEach { destination ->
                    NavigationSuiteItem(
                        // ⚠️ 必须显式传 type：Item 的默认值基于固定常量，不对应当前窗口，
                        //    不传会与 Scaffold 形态不一致（宽侧栏里文字竖排）。
                        navigationSuiteType = suiteType,
                        selected = destination.key == backStack.lastOrNull(),
                        onClick = {
                            if (destination.key == HomeKey) {
                                homeTaps++
                                if (homeTaps >= NAV_TAP_TRIGGER) {
                                    homeTaps = 0
                                    celebrate = true
                                }
                            } else {
                                // 点了别的标签就重新计数 —— "连点"必须是**连续**的。
                                homeTaps = 0
                            }
                            select(backStack, destination.key)
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        // 折叠的宽侧栏（WideNavigationRailCollapsed）本就以图标为主，
                        // 中文 label 会被压成竖排 —— 遵循形态语义：折叠侧栏不显示文字。
                        label = if (suiteType == NavigationSuiteType.WideNavigationRailCollapsed) {
                            null
                        } else {
                            { Text(destination.label) }
                        },
                    )
                }
            },
        ) {
            NavDisplay(
                backStack = backStack,
                entryProvider = appEntryProvider(),
            )
        }

        // 撒花铺满整屏（含导航栏）。它不消费触摸事件，所以照常可以点按、切页。
        CelebrationFireworks(play = celebrate, onFinished = { celebrate = false })
    }
}

/**
 * 连点底部导航「主页」多少下 = 手动放一次庆祝（测试用）。
 *
 * 10 下是刻意选的：正常使用（切页）不会误触，但明确知道的人随时能触发 ——
 * 与系统"连点 7 下开启开发者选项"同一个思路。
 */
private const val NAV_TAP_TRIGGER = 10

/** 切换顶层目的地。返回栈是唯一状态源，所以这里直接操作它。 */
private fun select(backStack: MutableList<NavKey>, key: NavKey) {
    if (backStack.lastOrNull() != key) {
        backStack.clear()
        backStack.add(key)
    }
}

/**
 * 页面路由表。
 *
 * `entryProvider` 是包级函数；`entry` 是它的作用域成员函数，**不要** import。
 */
private fun appEntryProvider() = entryProvider<NavKey> {
    entry<HomeKey> {
        HomeScreen()
    }
    entry<SettingsKey> {
        SettingsScreen()
    }
}
