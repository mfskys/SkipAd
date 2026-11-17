package org.dpdns.mfsky.skipad.adskip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import org.dpdns.mfsky.skipad.R

/**
 * 自动化期间的**保活服务** —— 三重手段，只为让厂商 ROM 别冻结本进程。
 *
 * ## 为什么需要它（实测踩出来的）
 *
 * 跑自动化时本应用会长时间待在后台：打开系统设置走 9 步、切到别的页面……
 * 这段时间里我们没有可见界面，厂商 ROM（MIUI / HyperOS 等）据此把进程判为
 * **可冻结**，然后把整个 uid 冻住。
 *
 * 被冻结的表现是**所有线程停止调度** —— 日志停在一行、界面完全不动，
 * 连应用自己的看门狗和心跳线程都不再触发：
 *
 * ```text
 * 11:06:59.813  [2/5] 清掉残留弹窗（可选）
 * （之后 24 秒内采样 4 次，一行日志都没有）
 * ```
 *
 * ## 为什么从进程内部查不出来
 *
 * 实测证据（cgroup v2）：
 *
 * ```text
 * /sys/fs/cgroup/apps/uid_10437/cgroup.freeze       = 1   ← uid 级被冻结
 * /sys/fs/cgroup/apps/uid_10437/pid_31885/cgroup.freeze = 0
 * ```
 *
 * 冻结**按层级继承**，所以子目录看着是 0；而进程状态仍是 `S`、
 * 没有 `SIGSTOP`、CPU 占用 0% —— 冻结既不改变线程状态、也不发信号，
 * 因此**从进程内部完全无法自证**，只有从外部读 cgroup 才看得到。
 *
 * 反向验证（铁证）：调 `am start` 把本应用切回前台后，
 * `cgroup.freeze` 立刻变回 `0`，日志**当场恢复**并从 `[2/5]` 续跑到 `[3/5]`。
 * 这就是用户反馈里"手动返回 App 后它自己接着跑"的机制。
 *
 * ## 三重手段
 *
 * | 手段 | 作用 |
 * | --- | --- |
 * | 前台服务 | Android 标准做法。**实测单靠它挡不住 MIUI**（`isForeground=true` 但仍被冻） |
 * | **1×1 透明悬浮窗** | 厂商的冻结判据基本是"还有没有可见窗口"，overlay 正好满足 |
 * | `PARTIAL_WAKE_LOCK` | 让 CPU 在息屏 / 低功耗下保持可用 |
 *
 * 三者都各自 `runCatching`，任何一个失败都不影响自动化本身 ——
 * 顶多退回"可能被冻结"，而不是让会话起不来。
 *
 * ## 它**完全不显示任何东西**
 *
 * ⚠️ 这是刻意的：本服务**不弹通知、不弹提示条**。
 *
 * - **通知**：`startForeground` 在 Android 12+ 必须带通知对象（不给直接抛异常），
 *   但渠道重要性设成了 `IMPORTANCE_NONE`，所以它在系统层面就不会出现；
 * - **悬浮窗**：只有 1×1 像素、全透明、不接收触摸的**保活形态**，
 *   没有任何文字内容 —— 用户不会被任何东西挡住视线或分心。
 *
 * 需要用户操作时，由**界面上**的权限卡片说明（那才是用户会看的地方）。
 *
 * 它也**不监听事件、不读节点、不做任何操作**。
 */
