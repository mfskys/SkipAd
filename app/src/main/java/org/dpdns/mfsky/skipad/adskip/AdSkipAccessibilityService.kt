package org.dpdns.mfsky.skipad.adskip

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务 —— **很薄的一层**，只做转发与生命周期管理。
 *
 * 全部判断逻辑在 [AdSkipRuleEngine]。这个类刻意不写业务代码，
 * 因为无障碍服务的实例生命周期由系统控制（可能被反复连接/断开），
 * 把状态放在这里容易出微妙的时序问题。
 *
 * ## 服务连上 ≠ 开始工作
 *
 * ⚠️ 这是本应用的**核心语义**：服务连上之后什么都不做，
 * 必须等用户在主页点「开始处理」，引擎才会去动屏幕。
 * 因为关掉的内置广告开关是**持久设置**，一次跑完永久生效，
 * 没必要常驻监视 —— 那只会耗电、并增加误点的机会。
 *
 * ## 为什么用静态实例
 *
 * 界面（主页按钮）需要发起/停止会话，但无障碍服务不是界面创建的、
 * 也没有绑定（bind）关系，拿不到引用。所以用一个静态实例做桥 ——
 * 无障碍服务与应用 UI 在**同一个进程**里，这个引用是安全的。
 * 连接时赋值、断开时清空，不会泄漏。
 */
class AdSkipAccessibilityService : AccessibilityService() {

    companion object {
        /**
         * 当前已连接的服务实例；null 表示服务未连接。
         *
         * 只允许界面读取它来发起/停止会话，**不要**拿它做别的判断 ——
         * "无障碍是否真的开启"请用 [AdSkipPermission.isEnabled]，
         * 那个查的是系统设置，比这个引用更可信。
         */
        @Volatile
        var instance: AdSkipAccessibilityService? = null
            private set

        /**
         * 界面调：开始一次处理会话。
         *
         * ⚠️ 刻意做成 companion 方法，而不是让界面写 `instance?.startSession()` ——
         * 后者在服务没连上时是**静默失败**：用户点了按钮什么都不发生，
         * 界面上也不会有任何提示，只能对着屏幕干瞪眼。
         * 这里至少在日志区留下一条能看懂的原因。
         */
        fun startSession() {
            val service = instance
            if (service == null) {
                AdSkipRuntime.log("无法开始：无障碍未开启")
                return
            }
            service.engine?.startSession()
        }

        /** 界面调：中止当前会话。 */
        fun stopSession() {
            instance?.engine?.stopSession()
        }

        /**
         * 界面调：只处理**清单里的某一项**（用户在主页清单上点它触发的）。
         *
         * 与 [startSession] 完全同源 —— 同一套执行链路、同样的重试与防呆，
         * 差别只是队列里只有这一项，所以跑完照样会自动切回本应用。
         *
         * ⚠️ 同样必须由**用户在前台的点击**触发：流程第一步要启动系统设置页，
         * 而后台启动 Activity 会被 HyperOS 静默丢掉（见 [startPermissionSession]）。
         */
        fun startSingleTask(taskId: String) {
            val service = instance
            if (service == null) {
                AdSkipRuntime.log("无法开始：无障碍未开启")
                return
            }
            service.engine?.startSingleTask(taskId)
        }

        /**
         * 界面调：自动获取权限（后台弹出界面 / 显示悬浮窗 / 安装未知应用）。
         *
         * ⚠️ 必须由**用户在前台的点击**触发：整条流程的第一步要启动一个系统页面，
         * 而 Android 10 起禁止后台应用启动 Activity —— 应用在后台时那一步会被
         * HyperOS **静默丢掉**（不报错、也不生效）。这与"无障碍服务刚连上
         * 就自动去授权"那套失败的原因完全一样，见 [onServiceConnected] 的说明。
         */
        fun startPermissionSession() {
            val service = instance
            if (service == null) {
                AdSkipRuntime.log("无法获取权限：无障碍未开启")
                return
            }
            service.engine?.startPermissionSession()
        }

        /**
         * 界面调：跳到「安装未知应用」页，**在那边挂一条提示**，用户自己打开开关后
         * 自动切回本应用。
         *
         * ## 为什么这一项不是"自动点击"
         *
         * 那一页的开关**不在无障碍树里** —— ROM 把装载它的列表项标成了
         * `noHideDescendants`，`flagIncludeNotImportantViews` 也穿透不了，
         * 读不到自然点不到（实测诊断快照见 `PermissionTasks` 的类文档）。
         *
         * 所以退一步：把用户**送到正确的位置**、把**该点哪里说清楚**、
         * 他点完再**自动接回来** —— 用户只需要一次点击，剩下的都不用管。
         *
         * ⚠️ 与 [startPermissionSession] 一样，必须由**用户在前台的点击**触发：
         * 它要启动一个系统页面，而后台启动会被 HyperOS 静默丢掉。
         */
        fun startInstallGrantWatch() {
            val service = instance
            if (service == null) {
                AdSkipRuntime.log("无法跳转：无障碍未开启")
                return
            }
            service.engine?.startInstallGrantWatch()
        }
    }

