package org.dpdns.mfsky.skipad.ui.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * App 实际生效的深浅色（**不是**系统夜间模式）。
 *
 * 用户在设置里显式选择时可能与系统值相反；任何"按当前是否深色"分支
 * （自定义绘制等）都应该读这个，而不是 `isSystemInDarkTheme()`。
 */
val LocalAppDarkTheme = staticCompositionLocalOf { false }

/**
 * 应用唯一主题入口。
 *
 * 用 [MaterialExpressiveTheme] 而不是 `MaterialTheme`：M3E 的 motionScheme
 * 只在这个入口下与 Expressive 组件的动画对齐。
 *
 * @param dynamicColor 是否启用 Material You 动态取色。默认关闭 ——
 *   本应用展示的是 M3E 自己的色板，而不是壁纸取色。
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = appColorScheme(
            darkTheme = darkTheme,
            dynamicColor = dynamicColor,
        ),
        typography = AppTypography,
        shapes = AppShapes,
        motionScheme = AppMotionScheme,
    ) {
        CompositionLocalProvider(
            LocalAppDarkTheme provides darkTheme,
        ) {
            content()
        }
    }
}
