package org.dpdns.mfsky.skipad.ui.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.roundedfilled.Arrow_forward

/**
 * 设置行组件 —— 官方 `SegmentedListItem` 的薄包装。
 *
 * 只做参数到官方槽位的映射，内边距、行高、字号、颜色、无障碍语义全部交给官方：
 *
 * | 我们的参数 | 官方槽位 |
 * | --- | --- |
 * | `title` | `content` |
 * | `description` | `supportingContent` |
 * | `icon` | `leadingContent` |
 * | `statusText` / 箭头 | `trailingContent` |
 *
 * [SettingItem] 有 `onClick` 时可点击；[SettingInfoItem] 纯展示，不可交互。
 *
 * ⚠️ 每行都必须由列表容器通过 [LocalSegmentedListItemShapes] 下发分段形状，
 * 脱离容器会退化成"单行"形状。
 *
 * 两处刻意保留：触感反馈（官方组件不做）、`showArrow` / `statusText`
 * （官方把行尾内容统一归到 `trailingContent`，这里收成参数减少样板）。
 */

/**
 * 列表项的容器色 —— 取官方的 **`surfaceContainer`**（分段列表的官方示例值）。
 *
 * ## 依据（material3 1.5.0-beta01 源码，不是凭感觉）
 *
 * | 来源 | 取值 |
 * | --- | --- |
 * | `ListTokens.ItemSegmentedContainerColor`（**token 默认值**） | `Surface` |
 * | 官方示例 `ListSamples.SegmentedListItems` | `MaterialTheme.colorScheme.surfaceContainer` |
 * | 官方 Filled Card（`CardDefaults`） | `surfaceContainerHighest` |
 *
 * token 默认的 `Surface` 与**页面底色**是同一个值（M3 里 `background` ≡ `surface`），
 * 照它做分段列表会"看不见列表"（只能靠 2dp 间隙分辨）—— 所以官方示例显式抬了一档到
 * `surfaceContainer`。**这一档就是列表层该用的取值。**
 *
 * ## ⚠️ 曾经的错误：跟着卡片一起用 `surfaceContainerHighest`
 *
 * 当时的理由是"分段列表看起来就是一叠小卡片，两者不同色会看出列表比卡片浅一档"。
 * 那个判断**是反的**，两个官方事实可以证伪它：
 *
 * 1. `ListTokens.ItemContainerElevation = ElevationTokens.Level0` —— 列表行是 **0dp**，
 *    **不靠阴影**表达抬起，层次**只能靠色阶**；
 * 2. M3 的容器色本身就是**一档一档的层级**：
 *    ```text
 *    页面 surface  →  列表行 surfaceContainer  →  卡片 surfaceContainerHighest
 *    ```
 *    把列表行提到和卡片同色，等于**把层级抹平**：一屏看下来全是"卡片"，
 *    分不出"这是一块内容"还是"这是一行"。
 *
 * ⚠️ 想调层次就改这一处（改完全页一起变），**不要只改某一种容器**；
 *    也不要把卡片一起降下来 —— 卡片的 `surfaceContainerHighest` 是官方 Filled Card 的默认值。
 */
@Composable
private fun segmentedItemColors() = ListItemDefaults.colors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
)

/**
 * **可点击行**（跳转 / 弹窗）与**纯展示行**（`onClick = null`）的统一入口。
 *
 * @param onClick **传 null = 这一行不可交互**：会走 `SegmentedListItem` 的
 *   无交互重载，既不响应点击、也不带点击语义，更没有波纹与触感。
 *
 *   ⚠️ 不要把"不可点击"写成空 lambda `{}` —— 那会落到可点击重载上，
 *   用户点了有反馈却什么都不发生，看起来像界面卡住。
 *   官方交互规范要求：交互反馈必须对应真实动作。
 * @param showArrow 行尾是否显示箭头。有 [statusText] 时以徽章优先。
 * @param statusText 行尾状态徽章文字（如「已开启」「失败」）。传 null 不显示。
 * @param statusPositive 徽章配色：true → `primaryContainer`（"是活的"）；
 *   false → `secondaryContainer`（"待办"）。
 */
@Composable
fun SettingItem(
    title: String,
    description: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    showArrow: Boolean = true,
    statusText: String? = null,
    statusPositive: Boolean = false,
) {
    // 容器下发的分段形状；不在列表容器里时退回单行形状（防御）。
    val shapes = LocalSegmentedListItemShapes.current
        ?: ListItemDefaults.segmentedShapes(index = 0, count = 1)
    val colors = segmentedItemColors()
    val haptic = LocalHapticFeedback.current

    val leading: (@Composable () -> Unit)? = icon?.let { vector ->
        {
            Icon(
                imageVector = vector,
                contentDescription = null,
                // 24dp 是官方 leading icon 的标称尺寸；
                // **颜色不指定** —— 走官方 leadingIconColor（onSurfaceVariant）。
                modifier = Modifier.size(24.dp),
            )
        }
    }

    val trailing: (@Composable () -> Unit)? = when {
        statusText != null -> {
            { StatusBadge(text = statusText, positive = statusPositive) }
        }

        showArrow -> {
            {
                Icon(
                    imageVector = MaterialSymbols.RoundedFilled.Arrow_forward,
                    contentDescription = null,
                )
            }
        }

        else -> null
    }

    val supporting: @Composable () -> Unit = { Text(description) }
    val headline: @Composable () -> Unit = { Text(title) }

    if (onClick == null) {
        // 无交互重载：不响应输入，也不带点击语义。
        SegmentedListItem(
            shapes = shapes,
            modifier = modifier,
            enabled = enabled,
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supporting,
            colors = colors,
            content = headline,
        )
    } else {
        SegmentedListItem(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            },
            shapes = shapes,
            modifier = modifier,
            enabled = enabled,
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supporting,
            colors = colors,
            content = headline,
        )
    }
}

/**
 * **纯展示行** —— [SettingItem] 的"`onClick = null` + 无箭头"形式。
 *
 * 版本号、待处理清单条目都走它：视觉与其它行完全一致，但**不响应点击**。
 */
@Composable
fun SettingInfoItem(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    statusText: String? = null,
    statusPositive: Boolean = false,
) {
    SettingItem(
        title = title,
        description = description,
        onClick = null,
        modifier = modifier,
        icon = icon,
        showArrow = false,
        statusText = statusText,
        statusPositive = statusPositive,
    )
}

/**
 * 行尾状态徽章：圆角小色块 + 文字。
 *
 * 官方 lists specs 里，trailing slot 允许放 "Icon, icon button, or **trailing text**" ——
 * 徽章属于 trailing text，合规。
 *
 * ⚠️ 底色用容器色对而不是 `surfaceContainerHighest`：后者与列表项底色太接近，
 * 徽章会"隐形"（实测踩过）。
 */
@Composable
private fun StatusBadge(text: String, positive: Boolean) {
    val container = if (positive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainer = if (positive) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Text(
        text = text,
        // `labelMedium` 默认就是 Medium 500，不手写 fontWeight。
        style = MaterialTheme.typography.labelMedium,
        color = onContainer,
        modifier = Modifier
            // 走 shapes.small token（8dp），不写 RoundedCornerShape(8.dp) 魔法值。
            .clip(MaterialTheme.shapes.small)
            .background(container)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
