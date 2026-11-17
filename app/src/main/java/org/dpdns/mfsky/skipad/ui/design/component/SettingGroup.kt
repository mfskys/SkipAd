package org.dpdns.mfsky.skipad.ui.design.component

import androidx.compose.material3.ListItemShapes
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 当前行的**分段形状**，由列表容器按行位置下发。
 *
 * `SegmentedListItem` 的 `shapes` 参数是**必传**的，而且它取决于"这一行在组里的位置"
 * （首行 / 中间 / 末行 / 单行）—— 行自己不知道这个信息，只能由容器提供，
 * 所以用 CompositionLocal 传。
 *
 * ## 谁在 provide
 *
 * · 设置页：`SettingsScreen.settingsRow`（一个组 = 标题 item + 每行一个 item）
 * · 主页处理清单：`HomeScreen` 的 `itemsIndexed`
 *
 * 两处都必须用官方的 `ListItemDefaults.segmentedShapes(index, count)` 生成形状 ——
 * 不要自己按 index 算圆角：手写值必然漂（曾经写成 6dp，而官方规范是 4dp inner / 16dp outer）。
 *
 * `null` = 不在容器里（防御），行组件会退回"单行"形状。
 */
val LocalSegmentedListItemShapes = staticCompositionLocalOf<ListItemShapes?> { null }
