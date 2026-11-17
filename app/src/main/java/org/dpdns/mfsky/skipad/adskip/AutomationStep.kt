package org.dpdns.mfsky.skipad.adskip

/**
 * 自动化步骤。
 *
 * 一个任务 = 一串按顺序执行的步骤，每一步都必须真正到位才进入下一步
 * （页面加载有延迟，抢跑会点错位置）。执行器见 [AutomationRunner]。
 *
 * 匹配条件统一是 `List<Match>`：**任一命中即算命中**，用来覆盖不同 ROM 的布局差异。
 * 每个步骤另提供接收单个 `Match` 的构造，`Click(Match(...))` 与 `Click(listOf(...))` 都合法。
 *
 * 用 sealed interface 而不是 enum + 可空字段：每步所需数据完全不同，
 * 类型系统能保证构造时不会漏填匹配条件。
 */
sealed interface AutomationStep {

    /** 给界面/日志看的一行说明。 */
    val label: String

    /**
     * 启动页面：`action`（隐式）/ `component`（显式）/ `appPackage`（等价点桌面图标）三选一。
     *
     * - `action`：系统标准页面，最稳，优先用。
     * - `component`：目标页没有 intent-filter 时的唯一办法。
     *   ⚠️ 启动前必须在真机确认 `exported=true`，否则抛 `SecurityException`、表现为"点了没反应"。
     * - `appPackage`：只回"应用主页"时用它（配合 `clearTask`）。
     *
     * @param clearTask 默认 `true`：系统设置是单 Activity 架构，不清任务栈会停在旧子页。
     *   跨应用（别人的任务栈）设 `false`；但要确定回到主页时必须 `appPackage` + `true`。
     */
    data class Launch(
        /** 隐式 Intent 的 action。与 [component] 二选一。 */
        val action: String? = null,
        /** 用"打开应用"的方式启动（`ACTION_MAIN` + `CATEGORY_LAUNCHER` + 包名）。 */
        val appPackage: String? = null,
        /** 显式组件 `包名/类名`；与 [action] 同时给出时以它为准。 */
        val component: String? = null,
        /** Intent data，如 `package:org.xxx`；特殊值见 [SELF_APK_DATA]。 */
        val data: String? = null,
        /** MIME 类型，仅唤起安装界面时需要 `application/vnd.android.package-archive`。 */
        val mimeType: String? = null,
        /** 是否带 `FLAG_ACTIVITY_CLEAR_TASK`。 */
        val clearTask: Boolean = true,
        override val label: String = "打开页面",
    ) : AutomationStep

    /**
     * 等待节点出现（带超时）。超时即判失败，**绝不跳过失败继续往下点**。
     *
     * @param retryable 超时后从头重跑整个任务；只用于"重跑大概率能成"的异步结果。
     * @param retryPreviousInput 等待期间重发上一步输入（比重跑整个任务轻）；
     *   只对紧跟 [Input] 的步骤有效。
     * @param optional 等到超时也不算失败，直接跳过。
     *   ⚠️ 打开后后续步骤必须自己兜住"页面不对"（见 [ToggleOffAll.guard]、[PressBack.guard]）。
     */
    data class AwaitFor(
        val matches: List<Match>,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val retryable: Boolean = false,
        val retryPreviousInput: Boolean = false,
        val optional: Boolean = false,
        override val label: String = "等待页面",
    ) : AutomationStep {
        constructor(
            match: Match,
            timeoutMs: Long = DEFAULT_TIMEOUT_MS,
            retryable: Boolean = false,
            retryPreviousInput: Boolean = false,
            optional: Boolean = false,
            label: String = "等待页面",
        ) : this(listOf(match), timeoutMs, retryable, retryPreviousInput, optional, label)
    }

    /**
     * 点击匹配到的节点。
     *
     * 执行走 [NodeClicker] 的三级回退（自身 `ACTION_CLICK` → 可点祖先 → 手势落中心），
     * 所以"文字节点不可点"无需单独处理。
     *
     * @param optional 没找到则跳过（用于清理可能存在的残留弹窗）。
     * @param useGesture 强制真实触摸。MIUI 安全中心的列表行对 `ACTION_CLICK`
     *   返回 true 但界面不动，这类页面必须用它。
     */
    data class Click(
        val matches: List<Match>,
        override val label: String = "点击",
        val optional: Boolean = false,
        val useGesture: Boolean = false,
    ) : AutomationStep {
        constructor(
            match: Match,
            label: String = "点击",
            optional: Boolean = false,
            useGesture: Boolean = false,
        ) : this(listOf(match), label, optional, useGesture)
    }