    private var engine: AdSkipRuleEngine? = null
    private var clicker: NodeClicker? = null

    /** 连上之后是否已经探过一次屏幕（见 [probeScreen]）。每次重连都重置。 */
    private var probedOnFirstEvent = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        val newClicker = NodeClicker(this)
        clicker = newClicker
        engine = AdSkipRuleEngine(this, newClicker)
        instance = this
        // 日志落盘：这个进程很可能是被无障碍服务拉起来的（用户直接从系统设置拨开关、
        // 或 MIUI 重连服务），此时界面从没打开过 —— 不在这里 attach 的话，
        // 那次运行的日志根本不会留档，用户事后点「复制」什么也拿不到。
        AdSkipRuntime.attach(this)
        AdSkipRuntime.log("无障碍已连接")

        // ⚠️ 探一次"现在屏幕上是什么"，而且要打在**下面那段"切回本应用"之前** ——
        //    这是唯一能在"用户面前就是那个页面"的瞬间取证的时机。
        //    排查「读不到界面」全靠它（见 [probeScreen] 与 PROJECT.md 坑 #43）。
        probedOnFirstEvent = false
        probeScreen("刚连上")

        // 用户刚在系统设置里拨完开关，此刻屏幕上是设置页 —— 把他送回本应用主页。
        //
        // 走引擎那套「发送 → 验证 → 用返回键兜底」，而不是直接调扩展：
        // 在 HyperOS 上后台拉起 Activity 会被静默拦截，直接调用的结果
        // 是"什么都没发生"，而且连日志都不会有。
        //
        // ⚠️⚠️ 但**必须先确认前台确实是系统设置**，绝不能无条件切回。
        //
        // 实测踩到的严重问题：MIUI 会因省电策略**反复解绑再重连**无障碍服务
        // （日志里 1 分钟内重连了 5 次），而每次重连都会执行到这里 ——
        // 于是用户正在用日历、正在看天气时，屏幕会**突然被拽回 SkipAd**，
        // 根本没法正常用手机。
        //
        // 判据：只有前台是系统设置 / 权限相关页面时才切回。
        // 前台读不到时（刚连上、根节点还没就绪）也保守地不动作 ——
        // 那种情况多半是服务在后台重启，用户根本不在设置页里。
        val foreground = rootInActiveWindow?.packageName?.toString()
        if (foreground != null && foreground in AdSkipPermission.PERMISSION_PAGE_PACKAGES) {
            engine?.bringAppToFront()
        }

