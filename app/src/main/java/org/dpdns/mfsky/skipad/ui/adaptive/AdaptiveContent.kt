package org.dpdns.mfsky.skipad.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 大屏上的**内容最大宽度**。
 *
 * 为什么需要它：手机竖屏（Compact，约 360–480dp）时 `fillMaxWidth()` 是合理的，
 * 但同一份代码放到平板横屏（Expanded 840dp+）或桌面窗口（ExtraLarge 1600dp+）上，
 * 一行文字会被拉到 100+ 字符宽 —— 眼球左右扫视幅度过大，**可读性显著下降**。
 *
 * 处理方式不是"换一套布局"，而是**给内容加一个上限并居中**：
 * 布局代码完全不用改，两种宽度下都体面。这是官方"响应式 + 内容优先"的常规做法。
 */
object AppContentWidth {
    /**
     * 单列列表 / 长文：上限取官方 Expanded 断点 840dp。
     * 超过此宽度继续拉伸的收益极小，代价是可读性（眼球左右扫视幅度过大）。
     */
    val Readable: Dp = 840.dp

    /**
     * **表单**：需要比列表更窄。
     *
     * 原因：输入控件一旦拉到 800dp 以上，单行文本字段会显得空旷、标签与输入内容距离过远；
     * 且用户视线在"标签 → 输入框 → 右侧操作"之间来回跳动的距离变长。
     * 640dp 约等于平板竖屏的可用宽度，也是官方表单类页面的常见上限。
     */
    val Form: Dp = 640.dp
}

/**
 * 自适应内容容器：在宽窗口下把内容**限制在 [maxWidth] 内并水平居中**，
 * 在窄窗口下等价于 `fillMaxSize()`（无任何副作用）。
 *
 * 用法：包在 `LazyColumn` / `LazyVerticalGrid` / 表单的外层即可，内部代码一行不用改。
 *
 * ⚠️ 不要用它包**全宽视觉**的页面（如轮播、全屏图片流）—— 那些页面本就该铺满。
 */
@Composable
fun AppAdaptiveContent(
    modifier: Modifier = Modifier,
    maxWidth: Dp = AppContentWidth.Readable,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxHeight(),
        ) {
            content()
        }
    }
}