class SkipAdAutomationService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var overlay: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        showKeepAliveOverlay()
        // 系统若因内存压力杀掉本服务，**不要重启** —— 会话状态由引擎自己维护，
        // 乱重启只会留下一个"没有自动化在跑"的悬浮窗。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hideOverlay()
        releaseWakeLock()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // 保活手段
    // -------------------------------------------------------------------------

    /**
     * 挂**保活用**的悬浮窗：1×1 像素、全透明、不接收触摸、不抢焦点。
     *
     * 这是对付厂商冻结的关键 —— 它们的判据通常是"这个应用还有没有可见窗口"，
     * 而自动化必然让 `MainActivity` 进入 stopped（要去看系统设置页），
     * 于是只能从外部补一个窗口。尺寸 1×1 + 全透明 = 肉眼不可见；
     * `FLAG_NOT_TOUCHABLE` = 不拦截任何触摸；`FLAG_NOT_FOCUSABLE` = 不抢焦点。
     *
     * ⚠️ 没有悬浮窗权限时**静默跳过**：那只是"可能被冻结"，
     * 不该阻断流程本身。这一点在权限还没授齐时是常态，不是异常。
     */
    private fun showKeepAliveOverlay() {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(this)) return

        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        runCatching {
            val view = View(this)
            windowManager().addView(view, params)
            overlay = view
        }
    }

    private fun hideOverlay() {
        val view = overlay ?: return
        overlay = null
        runCatching { windowManager().removeView(view) }
    }

    private fun windowManager(): WindowManager =
        getSystemService(WindowManager::class.java)

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SkipAd:automation",
            ).apply {
                setReferenceCounted(false)
                // 兜底上限：正常情况下会话结束就会 release（见 onDestroy）。
                // 万一进程被杀、onDestroy 没跑到，锁也不会永久持有。
                acquire(MAX_WAKE_LOCK_MS)
            }
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        runCatching { if (lock.isHeld) lock.release() }
    }

    // -------------------------------------------------------------------------
    // 通知（建而不显）
    // -------------------------------------------------------------------------

    /**
     * 建通知渠道 —— **重要性设为 `IMPORTANCE_NONE`，也就是根本不显示**。
     *
     * ## 为什么是 NONE 而不是"不申请权限"
     *
     * `startForeground` 在 Android 12+ **必须**带一个通知对象，不给会直接抛异常，
     * 所以通知对象省不掉。但"通知能不能显示"是两件事：
     *
     * - 只靠"不申请 `POST_NOTIFICATIONS`"是不够的 —— 如果用户在某次安装时点过
     *   "允许"，那个授权会**留在这台设备上**（覆盖安装不会撤销），通知照旧会冒出来；
     * - 把渠道重要性设成 `IMPORTANCE_NONE` 才是**系统层面**的关断：
     *   不管有没有权限、不管用户点过什么，通知都不会出现在通知栏。
     *
     * 保活不依赖通知是否可见（前台服务组件照样成立）。
     *
     * ⚠️ 渠道创建后**重要性不允许直接修改**，只能删除重建 —— 所以这里不能只判断
     * "渠道是否存在"，还要比对重要性，否则老版本建好的 LOW 渠道会一直留着。
     */
    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        // 清掉旧版本留下的渠道 —— 它建的时候是 LOW，会真的往通知栏弹东西。
        // ⚠️ 用**新的渠道 id**（`CHANNEL_ID`）而不是复用旧 id：渠道一旦被用户手动
        //    调整过，删除重建后重要性仍会被"用户偏好"顶回去，换个 id 才是干净的。
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }

        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null && existing.importance == NotificationManager.IMPORTANCE_NONE) return

        if (existing != null) {
            runCatching { manager.deleteNotificationChannel(CHANNEL_ID) }
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "后台处理",
                NotificationManager.IMPORTANCE_NONE,
            ).apply {
                description = "自动处理期间用于保活，不显示任何提醒"
                setShowBadge(false)
            },
        )
    }

    /** 只是 `startForeground` 的合规凭据，永远不会被用户看到（渠道是 NONE）。 */
    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("正在自动处理")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    companion object {
        /** 当前渠道 id。带上 `_silent` 后缀是为了和旧版本那个会弹通知的渠道区分开。 */
        private const val CHANNEL_ID = "skipad_automation_silent"

        /** 旧版本（会弹通知的）渠道 id，启动时顺手清掉。 */
        private const val LEGACY_CHANNEL_ID = "skipad_automation"
        private const val NOTIFICATION_ID = 1
        private const val MAX_WAKE_LOCK_MS = 30 * 60 * 1000L

        /**
         * 会话开始时调用（幂等）。
         *
         * ⚠️ 用 `startForegroundService` 而不是 `startService`：
         *    Android 8 起应用在后台时不允许直接 `startService`，
         *    而这个调用恰好发生在**用户刚点完按钮、界面即将被系统设置顶掉**的时刻。
         *
         * ⚠️ 外层 `runCatching`：厂商 ROM 对前台服务另有拦截。
         *    **失败绝不影响自动化本身** —— 顶多退回"可能被冻结"的状态。
         */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, SkipAdAutomationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { e ->
                // 保活失败不致命，但会在"进程被冻结"时表现为日志突然中断 —— 必须留痕。
                AdSkipRuntime.log(
                    "前台服务启动失败（继续处理，但可能被系统冻结）：" +
                        "${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }

        /** 会话结束时调用（幂等）。 */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, SkipAdAutomationService::class.java))
            }.onFailure { e ->
                AdSkipRuntime.log("前台服务停止失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