        // ── 关于「后台弹出界面」的自动授权 ──
        //
        // 实测结论（日志为证）：服务连上时 App 在后台，此刻发起的 startActivity
        // 会被 HyperOS 静默拦截 —— 连"打开应用信息页"都到不了，授权任务必然
        // 卡在第 2 步。这是 Android 的后台启动限制，无障碍豁免不覆盖它。
        //
        // 所以自动授权改在**主页状态卡**上触发：用户被 BACK 兜底带回应用后
        // 点一下按钮，这次点击属于前台交互，从它发起的页面跳转就能成功。
        // 之后进权限管理、点后台弹出界面、选始终允许，全靠无障碍完成，
        // 不再需要启动任何页面。

        // ── 「按键过滤」（音量键紧急停止）按需恢复 ──
        //
        // 该 flag **不在** config.xml 里（原因见 [setKeyFilterEnabled]），只在会话
        // 期间动态打开。而服务一旦被系统重绑，`serviceInfo` 会回到配置文件那份
        // （不含它）—— 所以这里按当前会话状态补回来，否则"会话中途被重连"
        // 会让刹车失灵。
        setKeyFilterEnabled(AdSkipRuntime.session.value == SkipSession.Running)
    }

    /**
     * 运行时开关**按键过滤** —— 「音量键紧急停止」依赖它，而它是个**按需能力**。
     *
     * ## 为什么绝不能把它写进 config.xml
     *
     * `flagRequestFilterKeyEvents` 若是静态声明，服务从连上那一刻起就**永远**
     * 接收所有按键，哪怕根本没在跑任务。代价远不止"多跑一个回调" ——
     * 系统在**每次按键**上都要把事件**同步投递**到本服务进程，而本应用是
     * "用完就扔在后台"的工具，进程很快会被 MIUI 判为低优先级 / 可冻结：
     * 于是用户**平时**按音量键时，系统得先把我们唤醒、等我们回一句"不处理"，
     * 那一下就是肉眼可见的卡顿。
     *
     * 实测反馈原话："软件使用过后…没有删除…一直监听…加音量减音量就会很卡，
     * 关闭（无障碍）了就不卡了。" —— 关掉服务立刻不卡，正说明瓶颈在
     * "系统必须把每个按键同步投递给我们"这件事上，而不在我们的回调体里。
     *
     * 而这个能力**只在会话期间有意义**（没在跑任务时，音量键没有任何要刹的车），
     * 所以由 [AdSkipRuleEngine] 的 `running` 在会话起止时开关它。
     *
     * ## ⚠️ 改 `serviceInfo` 未必立刻生效
     *
     * 平台文档原话：*the info will be picked up after the system has bound to this
     * service* —— 某些 ROM 上要等下次重绑才认。所以 [onServiceConnected] 里会
     * 按会话状态再应用一次；两条路一起兜住。
     */
    internal fun setKeyFilterEnabled(enabled: Boolean) {
        val info = serviceInfo ?: return
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        val current = info.flags and flag != 0
        if (current == enabled) return

        info.flags = if (enabled) info.flags or flag else info.flags and flag.inv()
        serviceInfo = info
        // 只打 logcat：这是实现细节，不该占用界面上的「处理记录」。
        android.util.Log.d("SkipAd", "按键过滤（音量键紧急停止）→ ${if (enabled) "开" else "关"}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 连上之后**第一个**事件时再探一次：那一刻界面已经稳定，
        // 比"刚连上"那一瞬更能说明问题（见 [probeScreen]）。
        if (!probedOnFirstEvent) {
            probedOnFirstEvent = true
            probeScreen("连上后首个事件")
        }
        engine?.onAccessibilityEvent(event)
    }

    override fun onInterrupt() {
        engine?.onInterrupt()
    }

    /**
     * **音量键 = 紧急停止**。
     *
     * ## 为什么需要这一层
     *
     * 自动化跑起来之后本应用在后台，用户想喊停得先切回本应用再点「停止」——
     * 而那一刻脚本正在别的应用里点来点去，把界面找回来非常别扭。
     * 音量键是**任何界面下都摸得到**的那个"刹车"。
     *
     * ## ⚠️ 必须返回 false
     *
     * 返回 `false` = **不消费**这个事件，音量照常调节。我们只是"顺便看一眼"。
     * 返回 true 的话用户调音量就不生效了 —— 那只是我们要的"副作用"，
     * 不该把按键本身吞掉。
     *
     * ## ⚠️ 代价：这是一个敏感能力
     *
     * 声明 `canRequestFilterKeyEvents` 之后，服务**能收到所有按键**
     *（包括输入法里敲的那些），系统无障碍设置页也会多一句"可以观察按键"的提示。
     * 所以这里刻意收得很紧：
     *
     * - **只认音量键**，其它按键在第一行就 return，不留任何记录；
     * - **不记录、不上报**任何按键内容 —— 它只有"触发停止"这一个用途。
     *
     * ## 幂等：不需要时间窗防抖
     *
     * 长按音量键会连发 ACTION_DOWN，但 [AdSkipRuleEngine.emergencyStop] 在
     * "会话已经停了"时返回 false，所以提示只会出现一次。
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null || event.action != KeyEvent.ACTION_DOWN) return false
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }
        // 只有真的刹住了一次会话才留日志 —— 平时调音量完全不留痕。
        if (engine?.emergencyStop() == true) {
            AdSkipRuntime.log("（音量键触发，已停止本次处理）")
        }
        return false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        engine?.dispose()
        engine = null
        clicker = null
        instance = null
        AdSkipRuntime.log("无障碍服务已断开")
        return super.onUnbind(intent)
    }

    /**
     * 报一次"屏幕上现在是什么"：前台包名 + **全部窗口**（含那些"根读不到"的）。
     *
     * ## 为什么值得专门留一条日志
     *
     * 排查「等待通知页」失败时，日志里只有一串"读不到界面（根节点为空）"——
     * 完全看不出是 **①服务没连上**、**②窗口在但读不到**、还是 **③页面没切过来**，
     * 而这三者的处理方式完全不同（①该重连、②是系统级限制、③该继续等）。
     *
     * ⚠️ 实测到的真实原因（Android 14+）：**没有声明 `android:isAccessibilityTool`
     *    的服务读不到"无障碍数据敏感"的界面**，「应用信息 → 通知」那一页正是这种。
     *    现象是：页面明明开着（`Displayed +168ms`）、`uiautomator dump` 读得到
     *    「允许通知」，而我们这边 `rootInActiveWindow` **恒为 null**。
     *    这条日志把那种情况一眼照出来：窗口**在**、`根=读不到`。
     *
     * ⚠️ 只观测，不动作：读不到时怎么处理由调用方决定。
     */
    @Suppress("DEPRECATION")
    private fun probeScreen(tag: String) {
        val windows = windows
        val detail = windows.joinToString("") { w ->
            val root = w?.root
            // 窗口标题可能带文档名 / 聊天对象名，只留前缀（够判断"是不是那个页面"即可）。
            val title = w?.title?.toString()?.take(24) ?: "-"
            " · id=${w?.id} type=${w?.type} title=$title " +
                (if (root == null) "根=读不到" else "根=${root.packageName}")
        }
        AdSkipRuntime.log(
            "  ↳ [$tag] 前台=${rootInActiveWindow?.packageName ?: "读不到（根节点为空）"}" +
                "，窗口 ${windows.size} 个$detail",
        )
    }

}

