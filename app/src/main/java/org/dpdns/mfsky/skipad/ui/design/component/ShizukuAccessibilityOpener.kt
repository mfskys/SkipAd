package org.dpdns.mfsky.skipad.ui.design.component

import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import org.dpdns.mfsky.skipad.util.ShizukuAccessibility
import org.dpdns.mfsky.skipad.util.ShizukuShell
import rikka.shizuku.Shizuku

/**
 * 「点一下就把无障碍开起来」—— 主页的「去开启无障碍」和设置页的「无障碍服务」
 * 是**同一个动作**，所以共用这一个 hook（和 [rememberSystemState] 当初一样，
 * 两个页面各写一份、改一处漏一处是迟早的事）。
 *
 * ## 三个分支（本机装没装 / 授权没授权）
 *
 * | 本机状态 | 点下去会发生什么 |
 * | --- | --- |
 * | 没装 Shizuku，或装了但**没运行** | 走 [onFallback]：打开系统无障碍设置页（= 老路子）|
 * | 在运行但**没授权** | 弹 **Shizuku 自己的**授权框 → 授权后**自动继续**开无障碍 |
 * | 已授权 | 直接开，**全程不离开本应用** |
 *
 * ⚠️ 被拒绝授权时也要明确说一句：否则用户点了按钮什么都没发生，只会以为程序坏了。
 *
 * ## 为什么开完要回调 [onEnabled]
 *
 * `rememberAccessibilityEnabled` 只在 `ON_RESUME` 时重读，而这条路**不会离开前台** ——
 * 所以调用方要用它把那个状态**手动刷一次**（变一下 `refreshKey`）。
 * 刷完首页那段"无障碍刚开启 → 自动接上第 2~4 步"的 `LaunchedEffect` 就会自然触发，
 * 用户不用再点任何东西。
 *
 * @param onEnabled 无障碍已经**真的开好了**（调用方据此刷新状态）。
 * @param onMessage 给用户的一句反馈（调用方用 Snackbar 弹）。
 * @param onFallback 降级动作：打开系统无障碍设置页。
 * @return 直接当 `onClick` 用的 lambda。
 */
@Composable
fun rememberShizukuAccessibilityOpener(
    onEnabled: () -> Unit,
    onMessage: (String) -> Unit,
    onFallback: () -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    // 只为拿 applicationId：要开的是**本包**的服务（debug 变体带 `.debug` 后缀）。
    val context = LocalContext.current

    // ⚠️ 回调要取"最新的那个"：下面那个 DisposableEffect 只在首次组合时注册监听器，
    //    它会一直持有**第一次**捕获的 lambda —— 而调用方每次重组都会传新的进来。
    val currentOnEnabled by rememberUpdatedState(onEnabled)
    val currentOnMessage by rememberUpdatedState(onMessage)
    val currentOnFallback by rememberUpdatedState(onFallback)

    /** 正在等 Shizuku 的授权结果（点了拒绝就清掉，别影响下一次）。 */
    var awaitingGrant by remember { mutableStateOf(false) }

    /** 真正的动作：跑命令 → 成功就回调、失败就说明并降级。 */
    val enable: () -> Unit = {
        scope.launch {
            val error = ShizukuAccessibility.enable(context)
            if (error == null) {
                currentOnEnabled()
            } else {
                currentOnMessage(error)
                currentOnFallback()
            }
        }
    }

    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            // 只认自己那一次请求：Shizuku 的授权结果里没有"谁在等"的信息。
            if (requestCode == ShizukuShell.REQUEST_CODE && awaitingGrant) {
                awaitingGrant = false
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    enable()
                } else {
                    currentOnMessage("Shizuku 授权被拒绝，已改为打开系统设置")
                    currentOnFallback()
                }
            }
        }
        // ⚠️ runCatching 包住：没装 Shizuku 时这些调用会抛（类不存在/无 binder），
        //    而"没装"恰恰是我们要优雅降级的正常情况之一。
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
        onDispose { runCatching { Shizuku.removeRequestPermissionResultListener(listener) } }
    }

    return {
        when {
            !ShizukuShell.isRunning() -> {
                currentOnMessage("没有可用的 Shizuku，已改为打开系统设置")
                currentOnFallback()
            }

            !ShizukuShell.isAuthorized() -> {
                awaitingGrant = true
                if (!ShizukuShell.requestPermission()) {
                    awaitingGrant = false
                    currentOnMessage("无法请求 Shizuku 授权，已改为打开系统设置")
                    currentOnFallback()
                }
            }

            else -> enable()
        }
    }
}
