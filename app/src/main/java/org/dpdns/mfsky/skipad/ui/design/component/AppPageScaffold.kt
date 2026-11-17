package org.dpdns.mfsky.skipad.ui.design.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll

/**
 * 页面外壳：`Scaffold` + [AppTopAppBar]（`MediumFlexibleTopAppBar`，随滚动折叠）。
 *
 * 底色、顶栏色全部用官方默认值，**一个都不要覆盖**：
 * 页面 `background` 与顶栏展开态 `surface` 同值，展开时是一体的；
 * 滚动折叠后顶栏才切到 `surfaceContainer`。改页面底色会让顶栏或导航栏的边界消失。
 *
 * @param snackbarHost 页面级 Snackbar 宿主。
 * @param content 必须使用传入的 [PaddingValues]，否则会与顶栏重叠。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppTopAppBar(
                title = { Text(title) },
                subtitle = subtitle?.let { { Text(it) } },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = snackbarHost,
        content = content,
    )
}
