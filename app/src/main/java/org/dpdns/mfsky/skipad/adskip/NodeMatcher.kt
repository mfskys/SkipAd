package org.dpdns.mfsky.skipad.adskip

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 一次命中的结果。
 *
 * @param node **最终要操作的节点**（已经根据 [MatchTarget] 解析过，不是锚点本身）。
 * @param description 命中描述，只用于日志。
 * @param requireChecked 该命中是否要求"开关处于开启状态才动手"。
 *   为 true 时调用方**必须**先检查 [AccessibilityNodeInfo.isChecked]。
 */
internal data class NodeHit(
    val node: AccessibilityNodeInfo,
    val description: String,
    val requireChecked: Boolean,
    /**
     * 手势落点用节点内的**相对位置**而不是中心，见 [Match.clickFraction]。
     * 为 null 表示"就是节点中心"（绝大多数情况）。
     */
    val clickFraction: Pair<Float, Float>?,
    /**
     * 命中的是候选列表里的**第几个**条件（从 1 开始）。
     *
     * 日志里带上它，排查"为什么点错了"时能直接定位到是哪条匹配规则放行的，
     * 不用对着 UI 猜。
     */
    val matchIndex: Int,
)

/**
 * 节点匹配器 —— 把"屏幕上的节点树"翻译成"要不要动手、动哪个"。
 *
 * ## 遍历约束（为什么必须有）
 *
 * 系统设置的节点树动辄上千个节点，每次内容变化都全树遍历会明显掉帧。所以：
 * - 深度优先遍历，节点数上限 [MAX_NODES]；
 * - 命中即返回，不做全量收集。
 *
 * ⚠️ 这里**刻意不做** `isVisibleToUser` 剪枝 —— 它会误杀真实存在的控件，
 * 详见 [findAnchor] 里的说明。
 *
 * ## 为什么不走 `findAccessibilityNodeInfosByText`
 *
 * 系统那个 API 只做"文本包含"一种匹配，拿不到 viewId / desc 组合条件，
 * 而且它返回**扁平列表、丢失层级** —— 没法做"同一行的开关"这类关系定位，
 * 而后者恰恰是本应用最主要的场景。
 */
internal object NodeMatcher {

    /**
     * 单次遍历的节点数上限。超过就放弃本轮（宁可漏一次，也不卡手机）。
     *
     * 去掉 `isVisibleToUser` 剪枝（见 [findAnchor] 的说明）后遍历量变大，
     * 所以这里比原先给得宽 —— 但这个上限本身必须留着：系统设置页的节点树
     * 能到几千个，而这是**每条事件**都会跑的路径，不设限会掉帧。
     */
    private const val MAX_NODES = 4000

    /** 找"同一行的开关"时向上查找的最大层数。 */
    private const val MAX_ROW_ANCESTOR_DEPTH = 5

    /** 开关类控件的类名特征。 */
    private val SWITCH_CLASS_HINTS = listOf("Switch", "CheckBox", "ToggleButton")

    /**
     * 按一条匹配条件查找目标（[AutomationRunner] 的每一步都用它）。
     *
     * @param root 当前窗口根节点（一般是 `rootInActiveWindow`）。
     * @return 命中的目标；没有命中返回 null。
     */
    fun findOne(root: AccessibilityNodeInfo, matches: List<Match>): NodeHit? {
        // 多候选：**按顺序**逐个尝试，任一命中即返回。
        // 顺序有意义 —— 脚本里把最精确的条件写在前面，模糊的兜底写在后面，
        // 这样在符合预期的 ROM 上不会被模糊条件抢先命中。
        for ((index, match) in matches.withIndex()) {
            val anchor = findAnchor(root, match) ?: continue
            resolveTarget(anchor, match)?.let { return it.copy(matchIndex = index + 1) }
        }
        return null
    }

