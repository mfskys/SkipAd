package org.dpdns.mfsky.skipad.adskip

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.graphics.Rect
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 一个任务的执行结果。 */
internal sealed interface RunnerResult {
    /** 任务完成（关开关类任务包含"本来就是关的"这种情况）。 */
    data class Completed(val message: String) : RunnerResult

    /** 任务失败，**已中止**，不会再往下点。 */
    data class Failed(
        val stepIndex: Int,
        val stepLabel: String,
        val reason: String,
        /**
         * 是否值得**从头重跑整个任务**。
         *
         * 只有**可恢复**的情况才为 true：
         * - "走偏"（用户中途开了别的页面）—— 重跑一遍通常就好；
         * - 等异步结果超时（搜索结果要联网）—— 重跑一次可能刚好赶上。
         *
         * "页面没出来"这类保持 false：重跑还是会停在同一个地方，白闪几次屏幕。
         *
         * ⚠️ 标了 true 也**不保证**一定会重跑：引擎还会看"上一轮是不是也挂在
         *    这一步"，是的话直接判失败。理由见 `AdSkipRuleEngine.lastRetryFailStep`。
         */
        val retryable: Boolean = false,
    ) : RunnerResult
}

/**
 * # 步骤执行引擎（全自动）
 *
 * 逐个执行 [AdTask.steps]，每一步都**必须真的到位**才进入下一步。
 *
 * ## 为什么需要"重试 + 超时"这一层
 *
 * 全自动最容易出事的不是"点不到"，而是**时序**：点完搜索框搜索页要几百毫秒才出来、
 * 输完关键字结果列表要联网查询才渲染、点完结果详情页的开关状态是异步填充的。
 * 如果点完就立刻执行下一步，会在**还没出现的页面上乱找节点**。
 *
 * 所以每一步都有独立的截止时间：
 * - 页面有变化（新事件）→ 立刻重试一次；
 * - 页面没变化 → 每 [RETRY_INTERVAL_MS] 再试一次（有些状态变化不发事件）；
 * - 超过该步的 `timeoutMs` → 判定失败、中止、如实上报。
 *
 * ⚠️ **失败绝不跳过**。宁可这一项没做成、让用户看到红色报告，
 * 也不能"猜着往下点" —— 那是在别人的手机上乱点。
 *
 * ## 四层冗余（从轻到重）
 *
 * 全自动最怕"某一次交互没生效"，所以这里按代价从低到高铺了四层兜底：
 *
 * | 层 | 时机 | 代价 |
 * |---|---|---|
 * | 0. **多候选匹配** | 每一步都可能：一路条件不中就用下一路 | 零，且**不消耗时间** |
 * | 1. **结果验证** | 写入后读回内容、关开关后读回状态 | 几乎为零 |
 * | 2. **原地重发输入** | [AutomationStep.AwaitFor.retryPreviousInput]：等了一半还没结果就重写一遍 | 很轻，屏幕不跳 |
 * | 3. **整个任务重跑** | [RunnerResult.Failed.retryable]：上面都没成，从第 1 步重来 | 最重，屏幕会跳一下 |
 *
 * 设计意图是**先用便宜的手段解决问题**：搜索结果没出来，十有八九只是"那一次输入
 * 没能触发查询"，而不是任务走错了 —— 那就没必要重新打开设置、重新点搜索框。
 *
 * ## 防呆：走偏了就别傻等
 *
 * 光靠超时是不够的：用户中途去开了别的设置页时，脚本会**杵在错误页面上等满 5 秒**
 * 才失败，体验很差。所以这里额外做了一层判断 —— 当 [AdTask.allowedPackages] 非空时，
 * 一旦发现前台跑到了任务路径之外的 App，**立刻**判定为可恢复失败并上报，
 * 由上层把整个任务从头重跑一遍（见 `AdSkipRuleEngine.onTaskResult`）。
 */
