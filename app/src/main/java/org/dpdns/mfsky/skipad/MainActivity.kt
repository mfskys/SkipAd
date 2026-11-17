package org.dpdns.mfsky.skipad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.dpdns.mfsky.skipad.adskip.AdSkipRuntime
import org.dpdns.mfsky.skipad.ui.SkipAdApp
import org.dpdns.mfsky.skipad.ui.design.theme.AppTheme
import org.dpdns.mfsky.skipad.ui.design.theme.ThemeMode
import org.dpdns.mfsky.skipad.ui.design.theme.ThemeRepository

/**
 * 唯一 Activity。
 *
 * ⚠️ 这里**刻意不申请通知权限**。
 *
 * `SkipAdAutomationService` 是前台服务，`startForeground` 在 Android 12+ 必须带一个
 * 通知对象（不给会直接抛异常），但**通知能不能显示出来是另一回事**：
 * 没有 `POST_NOTIFICATIONS` 时它只是不显示，服务本身照样是前台组件、照样生效。
 *
 * 所以保活这件事完全交给**悬浮窗**（`SYSTEM_ALERT_WINDOW`）去做，
 * 而"现在需要你做什么"由悬浮窗上的文字直接告诉用户 ——
 * 比一条藏在通知栏里的常驻通知直观得多，也省掉一次权限弹窗。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 日志落盘（`filesDir/logs/skipad.log`）：用户"用着出了问题、回头点复制"时
        // 才拿得到完整轨迹 —— 只存内存的话，进程被 MIUI 杀掉就什么都不剩了。
        AdSkipRuntime.attach(this)
        // edge-to-edge：内容绘制到系统栏之下、系统栏透明。
        // 官方推荐写法：ComponentActivity.enableEdgeToEdge() 已弃用，用 WindowCompat 版本。
        WindowCompat.enableEdgeToEdge(window)
        setContent {
            // 主题模式 = DataStore 里的用户偏好（设置页「外观」分组可切换）。
            // 这里 collect 而不是一次性读取：切模式的瞬间整个 App 立即重组变色。
            val themeMode by ThemeRepository
                .themeMode(applicationContext)
                .collectAsState(initial = ThemeMode.FollowSystem)
            // 只有「跟随系统」才看系统夜间模式；显式选浅/深则覆盖。
            val darkTheme = when (themeMode) {
                ThemeMode.FollowSystem -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // 系统栏图标的明暗必须跟随 **App 实际生效的主题**，而不是系统主题：
            //   浅色主题 -> 深色图标（isAppearanceLightStatusBars = true）
            //   深色主题 -> 浅色图标（isAppearanceLightStatusBars = false）
            // enableEdgeToEdge 的 auto 模式只看系统夜间模式 —— 用户在 App 内
            // 切到与系统相反的主题时，auto 就会与实际背景脱节。
            // 这里用 SideEffect 显式对齐，darkTheme 变化时会自动重新执行。
            SideEffect {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
            AppTheme(darkTheme = darkTheme) {
                SkipAdApp()
            }
        }
    }
}