    /** 深度优先找一个满足锚点条件的节点。 */
    private fun findAnchor(root: AccessibilityNodeInfo, match: Match): AccessibilityNodeInfo? {
        var visited = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (++visited > MAX_NODES) return null

            if (matchesAnchor(node, match)) return node

            // ⚠️ 这里**曾经**有一句 `if (!node.isVisibleToUser) continue`，理由是
            //    "不可见节点的子树通常也没有内容"。它会在 MIUI 的「安装未知应用」页
            //    上**误杀真要找的那个开关**：承载开关的那一行容器 `isVisibleToUser`
            //    报 false（RecyclerView 的预取 item / 自绘容器上很常见），整行被跳过。
            //
            //    现象极具误导性：屏幕上明明有开关、`uiautomator dump` 里也能看到
            //    `Switch id=switchWidget`，脚本却一直等到超时，而失败快照里只剩
            //    旁边那段说明文字 —— 看起来像"页面没加载出来"。
            //
            //    代价：遍历量变大，由 [MAX_NODES] 兜底；而 [findOne] 命中即返回，
            //    绝大多数步骤根本不会走满上限。
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    /**
     * 锚点条件判定。**多条件是 AND 关系**（同一个 [Match] 里写的条件必须同时成立）。
     *
     * ⚠️ 刻意是 `internal` 而不是 `private`：[AutomationRunner] 在"批量操作"类步骤里
     *    需要单独判断某个节点是否命中某个 [Match] ——
     *    典型是 [AutomationStep.ToggleOffAll] 要认出"哪些行是被排除的总开关"。
     *    那种场景不要"找一个节点"，所以用不了 [findOne]。
     */
    internal fun matchesAnchor(node: AccessibilityNodeInfo, match: Match): Boolean {
        // 文本
        if (match.text != null) {
            val actual = node.text?.toString() ?: return false
            if (match.maxTextLength != null && actual.length > match.maxTextLength) return false
            if (!textMatches(actual, match.text, match.textMatch)) return false
        } else if (match.maxTextLength != null) {
            // 没写 text 却写了长度上限：没有意义，视为不通过，避免误用。
            return false
        }

        // viewId
        if (match.viewIdContains != null) {
            val id = node.viewIdResourceName ?: return false
            if (!id.contains(match.viewIdContains, ignoreCase = true)) return false
        }

        // contentDescription
        if (match.descContains != null) {
            val desc = node.contentDescription?.toString() ?: return false
            if (!textMatches(desc, match.descContains, match.descMatch)) return false
        }

        // 类名（全等）
        if (match.className != null) {
            if (node.className?.toString() != match.className) return false
        }

        // 可点击性：用来精确锁定可点的那个节点，或排除同名但不可点的干扰项。
        if (match.clickable != null && node.isClickable != match.clickable) return false

        // 可编辑性：锁定输入框。
        if (match.editable != null && node.isEditable != match.editable) return false

        // 焦点：区分"同名同位的两个输入框"（见 Match.focused 的 KDoc）。
        if (match.focused != null && node.isFocused != match.focused) return false

        return true
    }

    /** 文本比较。⚠️ 一律忽略大小写。 */
    private fun textMatches(actual: String, expected: String, type: MatchType): Boolean = when (type) {
        MatchType.Exact -> actual.equals(expected, ignoreCase = true)
        MatchType.Prefix -> actual.startsWith(expected, ignoreCase = true)
        MatchType.Contains -> actual.contains(expected, ignoreCase = true)
    }

    /** 根据 [Match.target] 把锚点解析成真正要操作的节点。 */
    private fun resolveTarget(anchor: AccessibilityNodeInfo, match: Match): NodeHit? = when (match.target) {
        MatchTarget.Self -> NodeHit(
            node = anchor,
            description = "自命中「${anchor.describe()}」",
            requireChecked = false,
            clickFraction = match.clickFraction,
            matchIndex = 0, // 由 findOne 统一填上真实的候选序号
        )

        MatchTarget.ClickableAncestor -> {
            val target = findClickableAncestor(anchor) ?: anchor
            NodeHit(
                node = target,
                description = "祖先命中「${anchor.describe()}」→ ${target.className}",
                requireChecked = false,
                clickFraction = match.clickFraction,
                matchIndex = 0,
            )
        }

        MatchTarget.SwitchInSameRow -> {
            val switch = findSwitchInSameRow(anchor) ?: return null
            // 开关所在整行可点时点整行，否则点开关自身 —— 部分 ROM 的开关节点
            // 自己不接受点击，可点区域是外层容器。
            val target = findClickableAncestor(switch) ?: switch
            NodeHit(
                node = target,
                description = "同行开关「${anchor.describe()}」→ checked=${switch.checkedState()}",
                requireChecked = match.onlyWhenChecked,
                clickFraction = match.clickFraction,
                matchIndex = 0,
            )
        }
    }

    // -------------------------------------------------------------------------
    // 关系定位
    // -------------------------------------------------------------------------

    /**
     * 找与 [anchor] 在**同一行**里的开关。
     *
     * 查找顺序（从近到远，避免误抓别的行的开关）：
     * 1. 锚点的**兄弟节点**里找开关；
     * 2. 兄弟节点的**子树**里找（有些 ROM 把开关包了一层容器）；
     * 3. 仍找不到，才向上一层重复步骤 1-2，最多 [MAX_ROW_ANCESTOR_DEPTH] 层。
     *
     * ⚠️ 步骤 1、2 优先是刻意的：设置页一行一个开关，直接向上找很容易
     * 在"整个列表容器"那一层抓到**第一行的**开关，那就点错行了。
     */
    private fun findSwitchInSameRow(anchor: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var level: AccessibilityNodeInfo? = anchor
        var depth = 0

        while (level != null && depth <= MAX_ROW_ANCESTOR_DEPTH) {
            val parent = level.parent ?: return null
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChild(i) ?: continue
                if (sibling == level || sibling == anchor) continue

                if (isSwitch(sibling)) return sibling
                findSwitchInSubtree(sibling)?.let { return it }
            }
            // 本级兄弟里没有开关 → 往上爬一层再看
            level = parent
            depth++
        }
        return null
    }

    /** 在子树里找第一个开关（广度优先，浅层优先）。 */
    private fun findSwitchInSubtree(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (++visited > MAX_NODES) return null

            if (isSwitch(node)) return node

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return null
    }

    private fun isSwitch(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString() ?: return false
        return SWITCH_CLASS_HINTS.any { cls.contains(it) }
    }

    /** 从 [node] 向上找最近的、自己就接受点击的祖先（含自身）。 */
    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= MAX_ROW_ANCESTOR_DEPTH) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * 读开关状态。
     *
     * ⚠️ 显式抑制弃用：`AccessibilityNodeInfo.isChecked` 是唯一能读到开关状态的
     * 平台 API（AndroidX 的 `AccessibilityNodeInfoCompat.isChecked` 内部也调它，
     * 只是把告警隔离了）。
     */
    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.checkedState(): Boolean = isChecked

    /** 节点摘要，只用于日志。 */
    private fun AccessibilityNodeInfo.describe(): String {
        val label = text?.toString() ?: contentDescription?.toString() ?: ""
        val id = viewIdResourceName?.substringAfterLast('/') ?: ""
        return when {
            label.isNotEmpty() && id.isNotEmpty() -> "$label @$id"
            label.isNotEmpty() -> label
            id.isNotEmpty() -> "@$id"
            else -> className?.toString() ?: "?"
        }
    }
}
