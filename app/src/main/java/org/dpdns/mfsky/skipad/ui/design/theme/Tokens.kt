package org.dpdns.mfsky.skipad.ui.design.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 排版：M3 基线 15 档 + M3E 的 15 个 emphasized 变体（`Typography()` 默认值）。 */
val AppTypography: Typography = Typography()

/**
 * 形状：M3E 八档圆角（M3 五档 + M3E 新增三档）。
 *
 * 4 / 8 / 12 / 16 / 20 / 28 / 32 / 48dp。
 * ⚠️ `extraLarge` 是 28dp（不是旧 M3 文档常写的 24dp）。
 *
 * 纪律：业务代码只用这八档，禁止 `RoundedCornerShape(7.dp)` 这类魔法值。
 */
val AppShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    largeIncreased = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)

/** 动效：统一 expressive；自定义动画从 `MaterialTheme.motionScheme` 取 spec。 */
val AppMotionScheme: MotionScheme = MotionScheme.expressive()

/**
 * 三张卡片的容器色 —— 与列表行、导航栏同档（`surfaceContainer`）。
 *
 * 不用 Filled Card 的默认 `surfaceContainerHighest`：主页是"卡片 + 列表行"混排，
 * 卡片重一档会显脏；区分靠形状与结构，不靠色阶。
 *
 * ⚠️ 所有卡片都必须用它，别各写 `CardDefaults.cardColors()`。
 */
@Composable
fun appCardColors(): CardColors =
    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

/**
 * 庆祝撒花的颜色：品牌三色 + 主题紫。
 *
 * ⚠️ 这些不是 M3 角色，是品牌色；业务代码只引用这个列表，不写裸色值。
 */
val AppCelebrationColors: List<Color> = listOf(
    Color(0xFF32CD32), // 绿
    Color(0xFFFF6900), // 橙
    Color(0xFFF52E2E), // 红
    Color(0xFF6750A4), // 主题紫
)