/**
 * 无障碍服务状态查询工具。
 *
 * ⚠️ **必须查系统设置而不是只查服务实例**：用户在系统里把开关关掉时，
 * 进程可能还活着（`onUnbind` 不一定立即回调），只信实例会显示错误状态。
 * 系统设置里的 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 才是
 * 唯一可靠的状态来源。
 */
object AdSkipPermission {

    /**
     * 本服务在系统无障碍列表里的完整组件名。
     *
     * ⚠️ **包名必须取 `context.packageName`（= applicationId），不能写死**：
     *    debug 变体带 `.debug` 后缀（见 `app/build.gradle.kts` 的 `applicationIdSuffix`），
     *    写死的话 debug 包会拿 release 包的组件名去比对 —— 恒不相等，
     *    界面永远显示"无障碍未开启"，Shizuku 那条直达还会去开**另一个包**的服务。
     * ⚠️ 类名可以写死：它被 manifest 引用 + `-keepnames` 保着（见 `proguard-rules.pro` ③），
     *    不会随混淆变化。
     */
    fun serviceComponent(context: Context): String =
        "${context.packageName}/$SERVICE_CLASS"

    /** 服务类全名，与 `AndroidManifest.xml` 里 `<service android:name>` 的展开结果一致。 */
    private const val SERVICE_CLASS =
        "org.dpdns.mfsky.skipad.adskip.AdSkipAccessibilityService"

