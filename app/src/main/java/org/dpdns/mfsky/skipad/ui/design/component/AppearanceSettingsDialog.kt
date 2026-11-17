package org.dpdns.mfsky.skipad.ui.design.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.roundedfilled.Palette
import org.dpdns.mfsky.skipad.ui.design.theme.ThemeMode

/**
 * # 外观设置弹窗
 *
 * 形态：**`AlertDialog`（不是 ModalBottomSheet）**，里面用 M3E 的连接式按钮组做单选。
 *
 * ## 三条要点
 *
 * 1. **用官方 `ButtonGroup` + `toggleableItem`，不要拿 `FilterChip` 或裸 `ToggleButton` 拼**：
 *    形状由容器按组内位置决定（首/中/尾自动连接），按下时该项按 `expandedRatio` 放大、
 *    邻项等比压缩 —— 这是组级编排，`Row` + 裸按钮拿不到。
 * 2. **选中态靠按钮组自身的形状/配色表达，不插勾**：插勾会让文案横向位移，
 *    而且勾没有入场动画，反而比颜色变化更"跳"。
 * 3. **临时 state + 确定才落盘**：`tempThemeMode` 是局部状态，
 *    点「确定」才回调；「取消」或点外部直接丢弃。所以用户在里面怎么点都不会影响全局，
 *    不存在"点一下闪一下"。
 *
 * 按钮层级：**确定用实心（filled）= 本次操作的最终动作**，取消用描边（outlined）
 * = 只是退出路径。两个按钮若同等强调会互相抢注意力。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsDialog(
    currentThemeMode: ThemeMode,
    onDismiss: () -> Unit,
    onConfirm: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 临时态：确定才提交，取消即丢弃（对话框内怎么点都不影响全局）
    var tempThemeMode by remember(currentThemeMode) { mutableStateOf(currentThemeMode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = "外观设置",
                // M3E emphasized 字阶，不手写 fontWeight。
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ── 外观模式 ──
                SectionLabel("外观模式")
                val modeItems = listOf(
                    ThemeMode.FollowSystem to "跟随",
                    ThemeMode.Light to "浅色",
                    ThemeMode.Dark to "深色",
                )
                ButtonGroup(
                    // 本组只有三项、永远放得下，不需要"更多"溢出菜单
                    overflowIndicator = { _ -> },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    modeItems.forEach { (mode, label) ->
                        toggleableItem(
                            checked = tempThemeMode == mode,
                            label = label,
                            onCheckedChange = { tempThemeMode = mode },
                            weight = 1f,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(tempThemeMode) }) {
                Text("确定")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                // 描边按钮文字取主题主色，与「确定」的实心底色同源 ——
                // 两者一眼能看出属于同一组动作，而不是一个彩色一个灰。
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("取消")
            }
        },
    )
}

/** 弹窗内的分区标签：小图标 + 主色小标题。 */
@Composable
private fun SectionLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = MaterialSymbols.RoundedFilled.Palette,
            contentDescription = null,
            // ⚠️ 图标尺寸只有三个官方档位：**列表 24dp**（`ListTokens.ItemLeadingIconSize`）、
            //    **chip 18dp**（`AssistChipTokens.IconSize`）、以及 40/48dp 的大图标。
            //    这里是"小图标 + 文字标签"（与 chip 同构），所以取 **18dp**；
            //    原来写的 16dp 不在任何官方档位上（实测对比会显得比 chip 里的图标小一圈）。
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            // M3E emphasized 字阶（Bold）—— 与弹窗里其它"强调"用法同一套 token。
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
