package org.dpdns.mfsky.skipad.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.Bolt
import com.composables.icons.materialsymbols.rounded.Settings
import kotlinx.serialization.Serializable

/**
 * 顶层目的地的 NavKey。
 *
 * ⚠️ 必须 @Serializable —— NavKey 的源码注释是硬要求：
 * "Objects and classes that extend this class must be marked with the [Serializable] annotation
 *  in order to be saved with by the [rememberNavBackStack] function."
 * 缺了它返回栈无法在配置变更 / 进程被杀后恢复，且只在运行期暴露。
 */
@Serializable
data object HomeKey : NavKey

@Serializable
data object SettingsKey : NavKey

/**
 * 底部导航栏的两个顶层目的地：主页 / 设置。
 *
 * 图标全部来自 Material Symbols（官方已不推荐 androidx.compose.material.icons，
 * 且 material3 1.4.0 起不再自动传递 material-icons-core）。
 */
enum class TopLevelDestination(
    val key: NavKey,
    val label: String,
    val icon: ImageVector,
) {
    Home(
        key = HomeKey,
        label = "主页",
        icon = MaterialSymbols.Rounded.Bolt,
    ),
    Settings(
        key = SettingsKey,
        label = "设置",
        icon = MaterialSymbols.Rounded.Settings,
    ),
}