internal class AutomationRunner(
    private val service: AccessibilityService,
    private val clicker: NodeClicker,
    private val onResult: (AdTask, RunnerResult) -> Unit,
    private val onProgress: (AdTask, Int, AutomationStep) -> Unit,
) {

    private companion object {
        /** 无论有没有新事件，每隔这么久重试一次当前步骤。 */
        const val RETRY_INTERVAL_MS = 300L

        /**
         * 无障碍事件触发"立刻重试"的最小间隔。
         *
         * ⚠️ 这道节流是**必须的**：`TYPE_WINDOW_STATE_CHANGED` 在页面过渡期间
         * 每秒能来几十条，而每条都会走到 [attemptCurrentStep] → [isOffTrack]
         * → `service.rootInActiveWindow`（**跨进程调用**）。不节流的话主线程
         * 会被彻底占满，消息队列越堆越长 —— 现象是 `handler.post` 的回调、
         * 看门狗、乃至日志**全部排不上队**，界面看起来完全卡死。
         *
         * 实测症状：跨应用启动「信息流设置」后，日志断在"正在启动"再无下文，
         * 而页面其实早就打开了（前台就是它）、进程也活着。
         *
         * 100ms 足够及时响应页面变化，又不会让事件洪流淹没主线程。
         */
        const val PAGE_CHANGED_THROTTLE_MS = 100L

        /**
         * 按返回键后，等页面切换完成的时间。
         *
         * 取早了没用（`performGlobalAction` 只代表"事件已注入"，页面还在动画中），
         * 取晚了只是稍慢一点 —— 所以一个覆盖常见切换动画的值就够了。
         */
        const val BACK_SETTLE_MS = 500L

        /**
         * 失败快照最多输出多少个节点。
         *
         * 比 [PageSnapshot] 默认的 40 大得多：诊断模式会把**容器**也输出，
         * 40 个往往连列表都铺不完就截断了，等于白拍。
         */
        const val FAIL_SNAPSHOT_MAX_NODES = 250

        /**
         * [AutomationStep.AwaitFor] 的等待上限 —— 脚本写的值再大也**不会超过它**。
         *
         * ## 为什么必须有这道闸
         *
         * 脚本里为了"跨应用冷启动、联网查询"留了不少长超时（20 / 30 秒）。这些值
         * 单个看是合理的，但**失败一次会重跑整个任务**，于是 30 秒被放大成
         * 30 × 3 = 90 秒的空白等待 —— 用户看到的就是"卡在那里不动，最后还没成"。
         *
         * 15 秒足够覆盖冷启动 + 联网；超过它基本意味着"这个页面根本不会来"，
         * 那早点判定失败、早点把屏幕还给用户才是对的。
         *
         * ⚠️ 上限只对 [AutomationStep.AwaitFor] 生效。`ToggleOffAll` / `ScrollTo`
         *    天生是"多轮操作"（几十秒是正常进度），它们的超时另算，见 [stepTimeout]。
         */
        const val MAX_STEP_WAIT_MS = 15_000L

        /**
         * 防呆判断的宽限期。
         *
         * 刚发出动作时（比如刚 Launch 设置）前台还停留在旧页面上，
         * 立刻判断会误报"走偏"。给页面切换留一点时间。
         */
        const val OFF_TRACK_GRACE_MS = 1_500L

        /**
         * 扫「打开其他应用」确认弹窗的最小间隔。
         *
         * 这条检查挂在 [attemptCurrentStep] 上（每个 tick、每条无障碍事件都会跑），
         * 而它要遍历**全部窗口**的节点树 —— 不节流的话主线程会被拖垮
         *（理由同 [PAGE_CHANGED_THROTTLE_MS]）。
         */
        const val JUMP_DIALOG_SCAN_INTERVAL_MS = 300L

        /**
         * 弹窗**刚出现**时，给当前步骤补的时间。
         *
         * 弹窗是模态的，它挡着目标页面 —— 不补时间的话，一个 5 秒的步骤
         * 光等它就耗掉大半，用户看到的是"卡一下然后报失败"。
         *
         * ⚠️ 只在 `不在场 → 在场` 的翻转时补，且单步有 [JUMP_DIALOG_MAX_EXTENDS] 上限，
         *    所以它**不会**把超时变成无限等待。
         */
        const val JUMP_DIALOG_EXTEND_MS = 5_000L

        /** 单步内最多因弹窗补几次时间（防止"弹窗赖着不走 + 一直补时间"变成僵死）。 */
        const val JUMP_DIALOG_MAX_EXTENDS = 3

        /**
         * 同一个弹窗最多点几次。
         *
         * 点不动就**如实失败**，不在原地无限点 —— 这是本项目的红线
         *（"失败绝不跳过"，也不做无法收敛的重复动作）。
         */
        const val JUMP_DIALOG_MAX_CLICKS = 3

        /** 两次点弹窗之间的最小间隔：手势是异步的，连点会被系统拒。 */
        const val JUMP_DIALOG_CLICK_INTERVAL_MS = 600L

        /**
         * 刚发出 [AutomationStep.Launch] 之后的"弹窗观察窗口"。
         *
         * ⚠️ 这期间 [handleJumpDialog] 的"快速筛"要**失效**。
         *
         * 原因是时序：`Launch` 发出请求的那一刻，前台往往还停在**旧页面**
         *（会话刚开始时是本应用自己），而弹窗要过一两百毫秒才顶上来。
         * 快速筛只看"前台包名"的话，恰恰会在最该看的那一瞬间把检查短路掉。
         */
        const val JUMP_DIALOG_WATCH_MS = 3_000L

        /**
         * 点击前的稳定性观察时间。
         *
         * 命中节点后要**连续存在**这么久才真的点下去：搜索结果列表是异步渲染的，
         * 先铺骨架 / 旧内容、再被真实数据替换。抢在那个窗口里点击，
         * 点到的往往是一个即将失效的节点 —— 现象就是"点了没反应，然后卡住"。
         */
        const val CLICK_SETTLE_MS = 400L

        /**
         * 点开关后，**至少**等这么久才做第一次"读回确认"。
         *
         * ⚠️ 这一条是"关了又开"那个 bug 的修复。无障碍的节点树靠系统发事件更新，
         *    而点完开关后最先到达的那个事件可能**早于树完成刷新**
         *   （事件触发的重试只隔 [PAGE_CHANGED_THROTTLE_MS]，那才 100ms）——
         *    此时读回拿到的是**点击前的旧值**，于是被误判成"没点上"，
         *    走下面那个"再补一次点击"，把**刚关掉的开关又点开了**。
         *
         *    实测症状：开关先关、紧接着自己又打开（用户原话"点击关闭了，然后又打开了"）。
         *
         *    取 700ms：比切换动画（约两三百毫秒）宽一截，又远小于步骤超时。
         */
        const val TOGGLE_SETTLE_MS = 700L

        /**
         * 读回时**连续**读到相反状态多少次，才认定"真的没点上"、才补点。
         *
         * ⚠️ 与 [TOGGLE_SETTLE_MS] 是两道**独立**的闸：冷却期防"读得太早"，
         *    这一条防"冷却期后偶然仍读到旧值"。两道都过了才动手补点。
         *    代价是真需要补点时慢 [TOGGLE_VERIFY_STREAK] × 轮询间隔，
         *    相对于"把用户的开关点坏"完全可以接受。
         */
        const val TOGGLE_VERIFY_STREAK = 2

        /**
         * "原地重发输入"这种轻量补救最多做几次。
         *
         * 配合 [AutomationStep.AwaitFor.retryPreviousInput] 使用：该步的超时时间
         * 会被均分成 (n+1) 段作为触发点，所以 2 次 = 12 秒的等待里
         * 会在第 4 秒、第 8 秒各补救一次。
         */
        const val MAX_INPUT_RECOVERY = 2

        /** 读开关状态时向下搜索的节点数上限。 */
        const val MAX_SWITCH_SEARCH_NODES = 60

        /**
         * 两次向下滚动之间的最小间隔。
         *
         * 滚动是异步的，页面要一两帧才滑到位。间隔太小会**连滑好几屏**、
         * 直接冲过目标；400ms 既够一屏滑完，又不至于让"滚动查找"慢得难受。
         */
        const val SCROLL_INTERVAL_MS = 400L

        /** 找可滚动容器时遍历的节点数上限。 */
        const val MAX_SCROLL_SEARCH_NODES = 300

        /**
         * [AutomationStep.ScrollTo] 连续多少轮"内容完全没变"就判定**真的到底了**。
         *
         * ⚠️ 判据必须是"内容没变"，**不能**是"`ACTION_SCROLL_FORWARD` 返回 false"：
         * 那个返回值和"列表到底了"根本不是一回事（见坑 #23），而更坑的是
         * 它**返回 true 也可能几乎没动**（见坑 #40）。
         */
        const val MAX_SCROLL_STUCK = 3

        /** 取滚动容器"内容指纹"时遍历的节点数上限（只是防跑飞）。 */
        const val MAX_FINGERPRINT_NODES = 200

        /**
         * [AutomationStep.ScrollTo] 连续找不到可滚容器多少次才判"这个页面真的不可滚动"。
         *
         * ⚠️ **不能一找不到就失败**。实测（「所有应用」页点完「显示系统应用」）：
         * 那一下会把列表**清空重载** —— 快照里是 `loading_container` +
         * `正在加载...`、`apps_list_container childCount=0`（RecyclerView 还没重建）。
         * 而当时那句判定在**步骤开始后 77ms** 就报"页面不可滚动，且未找到目标"，
         * 把一次本来能成的滚动查找判死了。
         *
         * 这和坑 #23 是同一个错误：把"这一刻的状态"当成"永远的状态"。
         * 按 [SCROLL_INTERVAL_MS] 的节流算，8 次 ≈ 3 秒 —— 给重新加载留足余量；
         * 而"真的不可滚动"时多等这 3 秒无所谓（反正下一步也是失败）。
         */
        const val MAX_SCROLL_NO_CONTAINER_RETRIES = 8

        /**
         * "读不到界面"时打日志的间隔。
         *
         * ⚠️ 需要这个状态是因为实测遇到过一次**很难解释但真实**的情形：
         * 页面明明开着（`uiautomator dump` 读得到「允许通知」和 `id=switchWidget`），
         * 而引擎那 10 秒里 `rootInActiveWindow` **一直是 null**、
         * `service.windows` 里只剩状态栏 —— 于是所有匹配都落空，
         * 日志却只有一串"仍在等待…"，看不出是"页面没到"还是"界面读不到"。
         */
        const val UNREADABLE_LOG_INTERVAL_MS = 3_000L

        /**
         * 因为"读不到界面"而超时，最多再等这么久（**每个步骤只延一次**）。
         *
         * 直接判失败会把前面滚几十屏的成果全扔掉，还会触发整项重跑
         *（重跑一次好几十秒）。而这种情况往往过一会儿就恢复了。
         */
        const val MAX_UNREADABLE_EXTEND_MS = 10_000L

        /**
         * [AutomationStep.ToggleOffAll] 的步骤超时。
         *
         * 按"最多滚 [AutomationStep.ToggleOffAll.maxSwipes] 屏、每屏若干开关"估：
         * 每个开关一次点击约 200~400ms，一屏十来个就是四五秒，再乘以滚动次数。
         * 90 秒足够覆盖，而且它只是**上限** —— 正常页面（开关不多）会提前 `advance`。
         *
         * ⚠️ 不能用默认那 5 秒：实测钱包那页刚关掉几个就撞上超时，
         *    表现是"才关了几个就报失败"。
         */
        const val TOGGLE_ALL_TIMEOUT_MS = 90_000L

        /**
         * [AutomationStep.ScrollTo] 的步骤超时。
         *
         * 每次滚动之间有 [SCROLL_INTERVAL_MS] 节流，还要等目标渲染出来；
         * 滚满 `maxSwipes`（默认 6）就是 2.4 秒起步，加上查找与页面加载，
         * 默认那 5 秒太紧。
         *
         * ⚠️ 60 秒是**按最坏情况**给的：有的脚本会把 `maxSwipes` 开到 45
         *（那页有 130+ 个应用，实测「智能服务」在列表最末尾），
         * 45 × 节流 400ms ≈ 18 秒，再加上每轮的内容指纹扫描 —— 30 秒会紧。
         * 它只是**上限**：找到就 `advance`、判定到底就 `fail`，都不会等满。
         */
        const val SCROLL_TO_TIMEOUT_MS = 60_000L

        /**
         * [AutomationStep.ToggleOffAll] **连续**滚动失败多少次才认定"已到页面底部"。
         *
         * ⚠️ 之所以不是"失败一次就算到底"：`ACTION_SCROLL_FORWARD` 在上一次点击
         * 手势还没跑完时会被拒绝，返回 false —— 那一刻**并不代表到底**。
         * 实测踩到过：滚动只成功一次就被判定到底，而屏幕上还有一大半开关没关。
         *
         * 每次重试之间隔着 [SCROLL_INTERVAL_MS]，3 次足够让挂起的手势跑完。
         */
        const val MAX_SCROLL_FAILS = 3

        /**
         * [AutomationStep.ToggleOffAll] 两次**节点树扫描**之间的最小间隔。
         *
         * 见 `doToggleOffAll` 开头的说明：它是为了别把主线程拖垮 ——
         * 而主线程一卡，手势派发和滚动动作反而更容易被系统拒绝。
         */
        const val TOGGLE_ALL_SCAN_INTERVAL_MS = 200L

        /**
         * [AutomationStep.ToggleOffAll] 每轮扫描的节点数上限。
         *
         * 比别处大不少 —— 这一页可能有二十多个开关，加上各自的容器和文字，
         * 节点数远高于普通页面。上限只是防跑飞，正常页面用不到。
         */
        const val MAX_TOGGLE_ALL_NODES = 900

        /**
         * `Launch` 步骤等待 `startActivity` 返回的上限。
         *
         * 正常几十毫秒就返回；实测跨应用启动时可能**长时间不返回**，
         * 所以必须有个上限（见 [doLaunch] 的看门狗）。
         */
        const val LAUNCH_TIMEOUT_MS = 3_000L

        /**
         * **可选步骤**（[AutomationStep.Click.optional]）的等待上限。
         *
         * 这类步骤"没出现"才是常态，没必要等满默认时长 ——
         * 1.5 秒足够让一个已经存在的弹窗渲染出来。
         */
        const val OPTIONAL_STEP_TIMEOUT_MS = 1_500L

        /**
         * [AutomationStep.AwaitFor.optional] 的等待上限。
         *
         * 比 [OPTIONAL_STEP_TIMEOUT_MS] 宽得多：它等的是**整页切换**
         * （跨应用点击 + 页面渲染），1.5 秒经常不够；而它等不到时
         * 会跳过**一整段**后续步骤，判错一次的代价也更大。
         *
         * 但也不能再宽：一个任务里可能连着好几个"可选等待"
         * （搜索任务就有 4 个子页），每个都拖 5 秒以上就太慢了。
         */
        const val OPTIONAL_AWAIT_TIMEOUT_MS = 3_000L

        /**
         * 长时间等待（[AutomationStep.AwaitFor]）时，"仍在等待"日志的间隔。
         *
         * 冷启动别的应用时实测等过 **29 秒** —— 这期间界面与日志全无动静，
         * 用户会以为程序卡死（实测反馈原话："还是没有去点击啊"）。
         * 定期报一次进度，让等待**可见**。
         */
        const val WAIT_LOG_INTERVAL_MS = 3_000L
    }

    private val handler = Handler(Looper.getMainLooper())

    // -------------------------------------------------------------------------
    // 诊断：主线程心跳
    // -------------------------------------------------------------------------

    /**
     * 独立线程的心跳 —— 用来判断**主线程是否还活着**。
     *
     * ## 为什么必须另起线程
     *
     * 本类所有关键动作都在主线程上跑：无障碍事件处理（[onPageChanged]）、
     * `handler.post` 的回调、[retryTick]，以及 `AdSkipRuntime.log` 本身。
     * 一旦主线程被某个长阻塞占住，日志会**整体消失** ——
     * 现象是"日志停在某一行、界面不动、等多久都没有后续"，
     * 而主线程**无法自证**（连打日志的机会都没有）。
     *
     * 这个线程每秒给主线程投一个空任务；若投不进去（主线程没消化），
     * 说明它被占住了 —— 直接打到 logcat（绕过 `AdSkipRuntime`，因为那也走主线程）。
     *
     * 只在"状态翻转"时打印，不会刷屏。
     */
    /** 本次心跳的投递时刻；由主线程在消费时用来算**往返延迟**。 */
    @Volatile
    private var lastPostAtMs = 0L

    /** 主线程最近一次消费心跳的往返延迟（ms）。 */
    @Volatile
    private var lastAckLagMs = 0L

    private var heartbeatBlocked = false

    init {
        lastPostAtMs = SystemClock.uptimeMillis()
        Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(1_000L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val now = SystemClock.uptimeMillis()
                // ⚠️ 必须记下**本次**投递时刻、由主线程消费时算往返延迟。
                //    若改用"距上一次 ack 的间隔"，那个值恒等于心跳周期本身
                //    （看到的是稳定的 ~1000ms，像一直卡顿，其实完全正常）。
                lastPostAtMs = now
                handler.post { lastAckLagMs = SystemClock.uptimeMillis() - lastPostAtMs }
                val blocked = lastAckLagMs >= 2_000L
                // ⚠️ 只在"开始阻塞 / 恢复正常"时说话，平时一声不吭。
                //
                // 这是判断主线程有没有停止消化消息的**唯一可靠依据**：
                // 它跑在独立线程上，而主线程一旦被冻结连打日志的机会都没有
                //（实测：厂商 ROM 冻结进程时，日志会整体消失）。
                if (blocked != heartbeatBlocked) {
                    heartbeatBlocked = blocked
                    android.util.Log.d(
                        "SkipAd",
                        if (blocked) {
                            "[diag] 主线程**开始阻塞**（${lastAckLagMs}ms 未消化心跳）"
                        } else {
                            "[diag] 主线程已恢复响应"
                        },
                    )
                }
            }
        }.apply {
            isDaemon = true
            name = "SkipAd-heartbeat"
            start()
        }
    }

    /**
     * 执行"可能不返回的系统调用"用的后台作用域 —— 目前只有 [doLaunch] 在用。
     *
     * ⚠️ 不能省掉这一步、直接在调用处同步执行：`startActivity` 一旦卡住，
     * 会把**当前线程**一起拖住，而本类跑在主线程上 —— 一卡整个会话就僵死
     * （实测日志为证：连下一步的日志都出不来，也永远等不到失败上报）。
     */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var task: AdTask? = null

    /**
     * 最近一次 `ToggleOff` 的结果描述（"已关闭" / "本来就是关闭状态"…）。
     *
     * 任务走完时上报它 —— 比干巴巴的"全部步骤完成"对用户有意义得多。
     * 每个任务开始时清空（见 [beginStep]）。
     */
    private var lastToggleMessage: String? = null

    /** 当前 `ToggleOff` 是否已经点过确认弹窗（顺序说明见 [doToggleOff]）。 */
    private var confirmClicked = false
    private var stepIndex = 0
    private var stepDeadlineMs = 0L
    private var stepStartMs = 0L
    private var running = false

    /** 当前步骤是否已经"发出过动作"，避免同一个动作被反复执行。 */
    private var stepActionIssued = false

    /**
     * 最近一次"仍在等待"日志的时间，用来给长时间的 [AutomationStep.AwaitFor]
     * 打呼吸日志（见 [WAIT_LOG_INTERVAL_MS]）。每个步骤开始时归零。
     */
    private var lastWaitLogMs = 0L

    /**
     * 最近一次由无障碍事件触发的"立刻重试"时间。
     *
     * 用来把 `TYPE_WINDOW_STATE_CHANGED` 的事件洪流压到
     * [PAGE_CHANGED_THROTTLE_MS] 一次 —— 理由见那个常量的说明。
     */
    private var lastPageChangedMs = 0L

    /** 本次点击目标是从哪一刻开始"稳定存在"的；0 = 还没命中。 */
    private var clickCandidateSinceMs = 0L

    /** 当前 [AutomationStep.ScrollTo] 步骤已经滚了几次。 */
    private var scrollCount = 0

    /** 上一次向下滚动的时间，用于节流。 */
    private var lastScrollMs = 0L

    /**
     * 上一轮 [AutomationStep.ScrollTo] 时，滚动容器的**内容指纹**。
     *
     * 见 [scrollFingerprint]：用它判断"这一屏到底动没动"，因为
     * `ACTION_SCROLL_FORWARD` 的返回值在这方面完全不可信。
     */
    private var lastScrollFingerprint = ""

    /** [AutomationStep.ScrollTo] 连续几轮内容没变（= 已经到底）。 */
    private var scrollStuck = 0

    /**
     * [AutomationStep.ScrollTo] 是否已经改用**真实手势**滚动。
     *
     * 一旦发现无障碍滚动"返回成功但内容没动"，就永久切到手势（同一个容器上
     * 没有必要再来回试）—— 实测 MIUI「所有应用」那页就是这种：
     * `ACTION_SCROLL_FORWARD` 一次只挪一两行，而手势一次一屏。
     */
    private var scrollUseGesture = false

    /**
     * [AutomationStep.ScrollTo] 已经连续几轮"页面上没有可滚容器"。
     *
     * 页面**正在重新加载**时列表是空的（实测「显示系统应用」点下去之后就是这样），
     * 那一刻没有 RecyclerView 是**正常的中间态**，不该判失败 ——
     * 见 [MAX_SCROLL_NO_CONTAINER_RETRIES]。
     */
    private var scrollNoContainerStrikes = 0

    /**
     * [AutomationStep.ScrollTo] 是不是已经**改成往上找**了。
     *
     * ⚠️ 为什么要能往上：实测「所有应用」页会**带着上一次会话的滚动位置**（停到底部），
     * 而要找的「智能服务」其实就在视口**上方一点**。只往下翻永远碰不到它 ——
     * 快照里 `apps_list`（RecyclerView、11 个子项）明明在，引擎却报
     * "连续 9 次都没有可滚动容器"（见坑 #42）。
     */
    private var scrollSearchBackward = false

    /**
     * "读不到界面"是从什么时候开始的；`0` = 现在读得到。
     *
     * 见 [UNREADABLE_LOG_INTERVAL_MS]：`rootInActiveWindow` 有可能**持续为 null**
     * 而页面其实好好的 —— 那既不是"页面没到"也不是"锚点写错"，
     * 必须在日志里区分出来，否则只能靠猜。
     */
    private var unreadableSinceMs = 0L

    /** 上一次打印"读不到界面"的时间，用于节流。 */
    private var lastUnreadableLogMs = 0L

    /** 已经因为"读不到界面"给哪一步延长过超时（每步只延一次）。 */
    private var unreadableExtendStep = -1

    /** 当前 [AutomationStep.ToggleOffAll] 步骤已经滚了几次。 */
    private var toggleAllScrolls = 0

    /** 上一次 [AutomationStep.ToggleOffAll] 滚动的时间，用于节流。 */
    private var toggleAllScrollMs = 0L

    /**
     * [AutomationStep.ToggleOffAll] 已经放弃的开关（记屏幕 y 中心）。
     *
     * 存"点不动"的那些：反复点都不生效的开关如果每轮都被重新选中，
     * 整个步骤就卡死在它身上，后面的开关一个也关不掉。
     */
    private val toggleAllSkippedYs = mutableSetOf<Int>()

    /**
     * [AutomationStep.ToggleOffAll] **连续**滚动失败的次数。
     *
     * 用来区分"真的到底了"和"这次滚动被拒了" —— 二者的返回值一样，
     * 但含义完全不同。一次失败不代表到底（上一次点击手势还没跑完时就会被拒）。
     */
    private var toggleAllScrollFails = 0

    /** 上一次 [AutomationStep.ToggleOffAll] 扫描节点树的时间，用于节流。 */
    private var lastToggleAllScanMs = 0L

    // ── 「打开其他应用」确认弹窗（见 [handleJumpDialog]）──

    /** 上一次扫弹窗的时间，用于节流。 */
    private var lastJumpDialogScanMs = 0L

    /**
     * 上一次扫描时弹窗**是否在场**。
     *
     * 节流窗口内直接用这个值作答：宁可多"占用"几百毫秒，也不能因为
     * "这一刻没扫"而漏判 —— 漏判一次就可能被 [isOffTrack] 判成走偏、整个任务重跑。
     */
    private var jumpDialogVisible = false

    /**
     * 弹窗所在窗口的包名（实测是 MIUI 自己的系统包）。
     *
     * 它是**我们自己的启动请求**引出来的，所以 [isOffTrack] 要放行它 ——
     * 否则"用户被系统问了一句"会被当成"脚本跑偏了"。
     */
    private var jumpDialogPackage: String? = null

    /** 本次弹窗已经点了几次「始终允许」。 */
    private var jumpDialogClicks = 0

    /** 上一次点弹窗的时间。 */
    private var lastJumpDialogClickMs = 0L

    /** 当前步骤已经因弹窗补过几次时间（每个步骤开始时归零）。 */
    private var jumpDialogExtends = 0

    /**
     * 最近一次发出 [AutomationStep.Launch] 之后的观察截止时刻。
     *
     * 见 [JUMP_DIALOG_WATCH_MS]：这期间不做"前台不是系统包就跳过"的短路，
     * 因为弹窗总是**晚一拍**才顶到前台。
     */
    private var jumpDialogWatchUntilMs = 0L

    /**
     * 「打开其他应用」确认弹窗的**识别**条件（任一命中即算）。
     *
     * ⚠️ 判据刻意**不含**"始终允许"：MIUI 权限页那个「拒绝 / 仅在使用中允许 /
     * 始终允许」三选一也有这几个字（见 `PermissionTasks`），拿它当判据会把
     * 权限页误判成弹窗。而「本次允许」和标题里的「想要打开」**只有**
     * 跨应用启动的确认框才有。
     */
    private val JUMP_DIALOG_MARKERS = listOf(
        Match(text = "本次允许", textMatch = MatchType.Exact),
        Match(descContains = "本次允许", descMatch = MatchType.Exact),
        Match(text = "想要打开", textMatch = MatchType.Contains),
    )

    /**
     * 弹窗里要点的那个按钮。
     *
     * 按顺序尝试：**「始终允许」优先** —— 系统会记住这个选择，之后不再问，
     * 于是这块挡路石只会出现一次。「本次允许」只作为兜底（它会下次再问）。
     *
     * ⚠️ 「拒绝」不在候选里，也**永远不会**加：拒绝等于让脚本自己把路堵死。
     */
    private val JUMP_DIALOG_APPROVE = listOf(
        Match(
            text = "始终允许",
            textMatch = MatchType.Exact,
            target = MatchTarget.ClickableAncestor,
        ),
        Match(
            descContains = "始终允许",
            descMatch = MatchType.Exact,
            target = MatchTarget.ClickableAncestor,
        ),
        Match(
            text = "本次允许",
            textMatch = MatchType.Exact,
            target = MatchTarget.ClickableAncestor,
        ),
        Match(
            descContains = "本次允许",
            descMatch = MatchType.Exact,
            target = MatchTarget.ClickableAncestor,
        ),
    )

    /** 当前步骤里已经做过几次"原地重发输入"的补救。 */
    private var recoveryCount = 0

    /**
     * 输入步骤走到第几阶段：0 = 还没点输入框，1 = 已点（键盘应已弹出），2 = 已写入。
     *
     * 拆成三步是因为**必须先真实点击唤出软键盘**，查询才会被触发（见 [doInput]）。
     */
    private var inputPhase = 0

    /**
     * 最近一次点开关的时刻，用来给"读回确认"一段冷却期（见 [TOGGLE_SETTLE_MS]）。
     */
    private var toggleClickAtMs = 0L

    /**
     * 读回时**连续**读到"相反状态"的次数（见 [TOGGLE_VERIFY_STREAK]）。
     */
    private var toggleOppositeStreak = 0

    /**
     * 本次读回是否已经打过"暂时读不到状态"的日志。
     *
     * 只打一次。`ToggleOff` 的步超时是 5 秒、而 `retryTick` 每 300ms 就进来一轮，
     * 不节流的话同一句话会连着刷十几条，把日志里真正有用的信息顶掉。
     */
    private var nullStateLogged = false

    private val retryTick = object : Runnable {
        override fun run() {
            if (!running) return
            attemptCurrentStep()
            if (running) handler.postDelayed(this, RETRY_INTERVAL_MS)
        }
    }

    // -------------------------------------------------------------------------

    fun start(newTask: AdTask) {
        cancel()
        task = newTask
        stepIndex = 0
        running = true
        beginStep()
        handler.postDelayed(retryTick, RETRY_INTERVAL_MS)
    }

    fun cancel() {
        running = false
        handler.removeCallbacks(retryTick)
        task = null
        stepActionIssued = false
        clickCandidateSinceMs = 0L
        recoveryCount = 0
    }

    /**
     * 无障碍事件到来时调用 —— 有新事件说明页面可能变了，立刻试一次，
     * 不用干等 [RETRY_INTERVAL_MS]。
     */
    fun onPageChanged() {
        if (!running) return
        // ⚠️ 必须节流：页面过渡时 `TYPE_WINDOW_STATE_CHANGED` 每秒几十条，
        //    而每条都会做一次跨进程的 `service.rootInActiveWindow` 查询。
        //    不节流会把主线程的消息队列彻底淹没 —— 现象是 handler.post 的
        //    回调、看门狗、日志全部排不上队（详见 PAGE_CHANGED_THROTTLE_MS）。
        val now = SystemClock.uptimeMillis()
        if (now - lastPageChangedMs < PAGE_CHANGED_THROTTLE_MS) return
        lastPageChangedMs = now
        attemptCurrentStep()
    }

    // -------------------------------------------------------------------------

    private fun beginStep() {
        val t = task ?: return
        if (stepIndex >= t.steps.size) return
        // 第 1 步开始时清掉上一个任务留下的开关描述，避免消息串台。
        if (stepIndex == 0) lastToggleMessage = null
        stepActionIssued = false
        confirmClicked = false
        toggleClickAtMs = 0L
        toggleOppositeStreak = 0
        nullStateLogged = false

        // ⚠️ **每步开始时清空点击去重记录** —— 否则不同页面上的"位置相同"的控件
        //    会在去重表里互相污染。
        //
        //    原因是 [NodeClicker] 的 `nodeKey` 只由
        //    `类名 | viewId | left/8,top/8` 组成，**不含页面身份**。
        //    而这一族的页面布局高度相似 —— 实测「热搜」「生活」「娱乐」「话题」
        //    四页的第一个开关 bounds **完全相同**（`[44,595][1396,834]`），
        //    于是它们在去重表里是**同一个 key**。
        //
        //    实测症状：微博（热搜页第 1 个）点过 → count=1；笔记（生活页第 1 个）
        //    点过 → count=2；轮到短剧（娱乐页第 1 个）时 `isExhausted` 已经成立，
        //    于是**刚进页面就被判成"反复点都不生效"而跳过**。
        //
        //    日志里那句"有一个开关反复点都不生效，跳过它"出现在步骤开始后 **3ms** ——
        //    那本身就是铁证：3 毫秒内不可能真的点过两次。
        //
        //    ⚠️ 这样清**不会**影响 [AutomationStep.ToggleOffAll] 内部的重试计数：
        //    它在一个步骤内跨多轮（含滚动），而 `beginStep` 只在**步骤开始时**跑一次。
        clicker.resetSession()
        clickCandidateSinceMs = 0L
        scrollCount = 0
        lastScrollMs = 0L
        // 滚动进度判定（见 [scrollFingerprint]）按步骤重置：指纹是"上一屏的内容"，
        // 跨步骤留着会把新页面的第一屏误判成"没动"。
        lastScrollFingerprint = ""
        scrollStuck = 0
        scrollUseGesture = false
        scrollNoContainerStrikes = 0
        scrollSearchBackward = false
        unreadableSinceMs = 0L
        lastUnreadableLogMs = 0L
        unreadableExtendStep = -1
        toggleAllScrolls = 0
        toggleAllScrollMs = 0L
        toggleAllSkippedYs.clear()
        toggleAllScrollFails = 0
        lastToggleAllScanMs = 0L
        // 「打开其他应用」弹窗的重试额度按步骤重置：一个任务里可能连着跨好几次应用，
        // 每一次都该有完整的点击机会（弹窗本身会自己消失，不重置的话第二次弹出来
        // 就已经"点够次数"了）。
        jumpDialogClicks = 0
        lastJumpDialogClickMs = 0L
        jumpDialogExtends = 0
        recoveryCount = 0
        inputPhase = 0
        lastWaitLogMs = 0L
        val step = t.steps[stepIndex]
        val now = System.currentTimeMillis()
        stepDeadlineMs = now + stepTimeout(step)
        stepStartMs = now
        onProgress(t, stepIndex, step)
        // 立即尝试一次，不必等第一个 tick。
        handler.post { attemptCurrentStep() }
    }

    private fun stepTimeout(step: AutomationStep): Long = when {
        // ⚠️ **可选步骤必须排在最前判**：它们"没来"是正常情况，
        //    没必要等满常规时长（可选的 AwaitFor 在脚本里可能写着 10 秒）。
        //
        //    这里也顺带把"三类可选"收口到一处 —— 下面超时判定那边用的是
        //    同一批条件，两边必须一致，否则会出现"超时了却不算跳过"这种死结。
        step is AutomationStep.Click && step.optional -> OPTIONAL_STEP_TIMEOUT_MS
        step is AutomationStep.ToggleOff && step.optional -> OPTIONAL_STEP_TIMEOUT_MS
        step is AutomationStep.AwaitFor && step.optional -> OPTIONAL_AWAIT_TIMEOUT_MS

        // ⚠️ 脚本写的 timeoutMs **不直接采信**：失败一次会重跑整个任务，
        //    长超时会被成倍放大。这里统一套上 [MAX_STEP_WAIT_MS] 那道闸。
        step is AutomationStep.AwaitFor -> min(step.timeoutMs, MAX_STEP_WAIT_MS)

        // ⚠️ 下面这两类是"多轮操作"，**不能用默认那几秒**。
        //
        // 它们天生要反复做同一件事（滚动、关开关），耗时可到几十秒，
        // 而默认超时是按"单个动作 + 等页面"设的。实测踩到：钱包功能设置页
        // 二十多个开关，`ToggleOffAll` 刚关掉几个就撞上超时，
        // 用户看到的是"才关了几个就报失败"。
        //
        // 它们也**不受** [MAX_STEP_WAIT_MS] 约束 —— 那是给"等一个页面出现"
        // 设的闸，而这里的时间是花在**进度**上的，不是空等。
        step is AutomationStep.ToggleOffAll -> TOGGLE_ALL_TIMEOUT_MS
        step is AutomationStep.ScrollTo -> SCROLL_TO_TIMEOUT_MS

        // 其它步骤（Launch / Click / Input / Toggle…）统一给默认窗口：
        // 动作本身是同步的，等的是"节点渲染出来"。
        else -> AutomationStep.DEFAULT_TIMEOUT_MS
    }

    private fun attemptCurrentStep() {
        // 诊断：这三道守卫原本是**静默** return，一旦被拦住，
        // 现象就是"日志停在 [N/M] 那一行、界面完全不动"（实测踩过）。
        // 正常情况下三者都不成立、不会打印，所以这几行只在出问题时才吵。
        val t = task
        if (t == null) {
            AdSkipRuntime.log("  · 诊断：attempt 被拦 —— 当前无任务（task == null）")
            return
        }
        if (!running) {
            AdSkipRuntime.log("  · 诊断：attempt 被拦 —— 未在运行（running == false）")
            return
        }
        if (stepIndex >= t.steps.size) {
            AdSkipRuntime.log(
                "  · 诊断：attempt 被拦 —— 步号越界（$stepIndex >= ${t.steps.size}）",
            )
            return
        }

        val step = t.steps[stepIndex]

        // ⚠️ **系统弹窗必须排在走偏判定之前** —— 顺序反了会误判。
        //
        // 「打开其他应用」确认框一出来，前台就换成了系统包（不在 allowedPackages
        // 里），先判走偏的话会把"系统问用户一句"当成"脚本跑偏"→ 整个任务重跑 →
        // 弹窗又出现 → 再判走偏，最后报失败并把用户拉回本应用。
        // 实测现象就是"卡一会儿，然后莫名其妙回到软件里"。
        if (handleJumpDialog(t)) return

        // 防呆优先：跑偏了就立刻收手，别让用户对着不动的界面干等超时。
        if (isOffTrack(t)) {
            // ⚠️ 把**前台是谁**写进失败原因：不然日志里只有"页面不对"，
            //    而"到底是哪个包"恰恰是修这条的**唯一线索**（实测吃过一次亏）。
            fail(t, "页面不对，已偏离目标路径（前台=${foregroundPackage() ?: "读不到"}）", retryable = true)
            return
        }

        // "读不到界面"的状态要**看得见**（见 [UNREADABLE_LOG_INTERVAL_MS]）。
        updateUnreadableState()

        // 超时判定：任何一步等太久都算失败 —— **可选步骤除外**。
        if (System.currentTimeMillis() > stepDeadlineMs) {
            // ⚠️ 这三个条件必须与 [stepTimeout] 里那组**完全一致** ——
            //    判超时的窗口是那边给的，这边若自己认另一套，就会出现
            //    "按可选给了短超时、超时后却判失败"这种自相矛盾。
            val optionalMissed = (step is AutomationStep.Click && step.optional) ||
                (step is AutomationStep.ToggleOff && step.optional) ||
                (step is AutomationStep.AwaitFor && step.optional)
            if (optionalMissed) {
                // ⚠️ 两种措辞分开，别混为一谈：
                //   · Click 的经典用途是"清掉可能残留的弹窗"—— 没有弹窗才是常态，
                //     说"本机没有这一项"会让人以为是机型差异；
                //   · ToggleOff / AwaitFor 的可选版本恰恰就是机型差异。
                val why = if (step is AutomationStep.Click) "没有出现" else "本机没有这一项"
                AdSkipRuntime.log("  ↳ ${step.label}：$why，跳过")
                advance()
                return
            }
            // ⚠️ 因为"读不到界面"而超时 → **再给一次机会**（每步一次，见那个常量）。
            //    直接判失败会把前面滚几十屏的成果全扔掉，还会触发整项重跑。
            if (unreadableSinceMs != 0L && unreadableExtendStep != stepIndex) {
                unreadableExtendStep = stepIndex
                val extend = min(stepTimeout(step), MAX_UNREADABLE_EXTEND_MS)
                stepDeadlineMs = System.currentTimeMillis() + extend
                AdSkipRuntime.log(
                    "  ↳ ⚠️ 界面一直读不到，这一步再等 ${extend / 1000} 秒（每步只延一次）",
                )
                return
            }
            val retryable = (step as? AutomationStep.AwaitFor)?.retryable ?: false
            fail(t, "等待超时（${step.label}）", retryable = retryable)
            return
        }

        // 第 2 层冗余：还等在超时之内时，按均分点补发上一步的输入。
        if (step is AutomationStep.AwaitFor && step.retryPreviousInput) {
            maybeRecoverPreviousInput(t, step)
        }

        // ⚠️ 等待可见性：`AwaitFor` 可能等很久 —— 冷启动别的应用时实测**29 秒**。
        // 这期间界面和日志全无动静，用户会以为程序卡死
        //（实测反馈原话："还是没有去点击啊"，而任务其实正在正常等待）。
        // 每 [WAIT_LOG_INTERVAL_MS] 报一次进度，让等待有呼吸感。
        if (step is AutomationStep.AwaitFor) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastWaitLogMs >= WAIT_LOG_INTERVAL_MS) {
                lastWaitLogMs = nowMs
                AdSkipRuntime.log(
                    "  ↳ ${step.label}：仍在等待…（已等 ${(nowMs - stepStartMs) / 1000} 秒）",
                )
            }
        }

        when (step) {
            is AutomationStep.Launch -> doLaunch(t, step)
            is AutomationStep.AwaitFor -> doAwait(t, step)
            is AutomationStep.Click -> doClick(t, step)
            is AutomationStep.Input -> doInput(t, step)
            is AutomationStep.ToggleOff -> doToggleOff(t, step)
            is AutomationStep.ToggleOn -> doToggleOn(t, step)
            is AutomationStep.ScrollTo -> doScrollTo(t, step)
            is AutomationStep.ToggleOffAll -> doToggleOffAll(step)
            is AutomationStep.PressBack -> doPressBack(step)
        }
    }

    /**
     * 原地重发上一步的输入（第 2 层冗余）。
     *
     * 比如在等搜索结果：4 秒还没出来就先重写一遍「广告」并重新提交，
     * 而不是干等到 12 秒超时、再让整个任务从头跑一遍。
     *
     * 只在**确实找到输入框**时才消耗补救次数 —— 找不到说明页面状态还不对，
     * 把机会留给后面更合适的时机，而不是白白用掉。
     */
    private fun maybeRecoverPreviousInput(task: AdTask, step: AutomationStep.AwaitFor) {
        if (recoveryCount >= MAX_INPUT_RECOVERY) return

        // 触发点：把整个等待窗口均分，第 n 次补救在第 n/(n+1) 处触发。
        val dueAt = step.timeoutMs * (recoveryCount + 1) / (MAX_INPUT_RECOVERY + 1)
        if (System.currentTimeMillis() - stepStartMs < dueAt) return

        // 必须紧跟在 Input 后面才有意义。
        val previous = task.steps.getOrNull(stepIndex - 1) as? AutomationStep.Input ?: return
        val hit = findNode(previous.matches) ?: return
        val target = resolveEditable(hit.node) ?: return

        recoveryCount++
        writeText(target, previous)
        AdSkipRuntime.log("  ↳ 结果还没出来，重新输入「${previous.text}」并重新查询")
    }

    /**
     * 处理 MIUI/HyperOS 的「打开其他应用」确认弹窗。
     *
     * ## 它长什么样
     *
     * 跨应用启动（脚本里几乎每个任务的第一步）会触发系统确认框：
     *
     * ```text
     * 「澎湃SkipAd」想要打开「天气」
     *        本次允许    始终允许    拒绝
     * ```
     *
     * 这是 MIUI 的**应用跳转保护**：由应用（而不是用户手指）发起的跳转要问一句。
     * 选「始终允许」之后系统就记住了，**之后不再问** —— 所以它是一块
     * "只挡一次路"的石头，点什么别的都没意义，点它才是最优解。
     *
     * ## 不处理会怎样（实测现象）
     *
     * 弹窗一出，前台就变成系统包（不在 [AdTask.allowedPackages] 里），
     * 于是 [isOffTrack] 在 [OFF_TRACK_GRACE_MS] 之后判定"跑偏"→ 任务重跑 →
     * 弹窗又出现 → 又跑偏，最多 [AdSkipRuleEngine.MAX_TASK_RETRY] 次后报失败、
     * 结束会话并把用户拉回本应用。用户看到的就是：
     * **"卡一会儿，然后莫名其妙回到软件里"** —— 而那个页面其实差一步就打开了。
     *
     * ## 为什么判据是「本次允许」/「想要打开」而不是「始终允许」
     *
     * MIUI 权限页那個「拒绝 / 仅在使用中允许 / 始终允许」三选一里**也有**"始终允许"
     *（见 `PermissionTasks`，那里甚至还有行尾的 `ImageView desc="始终允许"`）。
     * 拿它当判据会在权限页上误命中、提前乱点。而「本次允许」与标题里的「想要打开」
     * 只有这个确认框有，所以用它们识别，识别到之后再去找「始终允许」点。
     *
     * ⚠️ 点击一律走 [NodeClicker.click] 的三级回退：系统弹窗有可能忽略
     *    `ACTION_CLICK`（MIUI 安全中心就有先例），那一级会退回真实手势。
     *
     * @return true 表示"这一刻被弹窗占住了"：调用方应当直接返回，
     *   **既不推进步骤，也不做走偏判定**。
     */
    private fun handleJumpDialog(task: AdTask): Boolean {
        val now = System.currentTimeMillis()
        // 节流：这条检查挂在每个 tick / 每条无障碍事件上，而它要遍历全部窗口。
        if (now - lastJumpDialogScanMs < JUMP_DIALOG_SCAN_INTERVAL_MS) return jumpDialogVisible
        lastJumpDialogScanMs = now

        // ── 快速筛 ──
        // 弹窗是系统弹窗，它一出来前台就不再是"当前任务允许的包"。
        // 前台还是本应用 / 允许的包时，连扫都不用扫 —— 这条路径每 300ms 就会跑一次。
        //
        // ⚠️ 但**刚发过 Launch 的观察窗口内不能短路**：那一刻前台还停在旧页面
        //   （会话刚开始时就是本应用自己），而弹窗晚一拍才顶上来 ——
        //    短路掉的话恰恰会漏掉它。见 [JUMP_DIALOG_WATCH_MS]。
        val watching = now < jumpDialogWatchUntilMs
        val foreground = service.rootInActiveWindow?.packageName?.toString()
        if (!watching && (foreground == null || foreground == service.packageName ||
                foreground in task.allowedPackages)
        ) {
            resetJumpDialogState()
            return false
        }

        val marker = findNode(JUMP_DIALOG_MARKERS)
        if (marker == null) {
            resetJumpDialogState()
            return false
        }

        // ── 确认就是它 ──
        val firstSeen = !jumpDialogVisible
        jumpDialogVisible = true
        jumpDialogPackage = marker.node.packageName?.toString()

        if (firstSeen) {
            // 弹窗是模态的、挡着目标页面。不补时间的话，一个 5 秒的步骤
            // 光等它就耗掉大半，用户看到的是"卡一下然后报失败"。
            if (jumpDialogExtends < JUMP_DIALOG_MAX_EXTENDS) {
                jumpDialogExtends++
                stepDeadlineMs += JUMP_DIALOG_EXTEND_MS
            }
            AdSkipRuntime.log(
                "  ↳ 检测到系统「打开其他应用」确认弹窗（${jumpDialogPackage ?: "?"}）：" +
                    "点「始终允许」让系统记住",
            )
        }

        if (jumpDialogClicks >= JUMP_DIALOG_MAX_CLICKS) {
            // 点够了还赖着不走：不再点，交给正常的超时 / 走偏逻辑收场（如实失败）。
            return true
        }
        if (now - lastJumpDialogClickMs < JUMP_DIALOG_CLICK_INTERVAL_MS) return true

        val approve = findNode(JUMP_DIALOG_APPROVE)
        if (approve == null) {
            AdSkipRuntime.log("  ↳ 弹窗里没找到「始终允许」/「本次允许」，先不动它")
            return true
        }

        lastJumpDialogClickMs = now
        jumpDialogClicks++
        val ok = clicker.click(approve.node)
        AdSkipRuntime.log(
            "  ↳ 点「始终允许」：${approve.description}，" +
                (if (ok) "已发出" else "未发出") + "（第 $jumpDialogClicks 次）",
        )
        return true
    }

    /**
     * 弹窗不在了：清掉"在场"状态与点击计数。
     *
     * 点击计数**必须**跟着清：这样下次真的又弹出时，又能完整地试
     * [JUMP_DIALOG_MAX_CLICKS] 次，而不是被上一次的失败额度挡住。
     */
    private fun resetJumpDialogState() {
        jumpDialogVisible = false
        jumpDialogPackage = null
        jumpDialogClicks = 0
    }

    /**
     * 判断脚本是不是"跑偏了"。
     *
     * 判据：前台包名既不在 [AdTask.allowedPackages] 里，也不是我们自己的 App。
     * 后者要放行 —— 处理结束时我们会把应用拉回前台，那属于正常情况。
     *
     * ⚠️ 「打开其他应用」确认弹窗所在的那个系统包**也要放行**（[jumpDialogPackage]）：
     * 它是**我们自己的启动请求**引出来的，不是走偏（见 [handleJumpDialog]）。
     *
     * 读不到前台包名（根节点为空）时**判定为没跑偏**：宁可多等一会儿，
     * 也不要因为一次读取失败就误判而重跑整个任务。
     */
    private fun isOffTrack(task: AdTask): Boolean {
        if (task.allowedPackages.isEmpty()) return false
        if (System.currentTimeMillis() - stepStartMs < OFF_TRACK_GRACE_MS) return false

        val foreground = foregroundPackage() ?: return false
        if (foreground == service.packageName) return false
        if (foreground == jumpDialogPackage) return false
        return foreground !in task.allowedPackages
    }

    /** 当前前台窗口的包名；读不到（根节点为空）返回 null。 */
    private fun foregroundPackage(): String? =
        service.rootInActiveWindow?.packageName?.toString()

    /**
     * 更新"读不到界面"的状态，并在读不到时打**带时长**的日志。
     *
     * 为什么值得单独一条日志：实测遇到过一次页面明明开着、`uiautomator dump`
     * 也读得到「允许通知」，而引擎这 10 秒里 `rootInActiveWindow` 一直是 null、
     * `service.windows` 里只剩状态栏 —— 日志却只有一串"仍在等待…"，
     * 既看不出是"页面没到"也看不出是"界面读不到"，只能靠猜。
     *
     * ⚠️ 只做**观测**，不改任何行为：读不到时怎么处理由调用方决定
     *（超时那里会因此多给一次机会，见 [MAX_UNREADABLE_EXTEND_MS]）。
     */
    private fun updateUnreadableState() {
        val now = System.currentTimeMillis()
        if (foregroundPackage() != null) {
            if (unreadableSinceMs != 0L) {
                AdSkipRuntime.log(
                    "  ↳ 界面恢复可读（读不到持续了 ${(now - unreadableSinceMs) / 1000} 秒）",
                )
            }
            unreadableSinceMs = 0L
            return
        }
        if (unreadableSinceMs == 0L) unreadableSinceMs = now
        if (now - lastUnreadableLogMs < UNREADABLE_LOG_INTERVAL_MS) return
        lastUnreadableLogMs = now
        AdSkipRuntime.log(
            "  ↳ ⚠️ 读不到界面（根节点为空），已 ${(now - unreadableSinceMs) / 1000} 秒 —— " +
                "页面可能正在切换，或被系统限制了读取",
        )
    }

    // -------------------------------------------------------------------------
    // 各步骤实现
    // -------------------------------------------------------------------------

    private fun doLaunch(task: AdTask, step: AutomationStep.Launch) {
        if (stepActionIssued) return
        stepActionIssued = true

        // 日志/失败信息里要写出"到底在启动什么"。
        // ⚠️ 必须带上 `appPackage`：只写 component/action 的话，`appPackage` 那种
        //    启动会打印成 `正在启动：null`（实测日志里就是这样），排查时完全看不出目标。
        val target = step.component ?: step.action ?: step.appPackage ?: "（未指定目标）"

        // ⚠️ `NEW_TASK` 是必须的（从服务里启动 Activity 必须有独立任务），
        //    `CLEAR_TASK` 则要看目标：见 [AutomationStep.Launch.clearTask]。
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            if (step.clearTask) Intent.FLAG_ACTIVITY_CLEAR_TASK else 0

        val intent = runCatching { buildLaunchIntent(step) }.getOrElse { e ->
            // 构造阶段出错（组件名格式不对等）—— 单独报出来，别和"系统拒绝启动"混在一起。
            AdSkipRuntime.log("  ↳ 构造 Intent 失败：${e.javaClass.simpleName}: ${e.message}")
            fail(task, "无法启动页面（$target）")
            return
        }
        intent.addFlags(flags)
        AdSkipRuntime.log("  ↳ 正在启动：$target（flags=0x${flags.toString(16)}）")

        // 启动请求刚发出去：接下来几秒里"打开其他应用"确认弹窗最可能出现，
        // 这期间不做前台短路（见 [JUMP_DIALOG_WATCH_MS]）。
        jumpDialogWatchUntilMs = System.currentTimeMillis() + JUMP_DIALOG_WATCH_MS

        // ── 看门狗 ──
        // 实测踩到：跨应用启动时 `startActivity` 会**长时间不返回**
        // （不抛异常、也不返回）。引擎跑在主线程，一旦它进去不出来，
        // 整个会话就僵死：既没有下一步的日志，也没有失败上报。
        // 这里给它一个上限，超时就按正常失败流程走（转交重试 / 上报）。
        var settled = false
        val watchdog = Runnable {
            if (settled) return@Runnable
            settled = true
            AdSkipRuntime.log("  ↳ 启动请求 $LAUNCH_TIMEOUT_MS ms 未返回，判定失败")
            fail(task, "启动页面无响应（$target）", retryable = true)
        }
        handler.postDelayed(watchdog, LAUNCH_TIMEOUT_MS)

        // ⚠️ 放到**后台线程**执行：万一系统调用真的卡住，至少主线程（界面、
        //    无障碍事件、超时机制）还活着，不会陪着一起僵死。
        backgroundScope.launch {
            // ── 先试 `PendingIntent.send()`，它才是跨应用启动的正路 ──
            //
            // 理由与 `bringAppToFront` 里完全一致：Android 10 起限制"后台应用
            // 启动 Activity"，而**无障碍服务在处理事件的过程中**恰好会被判定成后台。
            // 此时直接 `startActivity()` 会**卡住不返回**（本机实测：跨应用启动一个
            // `exported=true` 的 Activity 就卡死在这里，既不成功也不失败）。
            // PendingIntent 是交给**系统**去执行的，不受这条限制。
            // ⚠️ 临时放宽 `file://` 暴露检测，只包住**发送**这一段。
            //
            // Android 7 起普通应用通过 Intent 把 `file://` 传给别的应用会抛
            // `FileUriExposedException` —— 那是 **StrictMode 的 VmPolicy** 抛的。
            // 本应用唯一需要传 `file://` 的地方，就是"把自身 APK 交给安装器"
            //（见 [buildLaunchIntent]：那是进「应用包管理组件」设置页的唯一入口，
            //  而 MIUI 安装器不认 `content://`，只会停在中转页 `InstallStart`）。
            // 只包住发送、发完立刻还原，不做全局放宽。
            val vmPolicy = StrictMode.getVmPolicy()
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
            var viaPending = false
            var ok = false
            var thrown: Throwable? = null
            try {
                // 先直连、失败再退回 `PendingIntent`。
                //
                // 两条都留着是被实测逼出来的：`adb shell am start` 用**完全相同**的
                // Intent 能一步打开安装界面 `NewInstallerPrepareActivity`，
                // 但从无障碍服务里直连会被判成"后台启动 Activity"而失败
                //（实测成功那条日志写的是 `（PendingIntent）`），
                // 所以直连当探路、`PendingIntent` 兜底。
                //
                // ⚠️ 这段跑在 [backgroundScope]（IO 线程）上 —— 万一某个 ROM 上
                //    直连真的卡住不返回，也只是拖住这个后台线程，不会僵死主线程。
                ok = runCatching { service.startActivity(intent) }
                    .onFailure { thrown = it }
                    .isSuccess
                viaPending = false
                if (!ok) {
                    viaPending = runCatching {
                        PendingIntent.getActivity(
                            service,
                            0,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ).send()
                    }.isSuccess
                    ok = viaPending
                }
            } finally {
                StrictMode.setVmPolicy(vmPolicy)
            }

            handler.post {
                if (settled) return@post
                settled = true
                handler.removeCallbacks(watchdog)
                if (ok) {
                    AdSkipRuntime.log(
                        "  ↳ 启动请求已受理：$target" +
                            if (viaPending) "（PendingIntent）" else "（startActivity）",
                    )
                    advance()
                } else {
                    AdSkipRuntime.log(
                        "  ↳ 启动异常：${thrown?.javaClass?.simpleName}: ${thrown?.message}",
                    )
                    fail(task, "无法启动页面（$target）")
                }
            }
        }
    }

    /**
     * 组装 [AutomationStep.Launch] 的 Intent：**显式组件优先**，其次隐式 action。
     *
     * ⚠️ action 用 `setAction` 而不是 `Intent(action)`：`action` 现在是可空的
     * （给显式组件时就不需要它），显式构造 + `setAction(null)` 是明确安全的。
     *
     * 两者都没给时会得到一个空 Intent —— `startActivity` 会抛异常，
     * 由上面的 `runCatching` 捕获并如实上报，不会静默什么都不做。
     */
    /**
     * 把脚本里的 `%SELF%` 占位符换成**当前应用包名**。
     *
     * 任务定义是**静态数据**（见 [AdTask]，拿不到 Context），而"本应用自己"的包名
     * 在 debug 变体里带 `.debug` 后缀 —— 写死的后果不是报错，而是**假成功**：
     * 打开的是**另一个包**的页面，看着一切正常，实际授错了对象。
     * 详见 [AutomationStep.SELF_PACKAGE]。
     */
    private fun resolveSelf(raw: String?): String? =
        raw?.replace(AutomationStep.SELF_PACKAGE, service.packageName)

    private fun buildLaunchIntent(step: AutomationStep.Launch): Intent {
        // ⚠️ 三个目标字段都过一遍占位符：`appPackage` / `component` / `data`
        //    都可能写成 `%SELF%`（权限任务用的是 data 那种）。
        val app = resolveSelf(step.appPackage)
        val component = resolveSelf(step.component)
        val intent = when {
            // ① "打开应用"：等价于点桌面图标，系统最宽容。
            app != null -> Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(app)
            }

            // ② 显式组件：`包名/类名` → ComponentName。
            //    类名以 `.` 开头时补上包名 —— 这正是 AndroidManifest 里
            //    `android:name=".activity.Xxx"` 的写法。
            component != null -> {
                val pkg = component.substringBefore('/')
                val rawClass = component.substringAfter('/', "")
                val cls = if (rawClass.startsWith(".")) pkg + rawClass else rawClass
                Intent().setComponent(android.content.ComponentName(pkg, cls))
            }

            // ③ 隐式 action。
            else -> Intent()
        }
        // action：appPackage 方式上面已经设成 MAIN，其余用脚本声明的 action。
        if (app == null) intent.setAction(step.action)

        // ⚠️ data 与 type 必须用 `setDataAndType` **一次设完**。
        //
        // `Intent.setData()` 会清掉 type、`setType()` 会清掉 data ——
        // 分开设的结果是**只剩后者**。实测踩过：唤不起安装界面，
        // 报 `ActivityNotFoundException`，而日志里那个 Intent 完全没有 `dat=`。
        val dataUri = step.data?.let { raw ->
            if (raw == AutomationStep.SELF_APK_DATA) {
                // 特殊标记 → 本应用自己的 APK（用于唤起安装界面，见该常量的说明）。
                //
                // ## ⚠️ 为什么用 `file://` 而不是 FileProvider 的 `content://`
                //
                // 走过一次弯路，记在这里免得再试：`content://` 交给 MIUI 安装器后
                // 只会把**中转页 `InstallStart`** 拉起来，它随后**不跳**到真正的
                // 安装界面 —— 系统日志里只能看到
                // `prevPageName: com.miui.packageinstaller/…InstallStart`，
                // 于是右上角那个「设置」按钮永远等不到（实测 30 秒超时）。
                // 换成 `file://` 后（与 `adb shell am start` 同一条路）就能正常打开。
                //
                // ⚠️ 代价：Android 7 起普通应用通过 Intent 传 `file://` 会抛
                //    `FileUriExposedException` —— 那是**本应用自己的 StrictMode 检测**，
                //    发送处临时放宽即可，见 [doLaunch]。
                android.net.Uri.fromFile(java.io.File(service.applicationInfo.sourceDir))
            } else {
                // `%SELF%` → 当前应用包名（见 [AutomationStep.SELF_PACKAGE]）。
                android.net.Uri.parse(resolveSelf(raw) ?: raw)
            }
        }
        if (dataUri != null) {
            intent.setDataAndType(dataUri, step.mimeType)
            if (step.data == AutomationStep.SELF_APK_DATA) {
                // 交给安装器去读这个 content:// —— 不加会被拒。
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            step.mimeType?.let { intent.type = it }
        }
        return intent
    }

    private fun doAwait(task: AdTask, step: AutomationStep.AwaitFor) {
        val hit = findNode(step.matches) ?: return
        AdSkipRuntime.log(
            "  ↳ ${step.label}：命中候选#${hit.matchIndex} ${hit.description}",
        )
        advance()
        // 没找到就继续等（由 retryTick / 超时 / 补救负责推进）
    }

    private fun doClick(task: AdTask, step: AutomationStep.Click) {
        if (stepActionIssued) return
        val hit = findNode(step.matches)
        if (hit == null) {
            // 目标消失（列表正在重建）→ 重新计时，等它稳定下来再说。
            clickCandidateSinceMs = 0L
            return
        }

        // ⚠️ 稳定性确认：节点要**连续存在**一小段时间才真的点下去。
        //
        // 搜索结果列表是异步渲染的：先铺骨架 / 旧内容，再被真实数据替换。
        // 在这个窗口里点下去，节点往往在点击送达之前就被重建了，
        // 表现就是"点了没反应，然后卡住"。
        // 等 [CLICK_SETTLE_MS] 能确保点的是**已经稳定下来的**那一个节点。
        val now = System.currentTimeMillis()
        if (clickCandidateSinceMs == 0L) {
            clickCandidateSinceMs = now
            return
        }
        if (now - clickCandidateSinceMs < CLICK_SETTLE_MS) return

        // 重复点击保护：同一个节点点过太多次、或者距上次太近，就不再点，
        // 交给超时走重试。没有这道闸门的话，一旦点击没生效，
        // 脚本会在剩下的等待窗口里**反复点同一个地方**（每 300ms 一次）。
        if (clicker.shouldSkip(hit.node)) {
            AdSkipRuntime.log(
                "  ↳ ${step.label}：点击被去重跳过（同一节点已点过太多次）",
            )
            return
        }

        stepActionIssued = true
        // useGesture：MIUI 安全中心的列表行对 ACTION_CLICK 返回 true 但无反应，
        // 必须走真实触摸（详见 AutomationStep.Click.useGesture 的 KDoc）。
        // [NodeHit.clickFraction] 非空时点击器会直接走真实手势落在那个相对位置
        // （"节点边界 ≠ 热区"的情况，见 Match.clickFraction）。
        val ok = if (step.useGesture) {
            clicker.touch(hit.node)
        } else {
            clicker.click(hit.node, hit.clickFraction)
        }
        if (ok) {
            clicker.recordClick(hit.node)
            AdSkipRuntime.log(
                "  ↳ ${step.label}：候选#${hit.matchIndex} ${hit.description}，点击成功",
            )
            advance()
        } else {
            AdSkipRuntime.log("  ↳ ${step.label}：点击动作未成功，稍后重试")
            // 点击失败允许重试（stepActionIssued 回退），由超时兜底。
            stepActionIssued = false
            clickCandidateSinceMs = 0L
        }
    }

    /**
     * 写入文本（第 1 层冗余：读回确认）。
     *
     * ## 为什么不是"写一次就走"
     *
     * 输入是**异步行为**：`performAction` 返回 true 只代表"这次调用被接受了"，
     * 不代表输入框的内容立刻变成了目标值（输入法、页面的过滤器都可能再改动它）。
     * 所以这一步拆成两轮：
     *
     * 1. 第一轮 —— 聚焦 + 写入；
     * 2. 第二轮 —— **读回比对**，内容真的等于 [AutomationStep.Input.text] 才算成功。
     *
     * 读回这一轮还顺带解决了另一个隐患：如果写入后内容被页面**清空**
     * （比如搜索页重新初始化），比对不通过会把写入标记回退，下一轮重写一次，
     * 而不是带着一个空输入框往下走。
     */
    private fun doInput(task: AdTask, step: AutomationStep.Input) {
        val hit = findNode(step.matches) ?: return
        val target = resolveEditable(hit.node) ?: return

        when (inputPhase) {
            // ── 第 1 步：用**真实触摸**点一下输入框，把软键盘唤起来 ──
            //
            // ⚠️ 这是实测出来的关键，而且必须用 [NodeClicker.touch] 而**不是** [NodeClicker.click]：
            //
            //    MIUI 设置搜索框的查询逻辑挂在 IME 上。而 `ACTION_CLICK`（click() 的第一级）
            //    虽然能"点中"输入框、让光标出现，却**不会弹出软键盘** —— 于是
            //    `ACTION_SET_TEXT` 能把文字写进去（输入框里看得见「广告」），
            //    但**搜索永远不会触发**，下方一直只有"搜索记录"历史。
            //
            //    实测对照：
            //      ACTION_CLICK    → 有光标、无键盘、无结果 ✗
            //      dispatchGesture → 有光标、有键盘、结果立刻出来 ✓
            0 -> {
                inputPhase = 1
                if (clicker.touch(target)) {
                    AdSkipRuntime.log("  ↳ 真实触摸输入框，唤醒软键盘")
                } else {
                    AdSkipRuntime.log("  ↳ 触摸输入框失败，稍后重试")
                    inputPhase = 0 // 点不动 → 下一轮重试（超时兜底）
                }
            }

            // ── 第 2 步：写入文本 ──
            1 -> {
                inputPhase = 2
                if (writeText(target, step)) {
                    AdSkipRuntime.log(
                        "  ↳ 写入「${step.text}」${if (step.submitAfter) "并提交搜索" else ""}",
                    )
                } else {
                    AdSkipRuntime.log("  ↳ 文本写入被拒，稍后重试")
                    inputPhase = 1 // 写入被拒 → 下一轮重试
                }
            }

            // ── 第 3 步：读回确认，顺便把"输入异步生效"的时间让出来 ──
            else -> {
                val actual = readText(target)
                if (actual == step.text) {
                    AdSkipRuntime.log("  ↳ 读回确认通过，输入「${step.text}」完成")
                    advance()
                } else {
                    // 没写进去 / 被页面清掉了 → 退回去重写。
                    AdSkipRuntime.log(
                        "  ↳ 读回不符（当前「$actual」），重写",
                    )
                    inputPhase = 1
                }
            }
        }
    }

    /**
     * 关闭开关 —— **必须验证结果才算成功**，成功后由 [finishToggle] 推进到下一步。
     *
     * ## 为什么不能"点完就报成功"
     *
     * `performAction(ACTION_CLICK)` 返回 true 只代表"这次动作被系统受理了"，
     * **完全不代表开关真的被切换**。点歪了、点到的是只读文本、控件吞了事件……
     * 都会返回 true 而界面纹丝不动。
     *
     * 如果这里直接上报成功，用户拿到的是一份**假的成功报告** ——
     * 而"关广告开关"恰恰是这个应用唯一的产出，骗人的代价最大。
     *
     * 所以拆成两轮：**点一下 → 下一轮读回 `isChecked`**，
     * 真的变成 false 才收工；还没变就允许再点一次（受 [NodeClicker] 的去重保护限制）。
     */
    /**
     * 关掉一个开关并验证通过后，**推进到下一步**（而不是就此结束整个任务）。
     *
     * ⚠️ 这是刻意的行为变更：原先 `ToggleOff` 会 `complete` 整个任务，
     * 于是**一个任务只能有一个开关** —— 而「下载管理 → 信息流设置」里有两个
     * （资源推荐、热榜推荐），第二个永远轮不到执行。
     *
     * 现在 `ToggleOff` 只负责"关掉 + 验证"这一件事，任务何时结束由
     * [advance] 在步骤全部走完时统一判定。
     */
    private fun finishToggle(task: AdTask, message: String) {
        AdSkipRuntime.log("  ↳ $message")
        lastToggleMessage = message
        advance()
    }

    private fun doToggleOff(task: AdTask, step: AutomationStep.ToggleOff) {
        doToggle(
            task = task,
            matches = step.matches,
            confirm = step.confirm,
            // 目标是"关"。
            targetChecked = false,
        )
    }

    /** [AutomationStep.ToggleOn] 的入口 —— 与 [doToggleOff] 共用同一个实现。 */
    private fun doToggleOn(task: AdTask, step: AutomationStep.ToggleOn) {
        doToggle(
            task = task,
            matches = step.matches,
            confirm = null,
            targetChecked = true,
        )
    }

    /**
     * 按一次返回键（[AutomationStep.PressBack]）。
     *
     * ⚠️ 返回键是**异步**的：按下去之后页面要几百毫秒才切完，而
     * `performGlobalAction` 只表示"事件已经注入"。所以这里**不立刻推进** ——
     * 先等页面切完，再由下一步的 `AwaitFor` 确认到底到没到。
     *
     * 直接 `advance()` 的后果：下一步会在**旧页面**上找节点 —— 运气好只是
     * 白等一轮，运气差会命中一个**同名的旧节点**（两个页面都出现"确定"、
     * "始终允许"这类文字时），于是点错地方。
     */
    /**
     * 关闭页面上所有开关（可排除若干），边滚边关。
     *
     * ## 为什么一次只关一个、每轮重新扫描
     *
     * 关掉一项后页面**可能重排**（实测小米画报那页就会增减条目），
     * 预收集的节点会集体失效 —— 那正是"点了没反应"的经典成因。
     *
     * 所以每轮只做一件事：扫一遍 → 关掉最靠上的那个 → 回到常规循环。
     * 下一轮重新扫，拿到的永远是**当下的**节点。
     *
     * ## 怎么判断"本屏关完了、该往下滚"
     *
     * 本屏扫不到"还开着、且不在排除行里"的开关 → 滚一屏。
     * 用 `ACTION_SCROLL_FORWARD` 的**返回值**判断到底没有：
     * 滚不动了它返回 false，这比"滚 N 次"可靠得多。
     *
     * ## 点不动的开关怎么办
     *
     * [NodeClicker.shouldSkip] 会拦住"同一个点太多次"的重复点击。
     * 如果被它拦下，说明这个开关反复点都不生效 —— 这时**必须把它记下来跳过**，
     * 否则下一轮还会选中它，整个步骤就卡死在它身上，后面的开关一个也关不掉。
     *
     * 记录用的是"屏幕 y 中心"而不是节点对象：每轮都重新扫，节点对象是新的，
     * 拿它当 key 认不出同一个开关。滚动之后 y 含义全变，所以那时清空重记。
     */
    @Suppress("DEPRECATION")
    private fun doToggleOffAll(step: AutomationStep.ToggleOffAll) {
        // ── 守卫：页面对不上就整步跳过（见 [AutomationStep.ToggleOffAll.guard]）──
        //
        // ⚠️ 这道判断**必须排在最前**：本步的语义是"把**本页**开关全关掉"，
        //    而"本页"取决于我们以为自己站在哪一页。缺了守卫，前面一个
        //    [AutomationStep.AwaitFor.optional] 跳过之后，它就会在**上一页**上照关不误
        //    —— 实测场景：搜索 App 的「我的热榜」下有四个子页，别家 ROM 可能只有一两个，
        //    缺页时它会把「我的热榜」列表页的开关全关掉。
        //
        // ⚠️ 这里**不需要**等待窗口：脚本里这一步前面必然已经挂了一个
        //    `AwaitFor`（哪怕是 optional 的那个，也会等满 3 秒才跳过），
        //    到这儿页面状态早就稳定了。
        step.guard?.let { guard ->
            if (findNode(listOf(guard)) == null) {
                AdSkipRuntime.log("  ↳ ${step.label}：没等到守卫，页面对不上，整步跳过")
                advance()
                return
            }
        }

        // ⚠️ **扫描节流**：[attemptCurrentStep] 每几十毫秒就会调进来一次，
        //    而下面每一轮都要遍历几百个节点 —— 不节流会让主线程持续掉帧
        //（实测日志里 "Skipped 5x frames" 刷屏）。
        //
        //    而那不只是"卡一下"的问题：主线程被拖住会连累
        //    `dispatchGesture` 派发和 `ACTION_SCROLL_FORWARD`，
        //    让它们**更容易被系统拒绝**，于是"点不动""滚不动"接连发生 ——
        //    形成"越卡越失败、越失败越卡"的恶性循环。
        //
        //    200ms 一次足够跟得上开关状态的变化，也不会和 500ms 的点击节流打架。
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastToggleAllScanMs < TOGGLE_ALL_SCAN_INTERVAL_MS) return
        lastToggleAllScanMs = nowMs

        val root = service.rootInActiveWindow ?: return

        // ── 扫一遍当前屏 ──
        val all = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (++visited > MAX_TOGGLE_ALL_NODES) break
            all.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }

        fun boundsOf(node: AccessibilityNodeInfo): Rect {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            return bounds
        }

        fun centerYOf(node: AccessibilityNodeInfo): Int {
            val bounds = boundsOf(node)
            return (bounds.top + bounds.bottom) / 2
        }

        // ── 屏幕的可视纵向范围 ──
        //
        // ⚠️ 这里必须自己算，**不能**靠 `isVisibleToUser`：
        //    它在这个页面上对屏幕外的 item 也报 true（RecyclerView 预取 + 自绘容器），
        //    代码别处已经因为这个吃过一次亏（见 [findAnchor] 里的注释）。
        val screenBounds = boundsOf(root)

        /**
         * 这个开关**当前是否真的在屏幕上、能点到**。
         *
         * ⚠️ 这道判断是**必须的**，实测踩得很惨：RecyclerView 会把屏幕外的 item
         * 也放进节点树，而它们的 bounds 被**裁剪**成 `top > bottom` 的无效矩形
         *（如 `Rect(60, 3302 - 1380, 3110)`，屏幕高才 3110）。
         *
         * 中心点算出来是 3206 —— 点下去必然落在屏幕外、必然失败。
         * 不排除它们的话，脚本会一个接一个地"尝试点屏幕外的开关、失败、跳过"，
         * 屏幕上明明还有能关的，却**永远走不到"滚动"那一步**，最后超时。
         *
         * 现象就是：日志里刷一串"点不动，跳过"，而"已滚 N 屏"始终是 0。
         */
        fun isOnScreen(node: AccessibilityNodeInfo): Boolean {
            val bounds = boundsOf(node)
            val centerY = (bounds.top + bounds.bottom) / 2
            return centerY in screenBounds.top..screenBounds.bottom
        }

        // ── 先算排除项的**纵向范围** ──
        //
        // 按"行"而不是"节点"排除：总开关那一行的标题和开关是两个兄弟节点，
        // 只比对命中节点的自身范围，会把右边那颗开关漏掉。
        val excludedRanges: List<IntRange> = all
            .filter { node -> step.exclude.any { NodeMatcher.matchesAnchor(node, it) } }
            .map { node -> boundsOf(node).let { it.top..it.bottom } }

        // ── 找"还在屏幕上、还开着、且不在排除行里"的开关 ──
        //
        // ⚠️ 按**屏幕纵坐标**取最靠上的那个，而不是 `firstOrNull`（那按节点树序）。
        //    树序**通常**和视觉顺序一致，但 RecyclerView 有预取/复用时会不一致 ——
        //    表现出来就是"跳着关"。按 y 排一次才能保证从上往下、一个接一个。
        val victim = all
            .filter { node ->
                isSwitchNode(node) && node.isChecked &&
                    isOnScreen(node) &&
                    excludedRanges.none { range -> centerYOf(node) in range } &&
                    centerYOf(node) !in toggleAllSkippedYs
            }
            .minByOrNull { centerYOf(it) }

        if (victim != null) {
            if (clicker.shouldSkip(victim)) {
                // ⚠️ **不要**在这里直接把它判成"点不动"。
                //
                // [NodeClicker.shouldSkip] 把两种情况混在一起返回 true：
                //   · **距上次点击太近**（500ms 节流）—— 这是**常态**：手势点击是
                //     异步的，`click()` 返回 true 只代表"派发成功"，开关状态要过一会儿才变；
                //   · 真的点够次数了 —— 这才是坏掉的那个。
                //
                // 对前者应当**等一会儿再试**，对后者才该**放弃**。
                // 之前没区分，于是把"正常但还没生效"的开关也记进了放弃名单 ——
                // 用户看到的正是"跳着关、漏掉一堆"。
                if (clicker.isExhausted(victim) || clicker.isDispatchExhausted(victim)) {
                    toggleAllSkippedYs.add(centerYOf(victim))
                    AdSkipRuntime.log("  ↳ ${step.label}：有一个开关反复点都不生效，跳过它")
                }
                // 否则只是节流，这一轮什么都不做，下一轮状态到位了继续。
                return
            }
            if (clicker.click(victim)) {
                clicker.recordClick(victim)
                AdSkipRuntime.log(
                    "  ↳ ${step.label}：关掉一个（本步已滚 $toggleAllScrolls 屏）",
                )
            } else {
                // ⚠️ **失败时也必须记账**，否则会静默卡死。
                //
                // 不记账的后果：`shouldSkip` 查不到这个节点 → 返回 false →
                // 下一轮原样再选中它 → 再失败 …… 无限循环，而且**一声不响**
                //（这条分支既不打日志也不推进），用户看到的是"关了几个之后就没动静了"。
                //
                // 实测原因：`click()` 第 3 级走 `dispatchGesture`，而**上一个手势
                // 还没 onCompleted 时再派发会被系统拒绝**。前面几次紧密点击把队列排满，
                // 之后所有派发都失败 —— 卡死就在那一刻开始。
                //
                // 记账之后：[NodeClicker] 的 500ms 节流会让我们隔一轮再试
                //（手势有时间跑完，往往就成了）；真要是连着两次都派发不出去，
                // 这个节点会被判成"点不动"而跳过，后面的开关不受影响。
                // ⚠️ 这里记的是**派发失败**，不是 [NodeClicker.recordClick]。
                //
                // 两者的严重性完全不同：派发被拒是**暂时性**的
                //（上一个手势还没 onCompleted），等一会儿再发就能成；
                // 而"点击发出去了、界面没反应"才说明控件可能真点不动。
                //
                // 早先混在一起记，导致每页**第一个**开关常被误判成"点不动"——
                // 实测症状就是"娱乐页漏了短剧、话题页漏了知乎"（它们都是所在页
                // 最先被处理的那个）。
                clicker.recordDispatchFailure(victim)
                AdSkipRuntime.log("  ↳ ${step.label}：一次点击未能派发，稍后重试")
            }
            return
        }

        // ── 本屏没有可关的了 → 往下滚一屏 ──
        if (toggleAllScrolls >= step.maxSwipes) {
            AdSkipRuntime.log("  ↳ ${step.label}：已滚动 $toggleAllScrolls 次，收尾")
            advance()
            return
        }

        val now = System.currentTimeMillis()
        if (now - toggleAllScrollMs < SCROLL_INTERVAL_MS) return
        toggleAllScrollMs = now

        // ⚠️ **不能"一次滚动失败就判到底"** —— 实测踩到过：滚动只发生了一次
        //    就被判定"已到页面底部"，而屏幕上其实还有一大半开关没关。
        //
        //    失败有两个常见原因，都**不代表真的到底**：
        //    1. 上一次点击手势还没跑完 —— 此时 `ACTION_SCROLL_FORWARD` 会被拒
        //      （日志里的"一次点击未能派发"就是同一时刻的表现）；
        //    2. 选错了容器 —— 这页外层套了 CoordinatorLayout + NestedScrollView
        //      （为了标题栏折叠），它们**也**声明可滚动，对外层发滚动没有效果。
        //
        //    所以：依次试**所有**可滚容器，并且**连续失败若干次**才认定到底。
        val scrolled = scrollForwardAny(root)
        if (!scrolled) {
            toggleAllScrollFails++
            if (toggleAllScrollFails < MAX_SCROLL_FAILS) {
                AdSkipRuntime.log(
                    "  ↳ ${step.label}：本次滚动未生效，稍后重试" +
                        "（第 $toggleAllScrollFails 次）",
                )
                return
            }
            AdSkipRuntime.log(
                "  ↳ ${step.label}：连续 $toggleAllScrollFails 次滚动都没反应，判定已到底" +
                    if (toggleAllSkippedYs.isEmpty()) "" else
                        "（有 ${toggleAllSkippedYs.size} 个开关点不动）",
            )
            advance()
            return
        }

        toggleAllScrollFails = 0
        toggleAllScrolls++
        // 滚动后 y 坐标全部作废，放弃记录跟着清空 —— 否则新一屏里
        // 恰好落在同一 y 的**另一个**开关会被误跳过。
        toggleAllSkippedYs.clear()

        // ⚠️ 滚动后**要等页面重新布局完再扫描**。
        //
        // 实测（钱包那页）：滚动后立刻扫，往往只看到刚进屏幕的一两个开关 ——
        // RecyclerView 还没绑定新 item。于是变成"关一个 → 没候选了 → 又滚"，
        // 白白多滚好几屏（日志里连着 3 次滚动各只关了 1~2 个）。
        //
        // 把扫描时间一起往后推，等于给布局留出一段时间。
        lastToggleAllScanMs = System.currentTimeMillis()
    }

    /**
     * 滚动查找：**先往下翻，翻不动了再回头往上翻**；找到就过，上下都翻过还没有就判失败。
     *
     * 每轮只做一件事（要么"确认找到"、要么"滚一格"），然后回到常规的
     * [attemptCurrentStep] 循环再判一次 —— 这样滚动和查找天然交替，
     * 不会出现"一口气滚八屏然后找不到"的情况。
     *
     * ## 四条实测逼出来的纪律（见坑 #40 / #41 / #42）
     *
     * 1. **`ACTION_SCROLL_FORWARD` 的返回值不能当进度用**：MIUI「所有应用」那页
     *    每按一次只挪一两行（还返回 true），而真实手势一次一屏。所以这里改用
     *    [scrollFingerprint]"看内容有没有变"来判进度，发现没动就切手势。
     * 2. **判"到底了"也要看内容**，不能看返回值 —— 连续 [MAX_SCROLL_STUCK] 轮
     *    内容完全没变才收手。否则会把"到底之后再滑一下才露出来的那一段"漏掉
     *   （实测「智能服务」就在那页列表的最末尾，而且**是底部再滑一次之后**才出现）。
     * 3. **"这一刻没有可滚容器" ≠ "这页不可滚动"**：列表重新加载时页面会短暂变成
     *    `正在加载...` + 空容器，一找不到就失败会把本来能成的查找判死
     *   （见 [MAX_SCROLL_NO_CONTAINER_RETRIES]）。
     * 4. **只往下找是不够的**：页面会**带着上一次会话的滚动位置**进来（实测那页就停在
     *    底部），目标可能就在**上方**。所以"往下推不动"不是"找不到"，
     *    而是"该换方向了"——见 [scrollSearchBackward]。
     */
    private fun doScrollTo(task: AdTask, step: AutomationStep.ScrollTo) {
        // 已经在屏幕上 → 直接过。这一步放在最前面，所以"目标本来就在首屏"
        // 的情况下一次都不会滚（也就不会白晃一下屏幕）。
        if (findNode(step.matches) != null) {
            AdSkipRuntime.log("  ↳ ${step.label}：已在当前屏")
            advance()
            return
        }

        if (scrollCount >= step.maxSwipes) {
            fail(task, "${step.label}：滚了 $scrollCount 屏仍未找到", retryable = false)
            return
        }

        // 节流：滚动是异步的，页面要一两帧才滑到位。
        // 不节流的话每 50ms 发一次会**连滑好几屏**，直接冲过目标。
        // ⚠️ 指纹判定必须放在节流**之后** —— 否则每条无障碍事件都会去遍历一次容器。
        val now = System.currentTimeMillis()
        if (now - lastScrollMs < SCROLL_INTERVAL_MS) return
        lastScrollMs = now

        val root = service.rootInActiveWindow ?: return
        // ⚠️ 用 [findScanTarget] 而不是 [findAllScrollables]：后者的判据是
        //   "`isScrollable` 且能往后滚"，而**列表停在底部时这条根本不成立** ——
        //    那一刻也得能拿到容器去**往回**翻（见坑 #42）。
        val container = findScanTarget(root)
        if (container == null) {
            // ⚠️ **不能一找不到就判失败**：列表重新加载时（实测「显示系统应用」点下去
            //    之后）页面会短暂变成 `正在加载...` + 空容器 —— 那是正常的中间态。
            //    给几轮机会，只有连续多轮都没有才认定"这页真不可滚动"。
            scrollNoContainerStrikes++
            if (scrollNoContainerStrikes <= MAX_SCROLL_NO_CONTAINER_RETRIES) {
                AdSkipRuntime.log(
                    "  ↳ ${step.label}：还没有可滚动的容器（页面可能在重新加载）" +
                        "—— 第 $scrollNoContainerStrikes 次，继续等",
                )
                return
            }
            fail(
                task,
                "${step.label}：连续 $scrollNoContainerStrikes 次都没找到可滚动的列表，且未找到目标",
                retryable = false,
            )
            return
        }
        scrollNoContainerStrikes = 0

        // ── 上一轮滚完，内容到底动没动？没动就**逐级升级手段**，而不是马上判失败 ──
        val fingerprint = scrollFingerprint(container)
        if (scrollCount > 0 && fingerprint == lastScrollFingerprint) {
            scrollStuck++
            when {
                // ① 先换手段：无障碍滚动常常"返回成功却没动"（实测一次只挪一两行）
                !scrollUseGesture -> {
                    scrollUseGesture = true
                    scrollStuck = 0
                    AdSkipRuntime.log("  ↳ ${step.label}：无障碍滚动没推动，改用真实手势滑动")
                }
                // ② 再换方向：往下翻不动了 → 这一头到头了，回头往上找。
                //    ⚠️ 实测「所有应用」页会**带着上一次会话的滚动位置**（停在底部），
                //       而目标就在视口上方一点点 —— 只往下找永远碰不到它（坑 #42）。
                !scrollSearchBackward -> {
                    scrollSearchBackward = true
                    scrollStuck = 0
                    AdSkipRuntime.log(
                        "  ↳ ${step.label}：向下翻不动了，改为向上找（上面可能还有没看过的内容）",
                    )
                }
                // ③ 手段和方向都用尽了还是不动 —— 如实失败，不在原地打转
                scrollStuck >= MAX_SCROLL_STUCK -> {
                    fail(task, "${step.label}：上下都翻过了，仍未找到", retryable = false)
                    return
                }
            }
        } else {
            scrollStuck = 0
        }
        lastScrollFingerprint = fingerprint

        scrollCount++
        val forward = !scrollSearchBackward
        if (scrollUseGesture || !forward) {
            // ⚠️ "往回找"一律走真实手势：`ACTION_SCROLL_BACKWARD` 在列表底部同样
            //    可能被拒（和 forward 一个道理），而手势是实测能推得动的那个。
            val ok = if (forward) clicker.swipeUp(container) else clicker.swipeDown(container)
            AdSkipRuntime.log(
                "  ↳ ${step.label}：手势向${if (forward) "上" else "下"}滑一屏" +
                    "（第 $scrollCount 次）" + if (ok) "" else "（派发被拒，稍后重试）",
            )
        } else {
            // 与 [AutomationStep.ToggleOffAll] 共用同一套"依次试所有容器"的逻辑，
            // 免得两个步骤在同一个页面上表现不一致（一个滚得动、一个滚不动）。
            scrollForwardAny(root)
            AdSkipRuntime.log("  ↳ ${step.label}：向下滚动（第 $scrollCount 次）")
        }
    }

    /**
     * 滚动容器的**内容指纹**：取容器内头几条与末几条有文字的节点，拼成一个字符串。
     *
     * 用途只有一个 —— 判断"这一屏到底动没动"。因为 `ACTION_SCROLL_FORWARD`
     * 既可能**返回 true 却没动**，也可能**返回 false 但动了**（手势还没跑完就被拒），
     * 它的返回值在进度判定上完全不可信（坑 #23 / #40）。
     *
     * 头尾都取是为了区分"只挪了一点点"和"换了一屏"：只取头部的话，
     * 小幅滚动会被误判成"没动"，从而过早判定到底。
     */
    private fun scrollFingerprint(container: AccessibilityNodeInfo): String {
        val labels = ArrayList<String>(48)
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(container)
        while (queue.isNotEmpty() && visited < MAX_FINGERPRINT_NODES) {
            val node = queue.removeFirst()
            visited++
            val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            if (label != null && labels.size < 48) labels.add(label)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        if (labels.isEmpty()) return ""
        return (labels.take(3) + labels.takeLast(3)).joinToString("|")
    }

    /**
     * 依次尝试**所有**可向下滚动的容器，任一成功即返回 true。
     *
     * ## 为什么要"全都试"而不是"挑一个"
     *
     * 页面很常见地会在外面套一层 CoordinatorLayout / NestedScrollView
     *（为了标题栏折叠效果），它们**也** `isScrollable`，但对外层发滚动
     * **什么也不动** —— 真正装列表的是里面的 RecyclerView。
     *
     * 只挑一个的话，挑错了就永远滚不动；而挑错和"真到底"在调用方看来
     * 都是"返回 false"，无法区分。逐个试就绕开了这个歧义。
     *
     * 顺序上 [findAllScrollables] 会把 RecyclerView 排在前面，
     * 所以正常页面不会白试那几个外层容器。
     */
    private fun scrollForwardAny(root: AccessibilityNodeInfo): Boolean {
        for (node in findAllScrollables(root)) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                // 记下这次用的是哪个容器 —— "滚不动"时这是最关键的排查线索。
                val id = node.viewIdResourceName?.substringAfterLast('/') ?: "?"
                AdSkipRuntime.log("  ↳ 用容器 $id（${node.className}）滚动")
                return true
            }
        }
        return false
    }

    /**
     * 收集当前页面里所有可滚动的容器，**RecyclerView 优先**。
     *
     * 为什么优先它：很多页面为了让标题栏折叠，在外层套了 CoordinatorLayout /
     * NestedScrollView —— 它们**也**声明可滚动，但对外层发滚动未必有效，
     * 真正装列表的往往是里面的 RecyclerView。
     *
     * ⚠️ 实测补充：本机钱包那页**最终生效的其实是外层那两个 ScrollView**
     *（`coordinator_layout` / `nested_scroll_view`），因为 RecyclerView
     * 压根没出现在候选里。所以排序只是"优先"，**不等于**只能用它 ——
     * [scrollForwardAny] 会挨个试，谁成功算谁。
     */
    @Suppress("DEPRECATION")
    private fun findAllScrollables(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        var visited = 0
        val recyclerViews = mutableListOf<AccessibilityNodeInfo>()
        val others = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (++visited > MAX_SCROLL_SEARCH_NODES) break
            if (node.isScrollable) {
                val canForward = node.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                if (canForward) {
                    if (node.className?.toString()?.contains("RecyclerView") == true) {
                        recyclerViews.add(node)
                    } else {
                        others.add(node)
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return recyclerViews + others
    }

    /**
     * 找"该在它上面滚动"的容器 —— [findAllScrollables] 优先，退一步用 [findListLike]。
     *
     * ⚠️ 为什么不能只用前者：它的判据是 **`isScrollable` 且 `actionList` 里有
     *    `ACTION_SCROLL_FORWARD`**，而**列表停到底部时这两个都不成立**
     *  （已经没有"还能往后滚"的余地了）。实测「所有应用」页就是这样：
     *    ```
     *    · @apps_list androidx.recyclerview.widget.RecyclerView
     *        Rect(0, 0 - 1440, 2803) childCount=11        ← 列表明明在，11 个子项
     *    · 连续 9 次都没有可滚动容器，且未找到目标          ← 却这么判失败
     *    ```
     *    而要找的目标其实就在**视口上方**一点。
     *
     * 退路的用途只有一条：**让我们还能用手势去翻它** ——
     * 真实手势不受 `actionList` / `isScrollable` 这些标记的限制。
     */
    private fun findScanTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findAllScrollables(root).firstOrNull() ?: findListLike(root)

    /**
     * 按**类名**找"看着像列表"的容器，**不管它当前能不能往后滚**。
     *
     * RecyclerView / ListView 优先（真正装列表的多半是它们），
     * 都没有才退到 ScrollView 一类。BFS 顺序保证拿到的是**最靠外**的那一个。
     */
    @Suppress("DEPRECATION")
    private fun findListLike(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var visited = 0
        val scrollViews = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (++visited > MAX_SCROLL_SEARCH_NODES) break

            val cls = node.className?.toString().orEmpty()
            val isList = cls.contains("RecyclerView") || cls.contains("ListView")
            val isScroll = cls.contains("ScrollView")

            if ((isList || isScroll) && node.hasVisibleArea()) {
                if (isList) return node
                scrollViews.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return scrollViews.firstOrNull()
    }

    /** 这个节点在屏幕上有没有可见面积（手势要有地方落）。 */
    private fun AccessibilityNodeInfo.hasVisibleArea(): Boolean {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        return bounds.width() > 0 && bounds.height() > 0
    }

    private fun doPressBack(step: AutomationStep.PressBack) {
        // ⚠️ **这个判断必须排在最前面，包括排在守卫之前** —— 实测踩到过：
        //    按返回是异步的（`performGlobalAction` 只是把事件注进去），所以
        //    `advance()` 要延迟到页面切完。在这段"已按、还没推进"的窗口里，
        //    本方法会被**反复调用**（每次 `attemptCurrentStep` 都可能进来）——
        //    而那时页面**已经切走了**，守卫必然不满足，于是：
        //      ① 打出一条自相矛盾的日志「页面对不上，不按返回」（其实早就按了）；
        //      ② **又调一次 `advance()`**，和前面那个延迟回调叠加 → **一步被跳掉**。
        //    现象就是"步骤飞快往前跑、好几步根本没执行"。
        if (stepActionIssued) return

        // ── 守卫：页面对不上就不按（见 [AutomationStep.PressBack.guard]）──
        //
        // ⚠️ 这是"退过头"的唯一防线。前面一个 `AwaitFor(optional = true)`
        //    跳过之后，我们可能**压根没进到那一层**，此时按返回会把**上一层**
        //    也退掉 —— 后面所有步骤都会落在错误的页面上。
        step.guard?.let { guard ->
            if (findNode(listOf(guard)) == null) {
                // ⚠️ 先把"这一步处理过了"记上，再 `advance()`：否则推进的一瞬间
                //    若还有人调进来（延迟回调 / 重入），会再 `advance()` 一次。
                stepActionIssued = true
                AdSkipRuntime.log("  ↳ ${step.label}：页面对不上（没等到守卫），不按返回")
                advance()
                return
            }
        }

        stepActionIssued = true
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        AdSkipRuntime.log("  ↳ 已按返回键")
        handler.postDelayed({ advance() }, BACK_SETTLE_MS)
    }

    /**
     * 开关操作的**统一实现**（开 / 关只差一个方向）。
     *
     * @param targetChecked 期望的最终状态：true = 要打开，false = 要关闭。
     *   "幂等判定"和"读回验证"两端都由它决定方向，其余逻辑两者完全一致。
     */
    private fun doToggle(
        task: AdTask,
        matches: List<Match>,
        confirm: Match?,
        targetChecked: Boolean,
    ) {
        // 目标状态的中文名，用来拼日志（"已关闭" / "已开启"…）。
        val targetName = if (targetChecked) "开启" else "关闭"

        // ── 第一优先：确认对话框 ──
        //
        // ⚠️ 必须排在"找开关"**之前**，这是实测踩出来的：
        //    点开关后弹出的对话框会**盖上整个页面**，此时 `findNode(matches)`
        //    返回 null —— 若把这个 null 当成"开关已消失 = 关闭成功"，就会**误报成功**
        //    （实测日志：点完 15 ms 就报了"已关闭（开关已消失）"，而弹窗才刚要出现；
        //     紧接着下一步就被这个没点掉的弹窗挡住，一路超时）。
        if (confirm != null && stepActionIssued) {
            val dialog = findNode(listOf(confirm))
            if (dialog != null) {
                // ⚠️ 只点**一次**：点完弹窗要几十~几百毫秒才消失，这期间每一轮都会
                //    再次命中同一个按钮 —— 重复点没有意义，只会把日志刷爆
                //    （实测连点了 6 次）。`shouldSkip` 拦不住它：弹窗重建之后
                //    节点对象是新的，而去重是按节点记账的。
                if (!confirmClicked && clicker.click(dialog.node)) {
                    clicker.recordClick(dialog.node)
                    confirmClicked = true
                    AdSkipRuntime.log("  ↳ 确认弹窗：${dialog.description}")
                }
                // 让弹窗先消失，下一轮再读回开关的真实状态。
                return
            }
        }

        val hit = findNode(matches)
        if (hit == null) {
            if (!stepActionIssued) return
            // ⚠️ 两种"找不到"要分清：
            //   · 弹窗还挂着 → 开关只是被盖住了，**不能**判成功；
            //   · 有确认框但还没确认过 → 也不放行（宁可等超时，也不误报）。
            val dialogStillThere = confirm != null && findNode(listOf(confirm)) != null
            val confirmed = confirm == null || confirmClicked
            if (!dialogStillThere && confirmed) {
                finishToggle(task, "已$targetName（开关已消失）")
            }
            return
        }

        if (!stepActionIssued) {
            val initial = readCheckedState(hit.node)
            AdSkipRuntime.log(
                "  ↳ 开关当前状态：${when (initial) {
                    true -> "开启"
                    false -> "关闭"
                    null -> "无法读取"
                }}（候选#${hit.matchIndex}）",
            )
            // 幂等：已经处于目标状态 → 判定成功，且**不做任何点击**。
            //
            // ⚠️ 两个方向对"读不到状态"（null）的处理刻意不同，沿用这里已有的取舍：
            //    · 目标"关" → 读不到也算通过（历史上就这么做的：设置页里开关节点
            //      结构多变，卡在 null 上会让"本来就是关的"永远走不完）；
            //    · 目标"开" → 读不到**不**放行，继续走点击 —— 权限页上的开关
            //      必须真的变开，宁可等超时失败也不能误报"已开启"。
            val alreadyThere = if (targetChecked) initial == true else initial != true
            if (alreadyThere) {
                finishToggle(task, "本来就是${targetName}状态")
                return
            }
            if (clicker.shouldSkip(hit.node)) return

            stepActionIssued = true
            if (clicker.click(hit.node)) {
                clicker.recordClick(hit.node)
                // ⚠️ 记下点击时刻：读回确认要等过了冷却期（见 [TOGGLE_SETTLE_MS]）。
                toggleClickAtMs = SystemClock.uptimeMillis()
                toggleOppositeStreak = 0
                AdSkipRuntime.log("  ↳ 已点击开关，等待状态变化")
            } else {
                AdSkipRuntime.log("  ↳ 开关点击未成功，稍后重试")
                stepActionIssued = false
            }
            return
        }

        // ── 读回确认 ──
        //
        // ⚠️ **先等过冷却期**：刚点完就读，拿到的大概率是**点击前的旧值**
        //   （树还没刷新完），于是被误判成"没点上"、去补点，反而把刚关掉的
        //    开关又打开 —— 这就是"关了又开"那个 bug。见 [TOGGLE_SETTLE_MS]。
        if (SystemClock.uptimeMillis() - toggleClickAtMs < TOGGLE_SETTLE_MS) return

        val state = readCheckedState(hit.node)
        when {
            state == targetChecked -> finishToggle(task, "已$targetName")

            // 读不到状态 —— **两种情形要一起考虑**：
            //
            //   · 节点结构不是预期的那种 → 不能凭猜测报成功，交给超时兜底；
            //   · 树正在重建（刚好在状态切换那一瞬）→ 这恰恰说明**状态正在动**，
            //     此时若不把 [toggleOppositeStreak] 清零，下一轮再读到一次旧值
            //     就会凑够 [TOGGLE_VERIFY_STREAK]、去"补点一次"，
            //     把刚关掉的开关又点开 —— 也就是这个修复本来要防的那个 bug
            //     换了个入口绕回来。
            //
            // 两种情况从外部无法区分，所以统一清零：这正是"**连续**"二字的要求。
            state == null -> {
                toggleOppositeStreak = 0
                if (!nullStateLogged) {
                    nullStateLogged = true
                    AdSkipRuntime.log("  ↳ 点击后暂时读不到开关状态，继续确认")
                }
            }

            else -> {
                // 点了但状态没变 —— 再补一次（比如点在整行上，真正要点的
                // 是右侧那个小开关）。去重保护会拦住无限重试。
                //
                // ⚠️ 但**要连续读到相反状态才动手**：过了冷却期仍可能读到旧值，
                //    单次读到不足以证明"真的没点上"。见 [TOGGLE_VERIFY_STREAK]。
                val opposite = if (targetChecked) "关闭" else "开启"
                toggleOppositeStreak++
                if (toggleOppositeStreak < TOGGLE_VERIFY_STREAK) {
                    AdSkipRuntime.log(
                        "  ↳ 点后仍为$opposite（第 $toggleOppositeStreak 次），再确认一轮",
                    )
                    return
                }
                if (clicker.shouldSkip(hit.node)) {
                    AdSkipRuntime.log("  ↳ 点后仍为$opposite，去重已拦截，交由超时兜底")
                    return
                }
                AdSkipRuntime.log("  ↳ 点后仍为$opposite，再补一次点击")
                if (clicker.click(hit.node)) {
                    clicker.recordClick(hit.node)
                    // 重开一次冷却期，别让下一轮拿着同样过期的快照接着判定。
                    toggleClickAtMs = SystemClock.uptimeMillis()
                    toggleOppositeStreak = 0
                }
            }
        }
    }

    // -------------------------------------------------------------------------

    private fun advance() {
        stepIndex++
        val t = task ?: return
        if (stepIndex >= t.steps.size) {
            // 所有步骤走完 —— 任务在这里结束。消息优先用最近一次 `ToggleOff`
            // 的结果描述（"已关闭" / "本来就是关闭状态"），比"全部步骤完成"有用得多。
            complete(t, lastToggleMessage ?: "全部步骤完成")
        } else {
            beginStep()
        }
    }

    private fun complete(task: AdTask, message: String) {
        running = false
        handler.removeCallbacks(retryTick)

        // ⚠️ 顺序是关键：必须在 onResult **之前**把 task 置空。
        //
        // `onResult` 是**同步重入**的 —— 引擎收到 Completed 会立刻
        // `nextTask()` → `runner.start(下一项)`，于是 `task` 已被换成新任务。
        // 若把清空写在 onResult 之后（历史写法），就会把**刚启动的下一项**清掉：
        // 状态变成 `running == true` 而 `task == null`，`retryTick` 每 300ms
        // 报一次"当前无任务"，整个会话永久僵死。
        //
        // 现象：第 1 项跑得完全正常，**自动接第 2 项时**日志停在 `[1/N]`
        // 那一行再也不动，界面也不再有任何反应（实测稳定复现）。
        this.task = null
        onResult(task, RunnerResult.Completed(message))
    }

    private fun fail(task: AdTask, reason: String, retryable: Boolean = false) {
        running = false
        handler.removeCallbacks(retryTick)
        val step = task.steps.getOrNull(stepIndex)

        // ── 快照审查：失败时自动导出"当时的界面" ──
        //
        // 排查"为什么没找到/点不动"，最有价值的信息是失败那一刻屏幕上
        // 有哪些节点。用户点「复制」整段发出去就能定位问题，不用截图猜。
        //
        // ⚠️ 必须在 onResult **之前**拍：`retryable` 为真时 onResult 会让
        //    引擎立刻重跑同一项（`runner.start`），界面马上开始变化 ——
        //    放到后面就只能拍到"已经翻页"的界面，等于白拍。
        runCatching {
            // ⚠️ 快照必须和 [findNode] 一样**覆盖全部窗口** —— 否则失败时看到的
            //    只是活动窗口那一份，很容易得出"页面上根本没有这个控件"的
            //    错误结论（实测：真正要找的开关恰好不在活动窗口里）。
            val active = service.rootInActiveWindow
            val dump = buildString {
                // ⚠️ `verbose = true`：失败排查时**容器也要看** —— 只有
                //    `childCount` 与"实际能读到几个子节点"这组数字，才能区分
                //    "页面上根本没有这个控件"和"有、但取不到"（实测踩过：
                //    开关明明在屏幕上、树里却找不到，非诊断模式完全看不出原因）。
                append(
                    dumpPageSnapshot(active, maxNodes = FAIL_SNAPSHOT_MAX_NODES, verbose = true),
                )
                for (window in service.windows) {
                    if (active != null && window.id == active.windowId) continue
                    // ⚠️ **"根读不到"的窗口也必须列出来**：这里以前是
                    //    `val root = window?.root ?: continue` —— 把它**静默跳过**了，
                    //    于是失败快照给出的结论是"service.windows 里只剩状态栏"，
                    //    方向完全跑偏（实测「通知」页正是这种窗口，见坑 #43）。
                    @Suppress("DEPRECATION")
                    val title = window?.title ?: "-"
                    appendLine()
                    append("· —— 另一个窗口（id=").append(window.id)
                    append("，type=").append(window.type)
                    append("，title=").append(title)
                    val root = window?.root
                    if (root == null) {
                        append("，根=读不到（这个窗口的节点树取不到））")
                    } else {
                        append("，根=").append(root.packageName).append("）")
                        appendLine()
                        append(
                            dumpPageSnapshot(
                                root,
                                maxNodes = FAIL_SNAPSHOT_MAX_NODES,
                                verbose = true,
                            ),
                        )
                    }
                }
            }
            AdSkipRuntime.log("  ↳ 页面快照（失败时刻）：\n$dump")
        }

        // ⚠️ 同理：清空要放在 onResult **之前**，否则会清掉重试启动的新任务
        //    （与 [complete] 完全同一个坑）。
        this.task = null
        onResult(
            task,
            RunnerResult.Failed(
                stepIndex = stepIndex,
                stepLabel = step?.label ?: "?",
                reason = reason,
                retryable = retryable,
            ),
        )
    }

    // -------------------------------------------------------------------------
    // 节点读写
    // -------------------------------------------------------------------------

    /**
     * 找匹配节点（多候选，任一命中即可）。
     *
     * ⚠️ **不只看活动窗口**，而是把 `service.windows` 里的每个窗口都找一遍。
     *
     * ## 为什么（实测踩到）
     *
     * MIUI 的「安装未知应用」页（`ManageExternalSourcesActivity`）在**活动窗口**
     * 里只暴露了 ActionBar 和一段说明文字 —— **真正那个
     * `Switch id=switchWidget` 不在其中**。于是脚本一直等到超时，而屏幕上那个
     * 开关明明就在：截图可见、`uiautomator dump` 也能看到（因为 uiautomator
     * 自己会遍历**全部**窗口）。
     *
     * 这个现象极具误导性：失败快照里只剩说明文字，看起来像"页面没加载出来"。
     *
     * 服务配置里 `flagRetrieveInteractiveWindows` 早就开了
     * （见 `ad_skip_accessibility_config.xml`），但此前**没有任何代码用它** ——
     * 等于白开。这里把它用起来。
     *
     * ## 顺序
     *
     * 先活动窗口（绝大多数步骤都在这里命中，也最省一次跨进程遍历），
     * 再逐个兜底；命中即返回。
     */
    private fun findNode(matches: List<Match>): NodeHit? {
        val active = service.rootInActiveWindow
        if (active != null) {
            NodeMatcher.findOne(active, matches)?.let { return it }
        }

        for (window in service.windows) {
            val root = window?.root ?: continue
            // 活动窗口刚查过，跳过。⚠️ 不能按对象比较 —— `getRoot()` 每次都返回
            // 新的包装对象，`==` 永远为 false；按 windowId 认才对。
            if (active != null && window.id == active.windowId) continue
            NodeMatcher.findOne(root, matches)?.let { return it }
        }
        return null
    }

    /**
     * 把匹配到的节点解析成**真正可写**的输入框。
     *
     * 匹配到的常常是外层容器（比如带 id 的 LinearLayout），
     * 真正能写的是它的某个后代 EditText。
     */
    private fun resolveEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? = when {
        node.isEditable -> node
        else -> findEditableDescendant(node)
    }

    /**
     * 写文本 + （可选）提交搜索。
     *
     * ⚠️ 先 `ACTION_FOCUS` 再 `ACTION_SET_TEXT` —— 顺序不能反。
     * MIUI 设置搜索框在**未聚焦**时接受写入、但不会启动查询流程，
     * 表现就是"输入了「广告」，下面的列表一直空着"。聚焦之后同一个写入才会触发搜索。
     */
    private fun writeText(target: AccessibilityNodeInfo, step: AutomationStep.Input): Boolean {
        if (step.requestFocus) {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                step.text,
            )
        }
        if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false

        // 兜底：不靠 TextWatcher、只认按键的搜索框。Android 11 起才有这个动作。
        if (step.submitAfter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // ⚠️ 走 `AccessibilityAction.ACTION_IME_ENTER.id`，而不是
            //    `AccessibilityNodeInfo.ACTION_IME_ENTER` —— 后者在本项目的编译 SDK 上
            //    直接解析不到（Unresolved reference）。这个写法在任何 compileSdk 下都稳，
            //    也省得把 0x010200AE 这种魔法值写进代码。
            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return true
    }

    private fun readText(node: AccessibilityNodeInfo): String? =
        runCatching { node.text?.toString() }.getOrNull()

    private fun findEditableDescendant(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (++visited > 200) return null
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return null
    }

    /**
     * 读开关状态。
     *
     * 先看自己，再**广度优先**向下找（最多 [MAX_SWITCH_SEARCH_NODES] 个节点）——
     * 有些 ROM 把 Switch 包在容器里，只查直接子节点会读不到状态，
     * 而调用方要靠这个值判断"到底关掉没有"，读漏了就会误报失败。
     *
     * ⚠️ 读不到时返回 null，调用方**绝不能**把 null 当成"已关闭"。
     */
    @Suppress("DEPRECATION")
    private fun readCheckedState(node: AccessibilityNodeInfo): Boolean? {
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(node)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (++visited > MAX_SWITCH_SEARCH_NODES) return null
            if (isSwitchNode(current)) return current.isChecked
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return null
    }

    private fun isSwitchNode(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString() ?: return false
        return cls.contains("Switch") || cls.contains("CheckBox") || cls.contains("ToggleButton")
    }
}
