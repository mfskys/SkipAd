package org.dpdns.mfsky.skipad.adskip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 点击执行器 —— **三级回退** + **会话级去重**。
 *
 * ## 为什么必须三级回退（这是踩过的坑，不是保险起见）
 *
 * 1. `performAction(ACTION_CLICK)`：最精准，不产生真实触摸事件。
 * 2. 父节点 `ACTION_CLICK`：文字节点（TextView）**自己几乎从不可点**，
 *    真正响应点击的通常是外面那层容器，所以锚点命中后必须能往上让一层。
 * 3. `dispatchGesture` 落一个真实触摸：**某些广告 SDK / 系统控件会直接忽略
 *    `ACTION_CLICK`（返回 true 但界面毫无反应）**，只有真实触摸才认。
 *
 * ## 会话级去重的 key 为什么不含文本
 *
 * 开屏广告的跳过按钮常带倒计时："跳过 5" → "跳过 4" → "跳过 3"。
 * 如果 key 里带上文本，每次倒计时变化都会被当成**新的**控件，于是被反复点击。
 * 所以 key 只取 `className | viewId | bounds`：倒计时变化时这三者都不变，
 * 天然被识别为同一个按钮。
 */
internal class NodeClicker(
    private val service: AccessibilityService,
) {

    /** 单个控件在本轮会话内允许的最大点击次数。 */
    private val maxAttempts = 2

    /** 同一个控件的两次点击最小间隔（毫秒）。太密会被系统判定为异常输入。 */
    private val minIntervalMs = 500L

    /**
     * 同一个控件允许"手势派发被拒"多少次才放弃它。
     *
     * 比 [maxAttempts] 宽得多：派发被拒几乎都是**暂时性**的
     *（上一个手势还没跑完），而误放弃的代价是**直接漏掉一个开关** ——
     * 实测"娱乐页漏短剧、话题页漏知乎"就是这么来的。
     */
    private val maxDispatchFails = 5

    private data class Attempt(
        /** 点击**成功发出**的次数（手势也算"发出成功"，它没有同步回执）。 */
        var count: Int = 0,
        var lastUptimeMs: Long = 0L,
        /**
         * 手势**派发被拒**的次数。
         *
         * ⚠️ 与 [count] 分开计：这两件事的严重性完全不同。
         * [count] 涨说明"动作发出去了、但界面没反应"（控件可能真点不动）；
         * 而这里涨只是因为 `dispatchGesture` 被临时拒绝 ——
         * 最常见的原因是**上一个手势还没 onCompleted**，等一会儿再发就能成。
         *
         * 混在一起的结果实测过：每一页的**第一个**开关总被误判成"点不动"
         *（它前面刚点过别的页面的收尾动作），于是"娱乐页漏了短剧、话题页漏了知乎"。
         */
        var dispatchFails: Int = 0,
    )

    /** key → 点击记录。 */
    private val attempts = HashMap<String, Attempt>()

    /**
     * 判断该不该**跳过**这个节点（已经点过太多次 / 距上次太近）。
     *
     * 调用方要在点击**之前**问一次；点了之后再调 [recordClick] 记账。
     */
    fun shouldSkip(node: AccessibilityNodeInfo): Boolean {
        val record = attempts[nodeKey(node)] ?: return false
        if (record.count >= maxAttempts) return true
        return SystemClock.uptimeMillis() - record.lastUptimeMs < minIntervalMs
    }

    /**
     * 这个节点是否已经**点够次数**了（= 它真的点不动）。
     *
     * ## 为什么必须和 [shouldSkip] 分开
     *
     * [shouldSkip] 把两种情况混在一起返回 true：
     * 1. **距上次点击太近**（[minIntervalMs] 节流）—— 这是**常态**，
     *    因为手势点击是异步的，`click()` 返回 true 只代表"派发成功"，
     *    控件的真实状态要过一会儿才变；
     * 2. 真的点够 [maxAttempts] 次了 —— 这才是坏掉的那个。
     *
     * 而调用方对这两种情况的反应**应当完全相反**：前者要"等一会儿再试"，
     * 后者才该"放弃它"。混着用会让"正常但还没生效"的控件被误判成损坏。
     */
    fun isExhausted(node: AccessibilityNodeInfo): Boolean =
        (attempts[nodeKey(node)]?.count ?: 0) >= maxAttempts

    /**
     * 记一次**手势派发被拒**。
     *
     * 只更新节流时间戳、单独累加 [Attempt.dispatchFails] —— **不**并进
     * [Attempt.count]，理由见那个字段的说明。
     */
    fun recordDispatchFailure(node: AccessibilityNodeInfo) {
        val record = attempts.getOrPut(nodeKey(node)) { Attempt() }
        record.dispatchFails++
        record.lastUptimeMs = SystemClock.uptimeMillis()
    }

    /**
     * 这个节点连**派发**都失败太多次了（[maxDispatchFails]）。
     *
     * 额度比 [isExhausted] 宽得多 —— 派发失败是暂时性的，
     * 多给几次机会通常就成了；过早放弃会直接漏掉一个开关。
     */
    fun isDispatchExhausted(node: AccessibilityNodeInfo): Boolean =
        (attempts[nodeKey(node)]?.dispatchFails ?: 0) >= maxDispatchFails

    /** 记一次点击。 */
    fun recordClick(node: AccessibilityNodeInfo) {
        val key = nodeKey(node)
        val record = attempts.getOrPut(key) { Attempt() }
        record.count++
        record.lastUptimeMs = SystemClock.uptimeMillis()
    }

    /**
     * 清空会话。
     *
     * 由引擎在"切换页面"或"新的一轮匹配开始"时调用 —— 对应 [ResetMatch] 的语义。
     * 注意**不是**永久标记：下次进入这个页面仍然会重新尝试（这正是我们想要的，
     * 用户手动改回去之后我们还能再关一次）。
     */
    fun resetSession() {
        attempts.clear()
    }

    /**
     * 执行点击。返回是否**成功**（手势派发成功也算成功，它没有同步的回执）。
     *
     * @param fraction 见 [Match.clickFraction]：指定时**直接走真实手势**落在节点内的
     *   这个相对位置（0f..1f），跳过前面两级 `ACTION_CLICK` —— 那种节点的
     *   "边界 ≠ 热区"，节点自身的点击动作不可靠，试它只是白等一轮。
     */
    fun click(node: AccessibilityNodeInfo, fraction: Pair<Float, Float>? = null): Boolean {
        if (fraction != null) return clickByGesture(node, fraction)

        // ── 第 1 级：节点自己可点 ──
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // ── 第 2 级：父节点可点（文字节点的常见情况）──
        node.parent?.let { parent ->
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }

        // ── 第 3 级：真实手势落在节点中心 ──
        return clickByGesture(node)
    }

    /**
     * 强制用**真实触摸**点一下（跳过 `ACTION_CLICK`）。
     *
     * ## 什么时候需要它：唤醒软键盘
     *
     * `performAction(ACTION_CLICK)` 虽然能"点中"输入框、让光标出现在里面，
     * 但**不会弹出软键盘**。而 MIUI 设置搜索框的查询逻辑挂在 IME 上 ——
     * 没有键盘就永远不触发搜索，表现在界面上就是"文字写进去了，
     * 下面却一直只有搜索历史、没有结果"。
     *
     * 实测对照：`ACTION_CLICK` → 有光标、无键盘、无结果；
     * 真实触摸 → 有光标、有键盘、结果立刻出来。
     *
     * ⚠️ 普通按钮**不要**用这个：真实触摸会产生一次真实点击事件，
     * 能少用就少用（见 [click] 的三级回退设计）。
     */
    fun touch(node: AccessibilityNodeInfo): Boolean = clickByGesture(node)

    /**
     * 在节点范围内**向上滑一屏**（真实手势）。
     *
     * ## 为什么需要它：`ACTION_SCROLL_FORWARD` 的步进可能极小
     *
     * 实测 MIUI「所有应用」那页：`ACTION_SCROLL_FORWARD` 返回 true，但**一次只挪
     * 一两行**（在那页上滚 30 次才走到 T 段），而**同样的页面用真实手势一次就能
     * 滑一整屏**。所以"滚不动/滚太慢"时要能退回真实触摸。
     *
     * 落点取容器高度的 80% → 20%：避开顶部标题栏与底部悬浮按钮，
     * 同时留出足够的滑行距离（太短的滑动会被当成"轻扫"而几乎不滚）。
     *
     * ⚠️ 与点击一样，手势是异步的：派发成功 ≠ 真的滚动完成了。
     *    调用方要按"下一轮再看内容有没有变"来判进度，别信返回值。
     */
    fun swipeUp(node: AccessibilityNodeInfo, durationMs: Long = 300L): Boolean =
        swipeVertical(node, forward = true, durationMs = durationMs)

    /**
     * 在节点范围内**向下滑一屏**（真实手势）：看到列表**更靠前**的内容。
     *
     * 用在哪：`ScrollTo` 往下翻不动、但目标可能在**上方**时回头去找（见坑 #42）——
     * 实测「所有应用」页会带着上一次会话的滚动位置（停在底部），
     * 而目标其实就在视口上方一点、只往下找永远找不到。
     */
    fun swipeDown(node: AccessibilityNodeInfo, durationMs: Long = 300L): Boolean =
        swipeVertical(node, forward = false, durationMs = durationMs)

    /**
     * 竖向滑动一屏（真实手势）。
     *
     * @param forward true = 看后面的内容（手指从下往上划）；
     *   false = 看前面的内容（手指从上往下划）。
     *
     * 落点取容器高度的 80% ↔ 20%：避开顶部标题栏与底部悬浮按钮，
     * 同时留出足够的滑行距离（太短的滑动会被当成"轻扫"而几乎不滚）。
     *
     * ⚠️ 与点击一样，手势是异步的：派发成功 ≠ 真的滚动完成了。
     *    调用方要按"下一轮再看内容有没有变"来判进度，别信返回值。
     */
    private fun swipeVertical(
        node: AccessibilityNodeInfo,
        forward: Boolean,
        durationMs: Long,
    ): Boolean {
        if (!node.refresh()) return false

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false

        val x = bounds.exactCenterX()
        val near = bounds.top + bounds.height() * 0.8f
        val far = bounds.top + bounds.height() * 0.2f

        val path = Path().apply {
            // 手指起点在"靠近自己"的那一侧，划向另一侧
            moveTo(x, if (forward) near else far)
            lineTo(x, if (forward) far else near)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()

        return runCatching { service.dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    /**
     * 用 [AccessibilityService.dispatchGesture] 在节点上落一次真实触摸。
     *
     * 默认落在**节点中心**；给了 [fraction] 就落在节点内的相对位置（0f..1f，相对
     * bounds 的宽/高）—— 用于"节点边界 ≠ 热区"的自绘页面，见 [Match.clickFraction]。
     *
     * ⚠️ 每次点击前都要 [AccessibilityNodeInfo.refresh]：节点快照可能已经过期
     * （广告页 DOM 变化极快），不刷新的话拿到的 bounds 是旧的，会点到错误位置。
     */
    private fun clickByGesture(
        node: AccessibilityNodeInfo,
        fraction: Pair<Float, Float>? = null,
    ): Boolean {
        if (!node.refresh()) return false
        if (!node.isVisibleToUser) return false

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false

        val x = if (fraction == null) {
            bounds.exactCenterX()
        } else {
            bounds.left + bounds.width() * fraction.first.coerceIn(0f, 1f)
        }
        val y = if (fraction == null) {
            bounds.exactCenterY()
        } else {
            bounds.top + bounds.height() * fraction.second.coerceIn(0f, 1f)
        }

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            // ⚠️ 按压时长不能太短：30ms 的"瞬时点击"点按钮没问题，
            //    但实测在 HyperOS 上**唤不醒软键盘** —— 系统级的 input tap（约 100ms）
            //    能弹、无障碍合成手势 30ms 不弹，差异就在按压时长上。
            //    加到 120ms 接近真实手指按下的节奏，输入框就能正常获得 IME 焦点。
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 120L))
            .build()

        return runCatching { service.dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    /**
     * 去重用的节点身份。**刻意不含文本**，理由见类注释。
     *
     * bounds 会被不同版本的 ROM 微调（±几个像素），所以坐标取整到 8 像素一档，
     * 避免同一按钮因为 1px 抖动被当成两个控件。
     */
    private fun nodeKey(node: AccessibilityNodeInfo): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val cls = node.className?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        return "$cls|$id|${bounds.left / 8},${bounds.top / 8}"
    }
}