    /** 无障碍服务当前是否**真的**处于启用状态。 */
    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        while (splitter.hasNext()) {
            if (componentMatches(splitter.next(), context)) return true
        }
        return false
    }

    /**
     * 系统列表里的某一项是不是本应用的无障碍服务 —— **两种写法都要认**。
     *
     * ⚠️ 同一个组件，系统会在两种写法之间改写，而且存的恰恰是**缩写式**：
     * ```text
     * org.dpdns.mfsky.skipad/org.dpdns.mfsky.skipad.adskip.AdSkipAccessibilityService  ← 展开式（dumpsys 里看到的是它）
     * org.dpdns.mfsky.skipad/.adskip.AdSkipAccessibilityService                         ← 缩写式（settings get 拿到的是它）
     * ```
     * 只认一种的后果**不是"多打一条日志"**：首页会显示"无障碍未开启"、
     * **「开始处理」按钮根本不出现** —— 而服务其实好好跑着，用户只会以为应用坏了。
     */
    internal fun componentMatches(stored: String, context: Context): Boolean = expandComponent(stored)
        .equals(expandComponent(serviceComponent(context)), ignoreCase = true)

    /** 把 `包名/.类名`（缩写式）展开成 `包名/包名.类名`；已经是展开式就原样返回。 */
    private fun expandComponent(value: String): String {
        val pkg = value.substringBefore('/', "")
        val cls = value.substringAfter('/', "")
        return when {
            pkg.isEmpty() || cls.isEmpty() -> value
            cls.startsWith(".") -> "$pkg/$pkg$cls"
            // 还有 `包名/类名` 这种"类名不带包"的写法 —— 它是相对**包名**的
            !cls.contains('.') -> "$pkg/$pkg.$cls"
            else -> value
        }
    }

    /** 跳系统无障碍设置页。 */
    fun openSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * 「后台弹出界面」当前是否已允许。
     *
     * ## 怎么查到的
     *
     * 这是 HyperOS 的私有权限，Android 公开的 op 常量里没有它 ——
     * 但 `adb shell appops get <pkg>` 能看到它以 `MIUIOP(10021)` 的形式
     * 挂在系统的 AppOps 服务里。AppOps 是标准服务，MIUI 只是往里扩展了
     * 编号 —— 所以在 App 内用 [AppOpsManager] 直接传**编号 10021** 就能查，
     * 不需要反射、也不依赖 MIUI SDK。
     *
     * @return true = 已允许；false = 未允许/查询失败（保守按未允许处理）。
     */
    fun isBackgroundStartAllowed(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val uid = context.applicationInfo.uid
        val pkg = context.packageName

        // MIUI 扩展 op（后台弹出界面）没有公开常量名，而新版编译 SDK 上
        // checkOpNoThrow 的 int 重载解析不到（Kotlin 只挑到 String 版）——
        // 反射是唯一不依赖编译期签名的方式，任何 ROM 上都能尝试。
        val mode = AppOpsManager::class.java
            .getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            .invoke(appOps, 10021, uid, pkg) as Int

        android.util.Log.d("SkipAd", "后台弹出界面 op=10021 mode=$mode")
        mode == AppOpsManager.MODE_ALLOWED
    }.onFailure {
        android.util.Log.w("SkipAd", "查询后台弹出界面状态失败", it)
    }.getOrDefault(false)

    /**
     * 「读取应用列表」是否可用。
     *
     * ## ⚠️ 查的**不是** MIUI 页面上那个同名开关
     *
     * MIUI 的「其他权限」页里确实有一项叫「获取应用列表」，但它**不暴露任何 appop**。
     * 实测把 `10008 / 10017 / 10020 / 10022 / 10045 / 10053` 逐个设为 `allow`，
     * 那一行的状态**始终显示"拒绝"**（本机原值也是"拒绝"）——
     * 也就是说这个开关在 AppOps 里根本读不到。
     *
     * 更关键的是：**在它显示"拒绝"的情况下，本应用读取别的包完全正常**。
     * 合起来只能得出一个结论 —— Android 11 引入 `QUERY_ALL_PACKAGES` 之后，
     * 真正的闸门已经换成系统权限，MIUI 那个开关多半是旧版本遗留的展示项。
     *
     * 所以这里判断**真正的闸门**：`QUERY_ALL_PACKAGES`。
     *
     * ## 为什么这个判据比"读 MIUI 开关"更好
     *
     * 它**跨 ROM 一致**：MIUI 上有那个开关、别的 ROM 上没有，
     * 但两种情况下这一行的结论都是对的（有权限 = 能读 = 已就绪）。
     * 而如果去读那个读不到的开关，非 MIUI 机器上会永远显示"未允许"。
     *
     * ## 为什么它值得进权限清单
     *
     * 本应用有一处核心判断依赖它：`AdTask.requiredPackages` 从任务步骤里推出的
     * 那些包还在不在（天气 / 日历 / 画报 / 应用商店都可能被用户卸载），
     * 见 [isPackageUsable]。
     *
     * 少了它，`getApplicationInfo` 会对所有没在清单里声明过的包抛
     * `NameNotFoundException`，与"真的没装"**完全无法区分** ——
     * 那些任务会被误判成"未安装"而静默跳过（实测踩过一次，主页显示 3/3）。
     */
    fun isAppListAllowed(context: Context): Boolean = runCatching {
        context.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 可能出现「后台弹出界面」开关的系统页面所属的包。
     *
     * 只在来自这些包的事件上才去查 AppOps —— 窗口事件本身很频繁，
     * 每条都查一次（还是反射调用）没有必要；更重要的是，
     * 用户在自己别的应用里时，本应用**不该有任何动作**。
     */
    val PERMISSION_PAGE_PACKAGES = setOf(
        "com.android.settings",             // AOSP 设置页 / 应用信息
        "com.miui.securitycenter",          // HyperOS 安全中心（权限管理在这里）
        "com.android.permissioncontroller", // AOSP 权限控制器
    )

    /**
     * 上一次查到的「后台弹出界面」状态，用来识别**状态翻转**。
     *
     * `null` = 还没查过。首次查询只用来建立基准，**不算"刚授权"** ——
     * 否则应用一启动、而权限本来就开着的情况下会被误判成刚刚授权。
     */
    private var lastBackgroundStartAllowed: Boolean? = null

    /**
     * 查询「后台弹出界面」是否**刚刚**被允许（未允许 → 已允许）。
     *
     * 这是"用户在系统页开完权限后，把他自动带回本应用"的判据。
     * 每调用一次就刷新缓存，所以同一次翻转只会被消费一次。
     *
     * ⚠️ 只认翻转、不认"当前是允许"：权限本来就开着时不会触发，
     * 否则每次用户打开本应用都可能被拽一下前台。
     */
    @Synchronized
    fun consumeBackgroundStartJustGranted(context: Context): Boolean {
        val now = isBackgroundStartAllowed(context)
        val before = lastBackgroundStartAllowed
        lastBackgroundStartAllowed = now
        return before == false && now
    }

    /**
     * 跳系统「应用信息」页 —— 用户从这里点「权限管理」去开「后台弹出界面」。
     *
     * ## 为什么需要这个权限
     *
     * Android 10 起限制"后台应用启动 Activity"，HyperOS 上另有一个独立的
     * 「后台弹出界面」开关（默认关闭）。不开的话，处理结束时想把用户送回本应用
     * 会被**静默拦截**：`startActivity` / `PendingIntent` 都不报错、也不生效，
     * 界面停在系统设置页不动。
     *
     * ## ⚠️ 不要再试 MIUI 的 `APP_PERM_EDITOR`
     *
     * 曾经为了"少点两步"，先试 `miui.intent.action.APP_PERM_EDITOR`（想直达权限页），
     * 用 `runCatching { startActivity(...) }.isSuccess` 判断成功与否。
     * 实测在 HyperOS 上它是**假成功**：不抛异常、返回正常，界面却什么都不打开
     * （焦点落到桌面）。于是永远走不到兜底分支 —— 用户看到的就是"点了没反应"。
     *
     * **教训**："没抛异常"不等于"页面真的打开了"。这里改为只用实测有效的
     * 系统标准 action，用户多点一步「权限管理」，但**一定到得了**。
     */
    fun openAppPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * 打开**悬浮窗权限**授权页（「显示在其他应用上层」）。
     *
     * ## 为什么需要它
     *
     * 自动化期间本应用会长时间待在后台（它自己去打开了系统设置 / 下载管理），
     * 厂商 ROM 会把这样的进程判为可冻结、连整个 uid 一起冻住 ——
     * 那时**所有线程停止调度**，任务就卡死在半路
     * （实测证据：`/sys/fs/cgroup/apps/uid_xxx/cgroup.freeze = 1`）。
     *
     * 对策是挂一个 **1×1 像素的透明悬浮窗**（见 [SkipAdAutomationService]）：
     * 厂商的冻结判据基本是"这个应用还有没有可见窗口"，overlay 正好满足。
     * 而它需要用户手动授权，所以这里给一个直达入口。
     *
     * ⚠️ 带上包名，直接落到**本应用**那一页；少数 ROM 不认这种写法，
     * 回退到不带包名的权限列表页。全程 `runCatching`：跳不过去也不该崩。
     */
    fun openOverlaySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** 悬浮窗权限是否已授予。 */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 「安装未知应用」是否已允许。
     *
     * ## 为什么本应用需要它
     *
     * 「应用包管理组件」的设置页 `com.android.packageinstaller.SettingsActivity`
     * **没有导出**，外部无法直接启动（实测连 `adb shell` 都被拒）。它**唯一的入口**
     * 是安装界面右上角的「设置」按钮 —— 所以那个任务必须先"带着一个 APK
     * 唤起安装界面"（见 `AutomationStep.SELF_APK_DATA`）。
     *
     * 而"唤起安装界面"这件事，系统会检查**发起方**有没有「安装未知应用」权限：
     * 没有的话要么弹授权页、要么直接拒绝。这与 shell 无关 ——
     * 前提测试时授权的是 shell，不是本应用。
     *
     * ⚠️ 只有需要用到安装界面的那一个任务依赖它，**不是"开始处理的前提"** ——
     *    所以它出现在设置页，而不进主页的"还需要开启"清单。
     */
    fun canRequestPackageInstalls(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /**
     * 打开「安装未知应用」授权页（直接落到**本应用**那一页）。
     *
     * ⚠️ 带上包名；个别 ROM 不认这种写法时回退到不带包名的权限列表页。
     */
    fun openInstallUnknownAppsSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

}
