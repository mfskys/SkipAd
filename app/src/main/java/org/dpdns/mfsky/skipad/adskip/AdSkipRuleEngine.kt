package org.dpdns.mfsky.skipad.adskip

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 任务引擎 —— 在**用户点过「开始处理」之后**，按脚本逐项执行清单。
 *
 * ## 与"常驻广告拦截器"的根本区别
 *
 * 这个应用是**一次性工具**：新手机跑一遍，把内置广告开关全部关掉，之后再也用不到。
 * 所以引擎遵守三条铁律：
 *
 * 1. **不自动开始**：无障碍服务连上了也什么都不做，必须等用户点「开始处理」。
 * 2. **只处理清单内的目标**：清单之外的事件一律忽略。
 * 3. **做完就停**：整个清单走完（或有一项彻底失败）就结束会话，此后不再动屏幕。
 *
 * 换句话说，**它不会在你正常用手机的时候跳出来点东西**。
 *
 * ## 防呆：页面不对就从头重跑，而不是卡死
 *
 * 全自动最怕"用户中途去点开了别的设置页" —— 脚本会杵在错误页面上等满超时。
 * 所以这里做了两层：
 *
 * - [AutomationRunner] 检测到偏离目标路径（见 [AdTask.allowedPackages]）时，
 *   立刻返回一个 `retryable` 的失败；
 * - 本类收到后**把这一项从第 1 步重跑**（第 1 步就是"打开设置"，天然起到
 *   "回到起点"的作用），最多重试 [MAX_TASK_RETRY] 次，之后才判定真失败。
 *
 * ⚠️ 但重跑有个**止损**：若这一轮又挂在上一轮的**同一步**，说明重跑解决不了
 *（典型是"页面结构不对"而非"等异步结果"），立刻放弃重试、如实报失败 ——
 * 判据与理由见 [lastRetryFailStep]。没有这道止损时，一次结构性失败会让用户
 * 眼睁睁看它把前面所有步骤走三遍、等三段同样的超时，最后还是失败。
 *
 * ⚠️ 重跑前必须 [NodeClicker.resetSession]：上一轮已经点过的节点会被去重闸门挡下，
 * 不清空的话重试时**一步都点不动**。
 *
 * ## 三种结束方式，都会把用户送回本应用
 *
 * | 结束方式 | 结果 |
 * |---|---|
 * | 全部处理完 | 成功报告 |
 * | 某一项彻底失败 | 失败报告 + 该任务的引导路径 |
 * | 用户手动点「停止」 | 已停止报告 |
 *
 * 另外，**开关本来就是关闭的**（幂等命中）同样算这一项完成、同样会送回来 ——
 * 用户不需要为了"这个本来就是关的"自己退出系统设置。
 */
