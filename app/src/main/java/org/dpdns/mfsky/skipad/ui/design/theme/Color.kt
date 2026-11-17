package org.dpdns.mfsky.skipad.ui.design.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 静态配色：**rainbow 类型（chroma 48）**，由官方配色算法（Material Color Utilities +
 * HCT）从品牌紫 **`#6750A4`** 生成，浅色 / 深色成套。
 *
 * ## ⚠️ 先说清一件事：官方的 `expressiveLightColorScheme()` 并不鲜艳
 *
 * 本文件以前直接用 `expressiveLightColorScheme()`，注释还写着"M3E 重新调校过的
 * 强调色序列" —— **那句是错的**。material3 1.5.0-beta01 源码 `ColorScheme.kt` 原文：
 *
 * ```kotlin
 * public fun expressiveLightColorScheme(): ColorScheme =
 *     lightColorScheme(
 *         // TODO: Replace palette references with color token references when available.
 *         onPrimaryContainer = PaletteTokens.Primary30,
 *         onSecondaryContainer = PaletteTokens.Secondary30,
 *         onTertiaryContainer = PaletteTokens.Tertiary30,
 *         onErrorContainer = PaletteTokens.Error30,
 *     )
 * ```
 *
 * 它只是 `lightColorScheme()` **改了 4 个 `on*Container`** —— primary / primaryContainer
 * 等角色与基线 M3 **完全相同**，而基线用的是 **TonalSpot** 类型：色度（chroma）被刻意
 * 压低（primary 的 chroma 只有 36），所以整屏偏灰、"不够 M3E"。
 *
 * ## ⚠️ 鲜艳度是"色块"的属性，不是"文字"的属性（同一种子 #6750A4 的实测）
 *
 * | 类型 | primary | chroma | 当色块 | 当文字（14sp 小字） |
 * | --- | --- | --- | --- | --- |
 * | TonalSpot | `#65558F` | 36 | 发灰 | 很温和 |
 * | **rainbow（现在用的）** | **`#6750A4`** | **48** | 经典 M3 紫 | **不刺眼** ✓ |
 * | Vibrant | `#6F19FF` | **87** | 好看、够冲 | ✗ **刺眼** |
 * | Expressive | `#006B5A` | 37 | 主色换青绿 | 品牌紫消失 |
 *
 * 这条是实测撞出来的：**先用过 Vibrant（87）**，铺成按钮很好看，但同一条 `primary`
 * 拿去当小字就扎眼 —— 本应用里 `primary` 当文字的地方不少（`复制全部`、`处理清单`、
 * 日志里的成功行）。而官方**没有中间档**（不是 36 就是 87），所以退到 `rainbow`（48）：
 * 够紫、当文字也不刺眼。
 *
 * 若哪天想要更冲的色块：换回 `vibrant` 的同时，必须把这些**文字**退回中性角色
 * （区块标题/正文用 `onSurfaceVariant`，`primary` 只留给色块与图标）。
 *
 * ## 怎么重新生成（**别手改色值**）
 *
 * ```bash
 * cd tools && npm i
 * node gen_m3_scheme.mjs --scheme rainbow --seed '#6750A4' \
 *     --in-place ../app/src/main/java/org/dpdns/mfsky/skipad/ui/design/theme/Color.kt
 * ```
 *
 * `--in-place` 只替换下面两个 `val` 块，本文件的 KDoc 与 `appColorScheme` 原样保留。
 * 工具与取舍依据见 PROJECT.md 3.5.1。
 *
 * ## 深浅两端都成对生成
 *
 * 官方**没有** `expressiveDarkColorScheme`（源码原话：*For dark mode, use
 * [darkColorScheme]*），但深色方案同样由官方算法给出（Vibrant 类型的深色版），
 * 所以下面深色是**显式写的 48 个角色**，不再是 `darkColorScheme()` 的基线值 ——
 * 这样深浅两端的强调色是同一种子、同一类型，切换时不会"变了个应用"。
 */
val AppLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF4F378A),
    inversePrimary = Color(0xFFCFBCFF),
    primaryFixed = Color(0xFFE9DDFF),
    primaryFixedDim = Color(0xFFCFBCFF),
    onPrimaryFixed = Color(0xFF22005D),
    onPrimaryFixedVariant = Color(0xFF4F378A),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF4A4458),
    secondaryFixed = Color(0xFFE8DEF8),
    secondaryFixedDim = Color(0xFFCBC2DB),
    onSecondaryFixed = Color(0xFF1E192B),
    onSecondaryFixedVariant = Color(0xFF4A4458),
    tertiary = Color(0xFF7E5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E3),
    onTertiaryContainer = Color(0xFF633B48),
    tertiaryFixed = Color(0xFFFFD9E3),
    tertiaryFixedDim = Color(0xFFEFB8C8),
    onTertiaryFixed = Color(0xFF31101D),
    onTertiaryFixedVariant = Color(0xFF633B48),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF9F9F9),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFF9F9F9),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFE2E2E2),
    onSurfaceVariant = Color(0xFF474747),
    surfaceTint = Color(0xFF6750A4),
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Color(0xFFF1F1F1),
    outline = Color(0xFF777777),
    outlineVariant = Color(0xFFC6C6C6),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF9F9F9),
    surfaceDim = Color(0xFFDADADA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3F3),
    surfaceContainer = Color(0xFFEEEEEE),
    surfaceContainerHigh = Color(0xFFE8E8E8),
    surfaceContainerHighest = Color(0xFFE2E2E2),
)

/**
 * 深色：与浅色同种子、同类型（Vibrant）的官方深色方案 —— 见 [AppLightColorScheme] 的说明。
 */
val AppDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFCFBCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378A),
    onPrimaryContainer = Color(0xFFE9DDFF),
    inversePrimary = Color(0xFF6750A4),
    primaryFixed = Color(0xFFE9DDFF),
    primaryFixedDim = Color(0xFFCFBCFF),
    onPrimaryFixed = Color(0xFF22005D),
    onPrimaryFixedVariant = Color(0xFF4F378A),
    secondary = Color(0xFFCBC2DB),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    secondaryFixed = Color(0xFFE8DEF8),
    secondaryFixedDim = Color(0xFFCBC2DB),
    onSecondaryFixed = Color(0xFF1E192B),
    onSecondaryFixedVariant = Color(0xFF4A4458),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF4A2532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD9E3),
    tertiaryFixed = Color(0xFFFFD9E3),
    tertiaryFixedDim = Color(0xFFEFB8C8),
    onTertiaryFixed = Color(0xFF31101D),
    onTertiaryFixedVariant = Color(0xFF633B48),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131313),
    onBackground = Color(0xFFE2E2E2),
    surface = Color(0xFF131313),
    onSurface = Color(0xFFE2E2E2),
    surfaceVariant = Color(0xFF474747),
    onSurfaceVariant = Color(0xFFC6C6C6),
    surfaceTint = Color(0xFFCFBCFF),
    inverseSurface = Color(0xFFE2E2E2),
    inverseOnSurface = Color(0xFF303030),
    outline = Color(0xFF919191),
    outlineVariant = Color(0xFF474747),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF393939),
    surfaceDim = Color(0xFF131313),
    surfaceContainerLowest = Color(0xFF0E0E0E),
    surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainer = Color(0xFF1F1F1F),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF353535),
)

/**
 * 实际下发的配色。优先级：动态取色（Material You，API 31+）-> 静态配色（见上）。
 *
 * ## ⚠️ 为什么 [dynamicColor] 默认 false
 *
 * 本项目 `minSdk = 33`，所有能装的设备都满足动态取色的门槛（API 31+）。
 * 若默认开启，下面这个 `when` 的第一条会**永远命中** ——
 * [AppLightColorScheme]（官方算法生成的 Vibrant 方案）永远用不上，
 * 屏幕上变成系统按壁纸算出来的 TonalSpot 配色（**比本方案灰**）。
 *
 * 所以默认走静态方案；想要 Material You 个性化时再打开（深浅两端一起切过去）。
 *
 * 注意：自定义覆盖必须成对使用 ColorScheme 角色，例如
 * primary + onPrimary、primaryContainer + onPrimaryContainer。
 */
@Composable
fun appColorScheme(
    darkTheme: Boolean,
    dynamicColor: Boolean = false,
): ColorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }

    darkTheme -> AppDarkColorScheme
    else -> AppLightColorScheme
}