    /**
     * 向输入框写入文本（支持中文；`adb input text` 只支持 ASCII）。
     *
     * ⚠️ 节点必须 `isEditable`。MIUI 搜索框的查询挂在 IME 上：只写文本、
     * 不聚焦/不弹键盘时，框里有字但结果列表永远为空 —— 所以默认请求焦点，
     * 必要时再按一次搜索键。
     */
    data class Input(
        val matches: List<Match>,
        val text: String,
        /** 写入前请求焦点（默认开，别关）。 */
        val requestFocus: Boolean = true,
        /** 写入后按键盘搜索/回车键（`ACTION_IME_ENTER`）。 */
        val submitAfter: Boolean = false,
        override val label: String = "输入「$text」",
    ) : AutomationStep {
        constructor(
            match: Match,
            text: String,
            requestFocus: Boolean = true,
            submitAfter: Boolean = false,
            label: String = "输入「$text」",
        ) : this(listOf(match), text, requestFocus, submitAfter, label)
    }

    /**
     * 关闭开关 —— 任务的终点。
     *
     * 与 [Click] 的差别：**幂等**（已经是关的不点击、直接成功）＋ **验证**
     * （读回 `isChecked`；`performAction` 返回 true 不代表真的切换）。
     *
     * @param confirm 点击后可能弹出的确认框里的"确定"按钮；没有则传 `null`。
     * @param optional 找不到则跳过；只在确认过"某些 ROM 确实没有这一项"时才用。
     */
    data class ToggleOff(
        val matches: List<Match>,
        val confirm: Match? = null,
        val optional: Boolean = false,
        override val label: String = "关闭开关",
    ) : AutomationStep {
        constructor(
            match: Match,
            confirm: Match? = null,
            optional: Boolean = false,
            label: String = "关闭开关",
        ) : this(listOf(match), confirm, optional, label)
    }

    /**
     * 打开开关 —— [ToggleOff] 的镜像，同样幂等 + 验证。
     *
     * ⚠️ 不能用 [Click] 顶替：开关是翻转语义，重复执行会把刚打开的又关回去。
     */
    data class ToggleOn(
        val matches: List<Match>,
        override val label: String = "开启开关",
    ) : AutomationStep {
        constructor(match: Match, label: String = "开启开关") : this(listOf(match), label)
    }

    /**
     * 关掉本页所有开关（边滚边关）。
     *
     * 用于"一屏放不下的一长串推荐位开关"页面。循环：关最靠上的一个 → 本屏没有 →
     * 滚一屏 → 重复。一次只关一个、每轮重扫（关掉后页面可能重排，预收集的节点会失效）。
     *
     * @param exclude **不动的开关**（按所在行标题匹配）。页面上的总开关必须排除，
     *   否则会把用户刚关好的项恢复默认值 —— 不写就是做错事。
     * @param maxSwipes 最多滚几次，防止无限流页面一直关下去。
     * @param guard **守卫**：页面上出现它才动手。防止前面步骤走错页时"在上一页照关不误"；
     *   应配"这一页独有"的条目名。
     */
    data class ToggleOffAll(
        val exclude: List<Match> = emptyList(),
        val maxSwipes: Int = 12,
        val guard: Match? = null,
        override val label: String = "关闭全部开关",
    ) : AutomationStep

    /**
     * 滚动查找：每轮先看目标在不在，不在才往下滚一格。
     *
     * 不依赖页面长度：一屏放得下就一次不滚，长列表会滚到底。
     *
     * @param maxSwipes 上限，防止目标不在本页时无限滚动。
     */
    data class ScrollTo(
        val matches: List<Match>,
        val maxSwipes: Int = 6,
        override val label: String = "滚动查找",
    ) : AutomationStep {
        constructor(
            match: Match,
            maxSwipes: Int = 6,
            label: String = "滚动查找",
        ) : this(listOf(match), maxSwipes, label)
    }

    /**
     * 按一次系统返回键。
     *
     * 只用于"刚展开过覆盖层、现在要收起"（如 MIUI 权限选项列表选完后不自动收起）。
     * ⚠️ **不要**当通用返回用：在不确定的页面上按返回会把用户的应用退掉。
     *
     * @param guard 页面上出现它才按；防止"没进到那一层"时退过头。
     */
    data class PressBack(
        val guard: Match? = null,
        override val label: String = "按返回键",
    ) : AutomationStep

    companion object {
        /** [AwaitFor] 默认超时：覆盖常见页面跳转与列表填充。 */
        const val DEFAULT_TIMEOUT_MS = 4_000L

        /** [Launch.data] 特殊值：本应用自己的 APK（由引擎现取现转）。 */
        const val SELF_APK_DATA = "self-apk"

        /** 启动参数里的占位符：引擎启动时替换为当前 applicationId。 */
        const val SELF_PACKAGE = "%SELF%"
    }
}