internal class AdSkipRuleEngine(
    private val service: AccessibilityService,
    private val clicker: NodeClicker,
) {

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"

        /** 内容事件节流间隔。 */
        const val CONTENT_THROTTLE_MS = 100L

        /**
         * 「后台弹出界面」状态查询的节流间隔。
         *
         * ⚠️ 这个查询挂在**每个无障碍事件**上，而它内部是一次 AppOps（binder）调用。
         *    滚动列表 / 页面刷新会瞬间产生几百个 `TYPE_WINDOW_CONTENT_CHANGED` ——
         *    不节流的话实测**同一毫秒查了几十次**，日志被
         *    `后台弹出界面 op=10021 mode=0` 刷屏（用户随便一贴就是几百行），
         *    主线程也被这些 binder 调用拖住、连累后面的滚动和点击。
         *
         * 1 秒一次完全够：用户的动作（去系统页开权限再回来）本来就是秒级的；
         * 而"只认翻转"的逻辑**不依赖高频采样** —— 一次翻转只会被消费一次。
         */
        const val GRANT_CHECK_INTERVAL_MS = 1000L

        /**
         * 单项任务的最大重试次数。
         *
         * 取 2 而不是无限：用户如果一直在别的页面逛，无限重试就会反复弹设置页、
         * 像在抢屏幕一样，反而更烦人。两次足够覆盖"手滑点歪了"这类情况。
         *
         * ⚠️ 它只是**上限**，不是"一定会重试这么多次" —— 一旦发现重跑后仍卡在
         *    同一步（见 [lastRetryFailStep]），会立刻停下，不再浪费这一项的时间。
         */
        const val MAX_TASK_RETRY = 2

        /**
         * 结束会话后、切回本应用前的停顿。
         *
         * 不是拖延，是**必须的**：
         * 1. 让刚拨动的开关动画播完，用户能亲眼看到"它确实被关掉了"；
         * 2. 避免刚做完动作就抢走前台，让系统把设置页的收尾工作做完。
         */
        const val RETURN_DELAY_MS = 700L

        /** 发出"切回本应用"之后，隔多久读一次前台来验证。 */
        const val VERIFY_DELAY_MS = 600L

        /**
         * 验证失败时最多按几次返回键。
         *
         * 用户那次处理的路径可能压了好几层（搜索结果页 → 详情页 → 设置首页……），
         * 所以要给足次数；但也不能无限按 —— 万一我们本来就不在栈里，
         * 一直按下去会把用户自己的其它应用也退掉。
         */
        const val MAX_BACK_ATTEMPTS = 6

        /**
         * 「安装未知应用」等待用户授权的最长时间。
         *
         * 用户可能中途放弃 —— 到点就撤掉提示条、切回本应用，
         * 别让一条提示永远挂在他的设置页上。
         */
        const val INSTALL_WATCH_TIMEOUT_MS = 3 * 60 * 1000L

        /** 轮询「安装未知应用」是否已授权的间隔。500ms ≈ 用户点完就会"立刻"切回来。 */
        const val INSTALL_WATCH_INTERVAL_MS = 500L
    }

    private val runner = AutomationRunner(
        service = service,
        clicker = clicker,
        onResult = ::onTaskResult,
        onProgress = ::onTaskProgress,
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 把"这一项已完成"落盘用的作用域。
     *
     * ⚠️ 不能写成 `runBlocking`：引擎运行在**主线程**
     * （[AutomationRunner] 用的是 `Handler(Looper.getMainLooper())`），
     * 阻塞着等磁盘写完会掉帧甚至 ANR。写入内容极小（一个 `Set<String>`），
     * 异步完成足够可靠。
     */
    private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 待执行清单（快照，会话开始时固定）。 */
    private var queue: List<AdTask> = emptyList()
    private var index = 0

    /**
     * 会话是否在跑。
     *
     * ⚠️ **它不是普通字段**：音量键过滤跟着它一起开关
     *（见 [AdSkipAccessibilityService.setKeyFilterEnabled]）。
     *
     * 那个能力只在会话期间才有意义，而它的代价是"服务永远接收所有按键" ——
     * 少关一次，用户平时按音量键就会被拖慢（实测踩过，见那个方法的说明）。
     * 挂在 setter 上、而不是散落在各处 `running = false` 的地方，就是为了
     * **不可能漏**：启动 / 用户停止 / 跑完 / 失败 / 授权超时，所有路径都汇到这里。
     */
    private var running = false
        set(value) {
            if (field == value) return
            field = value
            AdSkipAccessibilityService.instance?.setKeyFilterEnabled(value)
        }

    /**
     * 本次会话是不是「自动获取权限」。
     *
     * 两种流程共用同一个 [AutomationRunner]（启动 / 等待 / 点击 / 开关这套能力是现成的），
     * 但**收尾方式完全不同**：权限流程不写处理进度（它不属于那份广告清单，
     * 记进去会让"还剩几项"对不上）、不进结果列表，结束后回到 [SkipSession.Idle]
     * 而不是"处理完成"。
     */
    private var permissionMode = false

    /**
     * 「安装未知应用」等待用户授权的起始时刻，用于超时兜底
     *（见 [startInstallGrantWatch]）。
     */
    private var installWatchStartMs = 0L

    /**
     * 是否正在等用户手动打开「安装未知应用」。
     *
     * 单独一个标志、而不是复用 [running]：广告会话也用它（意义是"引擎在跑"），
     * 而两者的收尾方式完全不同 —— 见 [cancelInstallGrantWatch]。
     */
    private var installWatching = false

    /** 当前这一项已经重试过几次（换任务时归零）。 */
    private var taskRetryCount = 0

    /**
     * 上一轮重试**卡在第几步**（-1 = 这一项还没重试过；换任务时归零）。
     *
     * ## 它解决的是什么问题
     *
     * 可恢复的失败会让这一项**从第 1 步重跑**（见 [onTaskResult]）。但"可恢复"
     * 是由脚本声明的（[AutomationStep.AwaitFor.retryable]），粒度很粗：它同时
     * 盖住了两种情况 ——
     *
     * - "**等异步结果**没等到"（搜索要联网）→ 重跑一次确实可能赶上 ✔
     * - "**页面结构不对**"（例如某个 ROM 版本压根没那个节点）→ 重跑还是会
     *   停在同一个地方 ✘
     *
     * 两种都重跑两遍，代价是**成倍的空等**：失败在第 12 步时，用户要眼睁睁
     * 看它把前面 11 步走三遍、等三次同样的超时，最后结果照旧失败。
     *
     * 这里用一条极简的判据把后者摘出来：**上一轮挂在哪一步，这一轮又挂在
     * 同一步 → 说明重跑解决不了，立刻放弃重试**。
     * 反过来，若这一轮挂在**更靠后的步骤**，说明重跑有进展，那就再给一次机会。
     */
    private var lastRetryFailStep = -1

    private var lastContentEventMs = 0L

    // -------------------------------------------------------------------------
    // 会话控制（由主页按钮调用）
    // -------------------------------------------------------------------------

    /**
     * 开始（或**继续**）一次处理会话。
     *
     * ## "继续处理"就实现在这里
     *
     * ⚠️ 已完成的项**整项不入队** —— 这才是"继续"的含义。
     *
     * 有人会问：`ToggleOff` 不是本来就幂等吗（开关已经是关的 → 直接算成功、
     * 不点击）？是的，所以**重跑不会出错**。但"不出错"不等于"不浪费时间"：
     * 一个 9 步的任务重跑一遍照样要走完 9 步的等待与匹配。
     * 这里的跳过是**任务粒度**的 —— 连页面都不用打开。
     *
     * 全部任务都完成过时，不入队任何东西，只记一条日志告诉用户可以
     * 点「重新处理全部」（那个入口会先清空进度）。
     */
    fun startSession() {
        // 上一次的「安装未知应用」等待可能还挂着（用户点进去、没开开关就回来了 ——
        // 那种情况它要等满 3 分钟超时才收尾）。它也占着 `running` 标志，
        // 不清掉的话下面那行会直接把这次点击挡回去，表现为"点了开始处理没反应"。
        //
        // 用户既然来点这个按钮，就说明他不想再等那件事了。
        if (installWatching) cancelInstallGrantWatch()

        if (running) return

        // ── 先剔除"这台机器上根本做不了"的项 ──
        //
        // 天气、日历、应用商店、垃圾清理都是**独立应用**，用户完全可以只卸载其中一个。
        // 那种项会在第 1 步"打开 XX"就卡住（应用不存在，Intent 解析不到），
        // 只能干等到超时，然后在清单里留一条永远失败的红项。
        // 详见 [usableAdTasks]。
        val usable = usableAdTasks(service)
        val unavailable = BuiltinAdTasks.filterNot { it.isUsable(service) }

        val done = AdSkipRuntime.doneTaskIds.value
        queue = usable.filterNot { it.id in done }

        if (unavailable.isNotEmpty()) {
            AdSkipRuntime.log(
                "跳过 ${unavailable.size} 项（对应应用未安装或已停用）：" +
                    unavailable.joinToString("、") { it.title },
            )
        }

        if (queue.isEmpty()) {
            // 两种"没得做"要分开说 —— 否则用户看到"已全部处理完"会以为开关都关掉了，
            // 而实际可能是这些应用压根没装。
            AdSkipRuntime.log(
                if (usable.isEmpty()) {
                    "没有可处理的项目：清单里的应用在这台设备上都没有安装"
                } else {
                    "上次已把所有项目处理完，无需重复。要重跑请点「重新处理全部」"
                },
            )
            return
        }

        beginQueue(queue)
        if (queue.size < usable.size) {
            AdSkipRuntime.log(
                "继续处理：跳过 ${usable.size - queue.size} 项已完成的，" +
                    "本次处理 ${queue.size} 项",
            )
        }
    }

    /**
     * 只处理**清单里的某一项** —— 用户在主页清单上点它触发的。
     *
     * ## 与 [startSession] 的关系
     *
     * 走的是**同一套执行链路**（同一个 [AutomationRunner]、同一份步骤脚本、
     * 同样的重试与防呆），唯一的差别是**入队的只有这一项**。
     *
     * 所以它天然继承了两个行为：
     * - 处理完自动切回本应用（[bringAppToFront] 挂在队列跑完那一刻）；
     * - 失败同样会重试 [MAX_TASK_RETRY] 次，然后如实上报。
     *
     * ## 为什么不复用 [startSession]
     *
     * 那一个会自己按"已完成"过滤清单 —— 而用户点某一行时的意思很明确：
     * **我就要跑这一项**，哪怕它历史上已经成功过（比如他刚手动改回去了、
     * 想让我们再关一次）。
     */
    fun startSingleTask(taskId: String) {
        if (installWatching) cancelInstallGrantWatch()
        if (running) return

        val task = BuiltinAdTasks.firstOrNull { it.id == taskId } ?: return

        // 界面已经不给点"应用未安装"的项了（那几行 onClick = null），
        // 这里再兜一道：万一包是在会话之间被卸载/停用的，
        // 至少给出一句能看懂的说明，而不是让脚本白等到超时。
        if (!task.isUsable(service)) {
            AdSkipRuntime.log("${task.title}：对应应用未安装或已停用，跳过")
            return
        }

        beginQueue(listOf(task))
    }

    /**
     * 真正开跑一份队列。两个入口（整批 / 单项）共用这一段。
     *
     * ⚠️ 里面那几行看似简单，但**顺序不能换**：
     * 先把 `running` 立起来，再挂前台服务，最后才 `nextTask()`。
     * 前台服务必须在自动化开始**之前**就位（理由见下面的注释）。
     */
    private fun beginQueue(tasks: List<AdTask>) {
        queue = tasks
        index = 0
        running = true
        taskRetryCount = 0
        clicker.resetSession()
        AdSkipRuntime.startSession(queuedCount = tasks.size)
        // ⚠️ 必须在真正开跑**之前**挂上前台服务。
        //
        // 跑自动化时本应用会长时间待在后台（它自己去打开了系统设置 / 下载管理），
        // 厂商 ROM（MIUI / HyperOS）会把这样的进程判为可冻结、连整个 uid 一起冻住 ——
        // 那时**所有线程停止调度**：日志停在一行、界面不动，
        // 连看门狗和心跳线程都不再触发。
        //
        // 实测证据（cgroup v2）：`/sys/fs/cgroup/apps/uid_xxx/cgroup.freeze` = 1，
        // 而进程状态仍是 S、无 SIGSTOP、CPU 0% —— 从进程内部完全无法自证。
        // 详见 [SkipAdAutomationService] 的 KDoc。
        SkipAdAutomationService.start(service)
        nextTask()
    }

    fun stopSession() {
        if (!running) return
        val wasPermission = permissionMode
        permissionMode = false
        running = false
        runner.cancel()
        // 会话结束就撤掉前台服务 —— 不给用户留一个常驻通知。
        SkipAdAutomationService.stop(service)
        if (wasPermission) {
            AdSkipRuntime.finishPermissionSession(stopped = true)
        } else {
            AdSkipRuntime.finishSession(stopped = true)
        }
        // 用户主动停止时不延迟：他就在等我们撒手，立刻回去最符合预期。
        bringAppToFront()
    }

    /**
     * **音量键紧急停止** —— 由 [AdSkipAccessibilityService.onKeyEvent] 调用。
     *
     * ## 为什么要有它
     *
     * 自动化跑起来之后本应用在后台，用户想喊停只有一个办法：切回本应用、
     * 点「停止」。而那一刻脚本正在别的应用里点来点去，把界面找回来得点好几下 ——
     * 音量键是**任何界面下都摸得到**的那个"刹车"。
     *
     * ## 与 [stopSession] 的关系
     *
     * 收尾完全走同一条路（中止当前项 → 撤前台服务 → 会话标记为已停止 →
     * 立刻回本应用）。这里只多做两件事：写一句日志，以及
     * **告诉调用方到底停没停** —— `onKeyEvent` 靠这个返回值区分
     * "真的刹住了"和"用户只是随手调了下音量"，后者不该在日志里留痕。
     *
     * ## ⚠️ 返回值不代表可以吞掉按键
     *
     * 调用方**不能**因为拿到 true 就去消费这个事件：音量键必须继续交给系统，
     * 用户调音量的动作不能被我们打断。
     *
     * @return true = 确实中止了一次正在跑的会话。
     */
    fun emergencyStop(): Boolean {
        if (!running) return false
        AdSkipRuntime.log("音量键：紧急停止")
        stopSession()
        return true
    }

    /**
     * 开始一次「自动获取权限」会话 —— 由主页权限引导里的「自动获取」发起。
     *
     * ## 共用什么、不共用什么
     *
     * 共用 [AutomationRunner]（启动 / 等待 / 点击 / 开关这套能力现成，
     * 另写一套只会多出一份要同步维护的等价逻辑）；**不共用**的是收尾 ——
     * 见 [finishPermissionSession]。
     *
     * ## ⚠️ 必须由用户在前台点击触发
     *
     * 第 1 步是 `Launch`，而 Android 10 起**禁止后台应用启动 Activity**
     * （无障碍豁免不覆盖它）。应用在后台时这一步会被 HyperOS **静默丢掉**，
     * 现象就是"点了自动获取，然后什么都没发生"。所以入口只能挂在主页按钮上，
     * 不能挪到 `onServiceConnected` 之类的后台时机里（实测踩过）。
     */
    fun startPermissionSession() {
        if (running) return

        permissionMode = true
        running = true
        queue = listOf(PermissionTasks.task)
        index = 0
        taskRetryCount = 0
        clicker.resetSession()
        AdSkipRuntime.startPermissionSession()
        // 与广告会话一样挂前台服务：自动化期间本应用长时间待在后台，
        // 没有它会被厂商 ROM 冻住（详见 SkipAdAutomationService 的 KDoc）。
        //
        SkipAdAutomationService.start(service)
        nextTask()
    }

    /**
     * 等用户**手动**完成「安装未知应用」的授权，完成即自动切回本应用。
     *
     * ## 为什么这件事用不上 [AutomationRunner]
     *
     * 因为那一页**没有可读的节点**：MIUI 把装载开关的列表项标成了
     * `noHideDescendants`，无障碍树里只剩旁边那段说明文字（实测快照见
     * `PermissionTasks` 的类文档）。既然读不到，那套"匹配 → 点击"就无从下手，
     * 只能换一条路：**跳过去 + 挂一条提示 + 轮询权限状态**。
     *
     * ## ⚠️ 必须由用户在前台点击触发
     *
     * 这里的 `startActivity` 会打开系统设置页，而 Android 10 起禁止后台应用
     * 启动 Activity（无障碍豁免不覆盖它）。所以入口只能挂在主页按钮上。
     */
    fun startInstallGrantWatch() {
        // ⚠️ 这里**不能**写 `if (running) return`。
        //
        // （下半段的判断同理，见 [startSession] 里对 installWatching 的处理。）
        //
        // 上一次的等待很可能还挂着：用户点进去看一眼、没开开关就返回了 ——
        // 那种情况下 [installWatchTick] 要等满 3 分钟才会超时收尾。
        // 若这行直接 return，用户再点「去开启」就**毫无反应**：
        // 按钮明明在那儿，点了却什么都不发生（实测反馈正是如此）。
        //
        // 正确做法：先把上一次收尾，再重新开始 —— 每一次点击都真的把他送过去。
        if (installWatching) cancelInstallGrantWatch()

        running = true
        installWatching = true
        installWatchStartMs = System.currentTimeMillis()

        AdSkipRuntime.log("打开「允许从此来源安装应用」")
        // 保活窗顺带把"应用在后台"这件事兜住 —— 这一整段应用都在后台。
        SkipAdAutomationService.start(service)

        val opened = runCatching {
            service.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.fromParts("package", service.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (!opened) {
            AdSkipRuntime.log("跳转失败，请手动去系统设置")
        }

        mainHandler.post(installWatchTick)
    }

    /**
     * 结束等待，但**不做任何收尾动作**（不切回前台、不写日志）。
     *
     * 专供"用户又点了一次「去开启」"这一刻：接下来要马上重新跳转，
     * 这时候把本应用拉回前台只会跟紧接着的跳转打架。
     */
    private fun cancelInstallGrantWatch() {
        installWatching = false
        running = false
        mainHandler.removeCallbacks(installWatchTick)
        SkipAdAutomationService.stop(service)
    }

    /**
     * 轮询「安装未知应用」是否已被打开。
     *
     * ⚠️ 用轮询而不是事件驱动：这个开关的切换**不会**产生我们订阅的那两类
     * 无障碍事件，只能主动查。500ms 的间隔足够让"用户点完 → 切回本应用"
     * 在主观上等同于"立刻"。
     */
    private val installWatchTick = object : Runnable {
        override fun run() {
            if (!running) return

            if (AdSkipPermission.canRequestPackageInstalls(service)) {
                finishInstallGrantWatch("「允许安装应用」已开启")
                return
            }
            if (System.currentTimeMillis() - installWatchStartMs > INSTALL_WATCH_TIMEOUT_MS) {
                finishInstallGrantWatch("等待「允许安装应用」超时，已停止")
                return
            }
            mainHandler.postDelayed(this, INSTALL_WATCH_INTERVAL_MS)
        }
    }

    private fun finishInstallGrantWatch(message: String) {
        val granted = message.contains("已开启")
        installWatching = false
        running = false
        mainHandler.removeCallbacks(installWatchTick)
        SkipAdAutomationService.stop(service)
        AdSkipRuntime.log(message, handled = granted)
        bringAppToFront(RETURN_DELAY_MS)
    }

    // -------------------------------------------------------------------------

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val pkg = event.packageName?.toString() ?: return

        // ── 等「安装未知应用」授权期间，用户回到了本应用 ──
        //
        // ⚠️ 这里**绝不能**直接判定"他放弃了"。
        //
        // MIUI 的授权流程是这样的：在那一页点「打开」之后，系统会再弹一个
        // **10 秒倒计时**确认框，用户点完「同意」权限才真正生效。而那个倒计时
        // 会让设置页暂时退到后台、把本应用顶到前台 ——
        // 如果一看到"用户回来了"就收尾，就会在权限还没授下来的时候把等待撤销，
        // 10 秒后用户点了「同意」也**没人再接住**（实测反馈正是如此）。
        //
        // 所以这里只做一件事：**顺手查一次权限**。
        //   · 已经授予（比如用户绕回来时权限其实已经生效）→ 正常收尾；
        //   · 还没授予 → **继续等**，倒计时结束后用户点「同意」照样能被接住。
        //
        // ⚠️ 这条判断必须放在下面的包名过滤**之前** —— 要捕获的恰恰是
        //    "用户回到了我们自己"，放到后面会被 `pkg == service.packageName` 挡掉。
        if (installWatching &&
            pkg == service.packageName &&
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            if (AdSkipPermission.canRequestPackageInstalls(service)) {
                finishInstallGrantWatch("「允许安装应用」已开启")
            }
            return
        }

        if (pkg == service.packageName || pkg == SYSTEM_UI) return

        // 没在跑：只做一件**不碰屏幕**的事 —— 等用户在系统页开完
        // 「后台弹出界面」，把他带回本应用（见 [maybeReturnAfterGrant]）。
        //
        // ⚠️ 必须在这条分支里做，不能挪到下面：`running` 为 false 时绝不能动屏幕，
        //    而"查一下权限状态、把本应用拉回前台"不属于动屏幕 ——
        //    恰恰是这一刻用户最需要的动作。放在下面的话永远不会执行到。
        if (!running) {
            maybeReturnAfterGrant(pkg)
            return
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now = SystemClock.uptimeMillis()
            if (now - lastContentEventMs < CONTENT_THROTTLE_MS) return
            lastContentEventMs = now
        }

        // 有新事件 → 立刻给 Runner 一次机会，不必干等它的重试间隔。
        // （"是否跑偏"由 Runner 用当前根节点自己判断，这里不做包名拦截 ——
        //   一个任务的路径天生跨包，按包名硬卡会把最后几步永远挡掉。）
        runner.onPageChanged()
    }

    /** 上次查询「后台弹出界面」状态的时刻，用于节流（见 [GRANT_CHECK_INTERVAL_MS]）。 */
    private var lastGrantCheckMs = 0L

    /**
     * 用户在系统页开完「后台弹出界面」，把本应用拉回前台。
     *
     * ## 为什么需要单独一条通路
     *
     * 原有的自动返回只挂在两个时机上：**服务连上**、**会话结束**。
     * 用户去开这个权限时**两者都不会发生** —— 无障碍服务一直连着（不会重连），
     * 也根本没有会话在跑。结果就是：权限开好了，界面停在系统设置页，
     * 本应用**一点反应都没有**（实测反馈正是如此）。
     *
     * ## 判据：只认状态翻转，且只认系统权限页
     *
     * - 只有 `未允许 → 已允许` 才算数（[AdSkipPermission.consumeBackgroundStartJustGranted]）——
     *   权限本来就开着、或用户只是正常打开本应用，都不会被拽前台；
     * - 只有事件来自系统设置 / 安全中心才去查
     *   （[AdSkipPermission.PERMISSION_PAGE_PACKAGES]）——
     *   用户在自己别的应用里时保证**零动作**，连一次查询都不做。
     *
     * 这不破坏"不点开始就绝不动屏幕"的铁律：拉回前台不是动屏幕，
     * 而且这里**不会**调用 `runner` 的任何方法。
     */
    private fun maybeReturnAfterGrant(pkg: String) {
        if (pkg !in AdSkipPermission.PERMISSION_PAGE_PACKAGES) return

        // ⚠️ 节流**必须有**（实测踩到）：下一行要查一次 AppOps（binder 调用），
        //    而本方法挂在**每个无障碍事件**上 —— 在系统设置里滚动列表时，
        //    实测**同一毫秒查了几十次**，日志被 `后台弹出界面 op=10021` 刷屏，
        //    主线程也被这些调用拖住。详见 [GRANT_CHECK_INTERVAL_MS]。
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs - lastGrantCheckMs < GRANT_CHECK_INTERVAL_MS) return
        lastGrantCheckMs = nowMs

        if (!AdSkipPermission.consumeBackgroundStartJustGranted(service)) return

        // 已经在前台就不用"返回"了（可能只是状态刷新事件）。
        if (service.rootInActiveWindow?.packageName?.toString() == service.packageName) return

        AdSkipRuntime.log("「后台弹出界面」已允许，返回中")
        bringAppToFront(RETURN_DELAY_MS)
    }

    // -------------------------------------------------------------------------
    // 任务队列推进
    // -------------------------------------------------------------------------

    private fun nextTask() {
        if (!running) return
        if (index >= queue.size) {
            running = false
            // 全部跑完 —— 撤掉前台服务。
            SkipAdAutomationService.stop(service)
            AdSkipRuntime.finishSession()
            bringAppToFront(RETURN_DELAY_MS)
            return
        }
        val task = queue[index]
        taskRetryCount = 0
        // 换一项 = 换一套页面，上一项的"卡点"对这里毫无意义，必须一起归零。
        lastRetryFailStep = -1
        AdSkipRuntime.setCurrentTask(index, queue.size, task)
        runner.start(task)
    }

    private fun onTaskProgress(task: AdTask, stepIndex: Int, step: AutomationStep) {
        AdSkipRuntime.setCurrentStep(step)
        AdSkipRuntime.log("  [${stepIndex + 1}/${task.steps.size}] ${step.label}")
    }

    private fun onTaskResult(task: AdTask, result: RunnerResult) {
        // ── 权限会话走独立收尾 ──
        //
        // 它**不写处理进度**：权限不是那份广告清单的一部分，记进去会让
        // 「继续处理（剩 N 项）」的 N 对不上。也不进结果列表（那是"关广告"的成绩单）。
        if (permissionMode) {
            finishPermissionSession(result)
            return
        }

        when (result) {
            is RunnerResult.Completed -> {
                AdSkipRuntime.addOutcome(task, success = true, detail = result.message)
                // 持久化"这一项已完成"：下次「继续处理」会直接跳过它。
                // 异步写，不阻塞主线程（理由见 progressScope 的 KDoc）。
                progressScope.launch {
                    SkipProgressRepository.markDone(service, task.id)
                }
                index++
                nextTask()
            }

            is RunnerResult.Failed -> {
                // ── 防呆：可恢复的失败（页面跑偏 / 异步结果没等到）→ 从第 1 步重跑 ──
                //
                // ⚠️ 但**同一处卡第二次就收手**。脚本声明的 [AutomationStep.AwaitFor.retryable]
                //    粒度很粗：它既盖住"等异步结果没等到"（重跑一次可能赶上），
                //    也盖住"页面结构不对"（重跑必然原地打转）。后者每多跑一遍，
                //    用户就要多看一遍前面所有步骤 + 同一段超时 —— 而成倍的空等
                //    换来的还是同一个结果。判据见 [lastRetryFailStep]。
                val stalledAgain = result.retryable && result.stepIndex == lastRetryFailStep
                if (stalledAgain) {
                    AdSkipRuntime.log(
                        "重跑后又卡在第 ${result.stepIndex + 1} 步「${result.stepLabel}」，" +
                            "重跑解决不了，不再重试",
                        handled = false,
                    )
                }
                if (result.retryable && !stalledAgain && taskRetryCount < MAX_TASK_RETRY) {
                    taskRetryCount++
                    lastRetryFailStep = result.stepIndex
                    // ⚠️ 必须清空点击去重记录，否则**重试时一步都点不动**：
                    //    上一轮已经点过的节点全会被 NodeClicker.shouldSkip 挡下，
                    //    而"点不动"往往正是上一轮失败的原因。
                    clicker.resetSession()
                    AdSkipRuntime.log(
                        "${result.reason}，重新开始这一项（第 $taskRetryCount 次）",
                    )
                    runner.start(task)
                    return
                }

                // ── 记失败，然后**继续下一项** ──
                //
                // ⚠️ 这里的行为**改过一次**，把原委写清楚，免得又被改回去：
                //
                //    旧逻辑：「一项走不通说明环境与预期不符，后面的项大概率同样
                //    走不通，继续跑只会反复弹设置页」→ 于是直接结束整个会话。
                //
                //    但清单里每一项**用的是不同的 App**（天气 / 日历 / 应用商店 /
                //    垃圾清理……），彼此没有因果关系：天气那页布局不匹配，
                //    完全不预示下载管理那一项会失败。旧逻辑的实际后果是
                //    **一项失败就再也不处理后面十几项**，用户得手动一项项点。
                //
                // 所以现在：如实记下这一项的失败（界面照旧显示红项 + 卡在第几步），
                // 然后 `nextTask()` 接着跑下一项。全部跑完时 `nextTask()`
                // 自己会收尾（finishSession + 回前台），不用在这里做。
                //
                // ⚠️ 这**不违背**"失败绝不跳过"那条红线：跳过的只是
                //    "这一项不再重试"，它在结果列表里**如实记为失败**，
                //    用户一眼能看到哪一项没做成、卡在第几步。
                //    红线禁止的是"**步骤层面**猜着往下点"。
                AdSkipRuntime.addOutcome(
                    task = task,
                    success = false,
                    detail = "第 ${result.stepIndex + 1} 步「${result.stepLabel}」${result.reason}",
                )
                AdSkipRuntime.log(
                    "${task.title}：失败，跳过这一项，继续下一项",
                    handled = false,
                )
                index++
                nextTask()
            }
        }
    }

    /**
     * 权限会话的收尾 —— 与 [onTaskResult] 的正常分支**刻意不同**。
     *
     * 不记 `outcomes`、不写 DataStore 进度：权限流程跑完不该在「处理清单」里
     * 留下任何痕迹（那里是"关广告"的成绩单）。结束状态也回到 `Idle` 而非
     * `Finished`，否则状态卡会报一句"成功 0 项，失败 0 项"。
     *
     * ⚠️ 失败时**不重试**：权限页面的失败基本都是"用户中途接管了屏幕"
     * 或"ROM 布局与预期不符"，再自动弹一次设置页只会干扰用户。
     * 日志里写清卡在哪一步，用户照着 `guide` 自己走一遍即可。
     */
    private fun finishPermissionSession(result: RunnerResult) {
        permissionMode = false
        running = false
        SkipAdAutomationService.stop(service)

        when (result) {
            is RunnerResult.Completed ->
                AdSkipRuntime.log("权限已自动获取完成")

            is RunnerResult.Failed ->
                AdSkipRuntime.log(
                    "自动获取权限未完成：第 ${result.stepIndex + 1} 步" +
                        "「${result.stepLabel}」${result.reason}",
                    handled = false,
                )
        }

        AdSkipRuntime.finishPermissionSession()
        bringAppToFront(RETURN_DELAY_MS)
    }

    // -------------------------------------------------------------------------

    /**
     * 把本应用拉回前台，让用户直接看到处理结果。
     *
     * ## 为什么"调用了"不等于"切回来了"
     *
     * 实测踩到的坑：`startActivity()` 和 `PendingIntent.send()` 在 HyperOS 上
     * 会被「后台弹出界面」限制**静默拦截** —— 不抛异常、也不返回失败，
     * 但界面纹丝不动。所以**不能**按"调用没报错"就记成功日志（那是骗人的）。
     *
     * 现在的做法是**发完就看结果**：隔一小段时间读一次前台包名，
     * 真的变成我们自己才算成功。
     *
     * ## 兜底：用返回键退出当前页面
     *
     * 如果拉不起来，就按 `GLOBAL_ACTION_BACK` 往回退。
     * 这条兜底之所以成立，是因为**整次处理是从本应用发起的** ——
     * 用户点「开始处理」时我们在前台，设置页是压在我们上面的。
     * 把设置页退干净之后，系统自然会把下面的我们露出来。
     *
     * @param delayMs 延迟多久执行。等开关动画播完再回去，用户能看清它确实被关了。
     */
    fun bringAppToFront(delayMs: Long = 0L) {
        // ⚠️ **会话正在跑时一律不执行。**
        //
        // 自动化的本质就是不停地跳页面，此刻"把本应用拉回前台"是灾难：
        // 拉起动作会盖住设置页；更糟的是返回键兜底 —— 它会把自动化**正要操作**的
        // 那个页面直接退掉。
        //
        // 实测现象：用户在主页点「自动获取」，脚本刚跳到「权限管理」页，
        // 界面闪一下又回到主页，然后第 3 步在主页上等超时。
        // 根因就是服务连接时的 `bringAppToFront()` 与自动化抢屏幕。
        //
        // 会话结束后的正常收尾不受影响：那时 `running` 已经被置回 false 了。
        if (running) return

        val action = Runnable { attemptBringToFront() }
        if (delayMs > 0L) {
            mainHandler.postDelayed(action, delayMs)
        } else {
            action.run()
        }
    }

    private fun attemptBringToFront() {
        service.bringAppToFront()
        mainHandler.postDelayed({ verifyForeground(attempt = 1) }, VERIFY_DELAY_MS)
    }

    /**
     * 校验前台是不是我们；不是就按一次返回键再校验。
     *
     * ⚠️ 按返回键**只允许在"当前前台属于本任务路径"时**做。
     * 用户完全可能中途切去微信、浏览器……那时无脑按返回键
     * 会把**他自己的应用**退掉 —— 一个"关广告"的工具去退别人的应用，
     * 是绝对不能接受的副作用。
     *
     * @param attempt 已经尝试了几次返回键。上限见 [MAX_BACK_ATTEMPTS]。
     */
    private fun verifyForeground(attempt: Int) {
        // 双保险：如果倒在"发出拉起请求"与"校验结果"这中间开跑了，
        // 上面那道闸门拦不住 —— 这里必须再拦一次，否则接下来的返回键
        // 会把自动化正要操作的页面退掉。
        if (running) return

        val foreground = service.rootInActiveWindow?.packageName?.toString()

        if (foreground == service.packageName) {
            // 区分是靠什么回来的 —— 这能直接告诉用户「后台弹出界面」权限到底需不需要开：
            // 第一轮就命中 = 直接拉起成功（权限已生效或系统没拦）；
            // 靠返回键才回来 = 拉起被拦了，开权限可以免掉这段兜底。
            AdSkipRuntime.log(
                if (attempt <= 1) "已切回本应用" else "已切回本应用（借助返回键）",
            )
            return
        }

        val giveUp = {
            AdSkipRuntime.log(
                "自动切回本应用被系统拦截，请手动返回。" +
                    "可在系统设置里允许本应用「后台弹出界面」以彻底解决",
            )
        }

        if (attempt > MAX_BACK_ATTEMPTS || foreground == null) {
            giveUp()
            return
        }

        // 安全闸门：只在"确定安全"的页面才退。
        // ① 本任务路径上的页面（allowedPackages）；
        // ② 系统权限相关页面（[AdSkipPermission.PERMISSION_PAGE_PACKAGES] ——
        //    系统设置、安全中心、权限控制器）—— 无障碍开关页、应用信息/权限管理都在这里。
        //    服务刚连上时 queue 还是空的（会话未开始），不加这组固定名单的话
        //    返回键兜底会被安全闸门直接拦死，一次都不会按（实测踩过）。
        // 除此之外（微信、浏览器…）一个都不碰 —— 用户自己的应用绝不退。
        val backSafePackages = queue
            .flatMapTo(mutableSetOf()) { it.allowedPackages } +
            AdSkipPermission.PERMISSION_PAGE_PACKAGES
        if (foreground !in backSafePackages) {
            giveUp()
            return
        }

        // 退一步。处理是从本应用发起的，退完上面的页面就会露出我们。
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        mainHandler.postDelayed({ verifyForeground(attempt + 1) }, VERIFY_DELAY_MS)
    }

    fun onInterrupt() = Unit

    /**
     * 服务被系统解绑时的收尾。
     *
     * ⚠️ 必须在 `running` 时把会话也结束掉：服务可能在**处理中途**被解绑
     * （用户关掉无障碍开关、系统回收进程），如果只管引擎，
     * 界面上的 `SkipSession` 会**永远停在 Running** —— 用户看到的是
     * "一直显示处理中"，而实际上引擎早就没了，也不会再有任何进度。
     */
    fun dispose() {
        val wasRunning = running
        mainHandler.removeCallbacksAndMessages(null)
        progressScope.cancel()
        runner.cancel()
        // 服务被解绑时可能正跑着 —— 一并撤掉前台服务，
        // 否则会话已经结束，通知却还挂在那里。
        SkipAdAutomationService.stop(service)
        running = false
        installWatching = false
        queue = emptyList()
        index = 0
        taskRetryCount = 0
        lastContentEventMs = 0L
        clicker.resetSession()

        if (wasRunning) {
            AdSkipRuntime.finishSession(stopped = true)
        }
    }
}
