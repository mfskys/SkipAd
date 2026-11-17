package org.dpdns.mfsky.skipad.ui.design.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.dpdns.mfsky.skipad.adskip.AdSkipPermission

/**
 * 权限状态 hook：统一在 `ON_RESUME` 时重读。
 *
 * 用户获得权限必然要跳系统设置再回来，只在首次组合读一次会显示旧状态。
 * 主页与设置页共用这一份实现，避免两处各写一遍 `DisposableEffect`。
 */
@Composable
fun rememberAccessibilityEnabled(refreshKey: Any? = null): Boolean =
    rememberSystemState(refreshKey) { AdSkipPermission.isEnabled(it) }

/** 「后台弹出界面」是否已允许。 */
@Composable
fun rememberBackgroundStartPermission(): Boolean =
    rememberSystemState { AdSkipPermission.isBackgroundStartAllowed(it) }

/**
 * 「读取应用列表」是否可用。
 *
 * ⚠️ 判据是系统的 `QUERY_ALL_PACKAGES`，不是 MIUI「其他权限」页里那个同名开关
 * （那个不暴露 appop，读到的一直是"拒绝"）。
 */
@Composable
fun rememberAppListAllowed(): Boolean =
    rememberSystemState { AdSkipPermission.isAppListAllowed(it) }

/**
 * 「显示在其他应用上层」是否已允许。
 *
 * 自动化期间用它挂 1×1 透明悬浮窗，防止 ROM 把进程冻结（见 `SkipAdAutomationService`）。
 */
@Composable
fun rememberOverlayAllowed(): Boolean =
    rememberSystemState { AdSkipPermission.canDrawOverlays(it) }

/**
 * 「安装未知应用」是否已允许 —— 只有「应用包管理组件 · 资源推荐」那一项依赖它。
 */
@Composable
fun rememberInstallAllowed(): Boolean =
    rememberSystemState { AdSkipPermission.canRequestPackageInstalls(it) }

/**
 * 读一个"要去系统设置里改、回来才会变"的布尔状态，并在 `ON_RESUME` 时重读。
 *
 * @param refreshKey 需要手动重读时把它变一下（例如换一个自增的 Int）。
 *   用 Shizuku 直接打开无障碍时用户没有离开应用，`ON_RESUME` 不会触发。
 */
@Composable
private fun rememberSystemState(
    refreshKey: Any? = null,
    read: (android.content.Context) -> Boolean,
): Boolean {
    val context = LocalContext.current
    // key 变了就重读一次（`remember(key)` 会丢掉旧值、跑一遍初始化）
    var value by remember(refreshKey) { mutableStateOf(read(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                value = read(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return value
}
