package org.dpdns.mfsky.skipad.ui.design.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * **原生 M3E 模式**的顶栏 —— 包装 `MediumFlexibleTopAppBar`。
 *
 * 为什么不用旧的 `TopAppBar`：M3E 的 AppBar 已毕业到 Flexible 系列。
 * 它内部是 TwoRows 结构，支持随内容滚动在展开态与折叠态之间过渡
 * （折叠高 64dp、展开约 2 行），这才是 M3E 推荐的顶栏形态。
 *
 * ## 为什么只有一个高度档
 *
 * 官方另一档是 `LargeFlexibleTopAppBar`（展开约 4 行，适合"标题即页面主角"的信息流页）。
 * 本应用只有主页 / 设置两个一级页面，标题都不是主角，用 Medium 一档就够，
 * 所以**不提供变体参数** —— 两个组件的参数列表完全一致，
 * 将来真需要时在这里换掉被包装的那个即可（改一行）。
 *
 * 纪律：
 * - 页面层**禁止**直接调用 `androidx.compose.material3.*TopAppBar`，也**禁止**直接用本组件
 *   （页面统一用 [AppPageScaffold]）
 * - 对外暴露的是 slot（navigationIcon / actions），不是一堆 String 参数
 * - 不在这里硬编码任何色值 / 字号 / 圆角，全部走主题 token
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: @Composable (() -> Unit)? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    MediumFlexibleTopAppBar(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}
