package org.dpdns.mfsky.skipad.adskip

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 页面快照 —— 把当前界面上"有信息的节点"导出成多行文本。
 *
 * ## 为什么要它
 *
 * 任务失败时（超时/找不到节点），光看"等待超时"四个字没法排查 ——
 * 需要知道**失败那一刻屏幕上到底有什么**。这里把当时的节点留档，
 * 用户点「复制」发过来，一眼就能看出是锚点文字变了、控件在别的包里，
 * 还是页面压根没到。
 *
 * ## 输出格式
 *
 * 每个有信息的节点一行：
 * ```
 * · text='系统工具广告' desc='' @title android.widget.TextView [44,824][1396,1029]
 * ```
 * 纯容器节点（没有 text/desc/id）被过滤 —— 快照的目的是看"有什么可匹配的"，
 * 不是还原完整布局。上限 [DEFAULT_MAX_NODES] 防止失控。
 *
 * ## ⚠️ 隐私：文本一律截断
 *
 * 这份内容会 ① 落盘 `filesDir/logs/` ② 进 logcat ③ 被「复制全部」放进剪贴板。
 * 而失败可能发生在任何页面（聊天、账号页），所以 text / contentDescription
 * **只保留前 [SNAPSHOT_TEXT_MAX] 个字符**：足够认出锚点，又不至于把整段内容带走。
 */
internal fun dumpPageSnapshot(
    root: AccessibilityNodeInfo?,
    maxNodes: Int = DEFAULT_MAX_NODES,
    /**
     * 诊断模式：连**纯容器**一起输出（附 `childCount` 与"实际能读到几个子节点"）。
     *
     * 用来回答一类很难查的问题："控件明明在屏幕上，为什么不在树里？" ——
     * 只有这两个数字能区分是"根本没有这个子节点"，还是"有、但
     * `getChild()` 返回了 null"（跨进程取节点时的常见故障，两者的排查方向完全不同）。
     */
    verbose: Boolean = false,
): String {
    if (root == null) return "（无法读取界面：根节点为空）"

    val sb = StringBuilder()
    val bounds = Rect()
    var visited = 0

    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.addLast(root)

    while (queue.isNotEmpty() && visited < maxNodes) {
        val node = queue.removeFirst()
        visited++

        val text = ellipsize(node.text?.toString().orEmpty())
        val desc = ellipsize(node.contentDescription?.toString().orEmpty())
        val id = node.viewIdResourceName?.substringAfterLast('/') ?: ""

        // ⚠️ `childCount` 只取一次；子节点也只 `getChild(i)` 一次（每次都是一次跨进程 IPC）。
        //    诊断模式下"实际能读到几个"直接用这份列表的大小，不再重复遍历。
        val childCount = node.childCount
        val children = ArrayList<AccessibilityNodeInfo>(childCount)
        for (i in 0 until childCount) {
            node.getChild(i)?.let { children.add(it) }
        }

        if (text.isEmpty() && desc.isEmpty() && id.isEmpty()) {
            // 纯容器：默认不输出，但继续遍历子节点。
            //
            // ⚠️ 诊断模式下**要输出**，且必须带上 `childCount` 和"实际能读到几个"
            //    这两个数字 —— 它们是区分"没有这个子节点"与"有、但 `getChild()`
            //    返回 null"的唯一证据（见 [verbose] 的说明）。
            if (verbose) {
                node.getBoundsInScreen(bounds)
                sb.append("· [容器] ").append(node.className)
                    .append(" childCount=").append(childCount)
                    .append(" readable=").append(children.size)
                    .append(' ').append(bounds)
                    .appendLine()
            }
            children.forEach { queue.addLast(it) }
            continue
        }

        node.getBoundsInScreen(bounds)
        sb.append("· text='").append(text)
        if (desc.isNotEmpty()) sb.append("' desc='").append(desc)
        if (id.isNotEmpty()) sb.append("' @").append(id)
        sb.append(' ').append(node.className)
            .append(' ').append(bounds)
        // ⚠️ 诊断模式下**每个节点**都要附上子节点统计。只给"纯容器"加是不够的：
        //    像 `recycler_view` 这种**带 id 的容器**走的是上面这条普通分支，
        //    恰恰最需要看的就是它到底有几个能读到的子节点。
        if (verbose) {
            sb.append(" childCount=").append(childCount)
                .append(" readable=").append(children.size)
        }
        sb.appendLine()

        children.forEach { queue.addLast(it) }
    }

    if (visited >= maxNodes || queue.isNotEmpty()) {
        sb.append("· …（快照已截断，仅展示前 ").append(visited).append(" 个节点）")
    }
    return sb.toString()
}

/**
 * 快照文本截断 —— 见文件头的隐私说明。
 *
 * 40 个字符足够认出「系统广告」「允许通知」这类锚点，又不会把一条聊天消息
 * 或一段账号信息整段带走。
 */
private fun ellipsize(raw: String): String =
    if (raw.length <= SNAPSHOT_TEXT_MAX) raw else raw.take(SNAPSHOT_TEXT_MAX) + "…"

private const val SNAPSHOT_TEXT_MAX = 40
private const val DEFAULT_MAX_NODES = 40
