package org.dpdns.mfsky.skipad.adskip

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 处理会话的状态。
 *
 * ⚠️ 这套状态机是本应用的核心语义：**它是一次性工具，不是常驻服务**。
 * 只有在 [Running] 期间引擎才会动屏幕，做完（[Finished]）就彻底停下 ——
 * 用户不需要一直开着无障碍服务。
 */
enum class SkipSession {
    /** 还没开始。引擎完全不动屏幕，即使用户开了无障碍。 */
    Idle,

    /** 用户点了「开始处理」，正在按脚本逐项执行。 */
    Running,

    /** 全部处理完毕（无论成功失败）。引擎停止工作。 */
    Finished,
}

/** 一行处理记录。 */
data class SkipLogEntry(
    val timeMs: Long,
    val message: String,
    /** true = 成功 / false = 失败 / null = 普通进度信息。 */
    val handled: Boolean?,
)

/** 一个任务的处理结果，用于界面汇总。 */
data class TaskOutcome(
    val taskId: String,
    val taskTitle: String,
    val success: Boolean,
    val detail: String,
)

/**
 * 运行期状态与日志的**唯一出口**。
 *
 * 引擎（无障碍线程）写、界面（主线程）读，所以内部用 [StateFlow] 保证跨线程可见，
 * 并且**日志有上限**（不设上限的话跑几小时就能把内存吃掉）。
 */
object AdSkipRuntime {

    private val _session = MutableStateFlow(SkipSession.Idle)
    val session: StateFlow<SkipSession> = _session.asStateFlow()

    /**
     * 本次会话是不是「自动获取权限」，而不是关广告。
     *
     * ⚠️ 需要它是因为两者的**"总项数"不是一个数**：状态卡上要写"第 N/M 项"，
     * 而 `M`（= 广告清单长度）对权限流程毫无意义 —— 权限流程只有一项。
     * 界面据此换成"正在自动获取权限"。
     */
    private val _permissionSession = MutableStateFlow(false)
    val permissionSession: StateFlow<Boolean> = _permissionSession.asStateFlow()

    /**
     * 每个任务的结果。
     *
     * ⚠️ **刻意只放内存**，不持久化：本应用的使用场景是"新手机初始化，一次跑完"，
     * 而"关掉某个开关"本身是系统里的持久设置 —— 重跑一遍时那些已经关掉的开关
     * 会因为幂等而**什么都不做**，所以没有必要记住"上次跑到哪"。
     */
    private val _outcomes = MutableStateFlow<List<TaskOutcome>>(emptyList())
    val outcomes: StateFlow<List<TaskOutcome>> = _outcomes.asStateFlow()

    /**
     * **跨次累计**已处理完的任务 id —— 来自 [SkipProgressRepository]，持久化。
     *
     * ⚠️ 与 [outcomes] 的区别要分清：
     * - [outcomes] 是**本次会话**的结果，带成功/失败详情，只存内存；
     * - 这个是**历史上的"已完成"**，只记成功、跨重启保留，
     *   用来让「继续处理」直接把整项跳过（连流程都不用走）。
     *
     * 由界面层从 DataStore 同步进来（见 `SkipAdApp`），引擎只读它。
     * 这样引擎不必碰 suspend 的 DataStore，保持同步语义。
     */
    private val _doneTaskIds = MutableStateFlow<Set<String>>(emptySet())
    val doneTaskIds: StateFlow<Set<String>> = _doneTaskIds.asStateFlow()

    internal fun setDoneTaskIds(ids: Set<String>) {
        _doneTaskIds.value = ids
    }

    /** 清单总项数。 */
    val totalTaskCount: Int get() = BuiltinAdTasks.size

    /** 当前正在处理第几项（从 1 开始，0 表示未开始）。 */
    private val _currentTaskIndex = MutableStateFlow(0)
    val currentTaskIndex: StateFlow<Int> = _currentTaskIndex.asStateFlow()

    /** 当前正在执行哪一步（界面显示"正在做什么"）。 */
    private val _currentStepLabel = MutableStateFlow<String?>(null)
    val currentStepLabel: StateFlow<String?> = _currentStepLabel.asStateFlow()

    /** 当前任务的引导路径（失败了用户要靠它自己走过去）。 */
    private val _currentGuide = MutableStateFlow<String?>(null)
    val currentGuide: StateFlow<String?> = _currentGuide.asStateFlow()

    private val _entries = MutableStateFlow<List<SkipLogEntry>>(emptyList())
    val entries: StateFlow<List<SkipLogEntry>> = _entries.asStateFlow()

    /**
     * 本次会话**是否已经放过庆祝撒花**。
     *
     * ⚠️ 它必须挂在会话上，而不是主页的 `remember` 里：主页是 `NavDisplay` 的一个目的地，
     * 切到设置再切回来会**重建组合** —— 那时 `remember`/`rememberSaveable` 都归零，
     * 于是"刚跑完成功、来回切页面"会**每次回主页都撒一遍花**（实测如此）。
     *
     * 刻意用普通 `var` 而**不是** `StateFlow`：它只被"要不要放"这一处读一次，
     * 不需要驱动重组；放起来的是 [CelebrationFireworks] 自己的动画状态。
     */
    var celebrated: Boolean = false
        private set

    /** 标记本次会话已庆祝（由主页在"全部成功"时调用，幂等）。 */
    fun markCelebrated() {
        celebrated = true
    }

    /**
     * **界面用**的日志条数上限（内存环形缓冲）。
     *
     * ⚠️ 这个上限**只影响界面**，不再决定"能拿到多少日志"——「复制」取的是落盘的那份
     * （见 [attach] / [fullLogText]）。
     *
     * 之前它同时是"全部日志"，于是**一次完整处理**（15 项 × 每项 30~40 条 ≈ 400+ 条）
     * 就会把**出问题之前**的那段挤掉：用户回头点「复制」，拿到的只剩尾巴，
     * 而排查最需要的前半段恰好没了。
     */
    private const val MAX_ENTRIES = 150

    // ── 全量日志落盘（「复制全部」取的就是它）─────────────────────────────────
    //
    // 为什么必须落盘，两个理由都是实测会碰到的：
    //   ① 一次长会话就能超过内存上限，早期的被挤掉；
    //   ② 进程一重启（MIUI 省电策略会解绑/杀）内存日志全没 ——
    //      而"用着出了问题、回头再点复制"这个场景，经常就发生在重启之后。

    /** 落盘文件与写盘线程；由 [attach] 赋值。未赋值时所有落盘逻辑静默跳过。 */
    private var logFile: File? = null
    private var logIo: ExecutorService? = null

    /** 单个日志文件的上限，超过就轮转。 */
    private const val LOG_MAX_BYTES = 512 * 1024L

    /** 轮转保留的历史份数（`skipad.log.1`…）。 */
    private const val LOG_BACKUPS = 1

    /** 写盘与清空之间的互斥（写盘在 IO 线程，清空可能从主线程发起）。 */
    private val logWriteLock = Any()

    /**
     * 把 Context 交给日志系统（只为拿 `filesDir` 落盘）。
     *
     * 调用点两处，缺一不可：
     * · `MainActivity.onCreate` —— 用户打开了界面；
     * · `AdSkipAccessibilityService.onServiceConnected` —— 进程是被无障碍服务拉起来的
     *   （用户直接从系统设置拨开关，或 MIUI 重连服务），此时界面从没开过。
     *
     * ⚠️ 只存 `applicationContext`：这是个单例，存 Activity 必然泄漏。
     */
    fun attach(context: Context) {
        if (logFile != null) return
        val dir = File(context.applicationContext.filesDir, "logs")
        runCatching { dir.mkdirs() }
        logFile = File(dir, "skipad.log")
        logIo = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "skipad-log").apply { isDaemon = true }
        }
        // 每次启动补一条分隔线：两次运行首尾黏在一起时，光看时间戳看不出中间重启过。
        appendRaw("${formatStamp(System.currentTimeMillis())}  ──────── 应用启动 ────────")
    }

    // -------------------------------------------------------------------------
    // 会话控制（只有界面调）
    // -------------------------------------------------------------------------

    /**
     * 用户点「开始处理」/「继续处理」。
     *
     * @param queuedCount **本次实际要跑几项**（已排除历史上已完成的）。
     *   它与 [totalTaskCount] 不同时，说明这是一次"继续处理"。
     */
    internal fun startSession(queuedCount: Int) {
        _outcomes.value = emptyList()
        _currentTaskIndex.value = 0
        _currentStepLabel.value = null
        _currentGuide.value = null
        _permissionSession.value = false
        _session.value = SkipSession.Running
        // 新一次运行 → 解锁"本次可庆祝"（见 [celebrated]）。
        celebrated = false
        log("开始 · $queuedCount 项")
    }

    /**
     * 用户点「自动获取」权限 —— 开始一次权限会话。
     *
     * 与 [startSession] 的差别只有状态与文案：权限流程没有"第 N/M 项"，
     * 也不需要 `queuedCount`。
     */
    internal fun startPermissionSession() {
        _outcomes.value = emptyList()
        _currentTaskIndex.value = 0
        _currentStepLabel.value = null
        _currentGuide.value = null
        _permissionSession.value = true
        _session.value = SkipSession.Running
        log("开始获取权限")
    }

    /** 一次会话结束（全部走完，或中途被停止）。 */
    fun finishSession(stopped: Boolean = false) {
        _session.value = SkipSession.Finished
        _currentStepLabel.value = null

        // ⚠️ 刻意**不清空** `_currentGuide`。
        //
        // 失败时，"设置 → 搜索「广告」→ 系统广告"是用户唯一能照着**自己走过去**的信息；
        // 清掉的话界面上就只剩一句"处理失败"，帮不上任何忙。
        // 它会在下一次 startSession / setCurrentTask 时被覆盖，不会残留成脏数据。
        val ok = _outcomes.value.count { it.success }
        val failed = _outcomes.value.count { !it.success }
        log(
            if (stopped) "已停止 · 成功 $ok · 失败 $failed"
            else "完成 · 成功 $ok · 失败 $failed",
            handled = failed == 0 && !stopped,
        )
    }

    /**
     * 权限会话结束。
     *
     * ⚠️ 刻意回到 [SkipSession.Idle] 而**不是** [SkipSession.Finished]：
     * `Finished` 在状态卡上对应"处理完成 · 成功 N 项，失败 N 项"，
     * 而权限流程一项广告都没处理 —— 套上去只会显示"成功 0 项，失败 0 项"，
     * 白白让用户以为跑失败了。回到 `Idle` 后状态卡自动落回
     * "准备就绪"/"还需要完成权限准备"，正是此刻该给用户的下一步。
     */
    internal fun finishPermissionSession(stopped: Boolean = false) {
        _permissionSession.value = false
        _currentStepLabel.value = null
        _session.value = SkipSession.Idle
        log(if (stopped) "已停止" else "权限获取结束")
    }

    // -------------------------------------------------------------------------
    // 引擎侧上报
    // -------------------------------------------------------------------------

    /**
     * @param total **本次队列的长度**，不是清单总长 ——
     *   用户在清单里点了单项处理时队列只有 1，那时写"N/3"会让人以为还有 2 项要跑。
     */
    internal fun setCurrentTask(index: Int, total: Int, task: AdTask) {
        _currentTaskIndex.value = index + 1
        _currentGuide.value = task.guide
        _currentStepLabel.value = null
        // 权限会话只有一项，序号对它没有意义。
        if (_permissionSession.value) {
            log(task.title)
        } else {
            log("${index + 1}/$total · ${task.title}")
        }
    }

    internal fun setCurrentStep(step: AutomationStep) {
        _currentStepLabel.value = step.label
    }

    internal fun addOutcome(task: AdTask, success: Boolean, detail: String) {
        _outcomes.value = _outcomes.value + TaskOutcome(
            taskId = task.id,
            taskTitle = task.title,
            success = success,
            detail = detail,
        )
        log("${task.title}：$detail", handled = success)
    }

    internal fun log(message: String, handled: Boolean? = null) {
        val entry = SkipLogEntry(
            timeMs = System.currentTimeMillis(),
            message = message,
            handled = handled,
        )
        _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)

        // 同步一份到 logcat（tag = SkipAd）：应用内的日志卡片只在会话后可翻，
        // 而 logcat 可以边跑边看 —— 用 `adb logcat -s SkipAd` 实时盯执行轨迹，
        // 排查"卡在哪一步"时不用等跑完再复制。
        android.util.Log.d("SkipAd", message)

        // 同步一份到落盘文件：**全量**，不受 [MAX_ENTRIES] 限制（见 [fullLogText]）。
        appendRaw(formatLine(entry))
    }

    /**
     * 清空日志 —— **内存与落盘一起清**。
     *
     * ⚠️ 只清内存的话，"清空"之后点「复制」又会冒出一大段历史，
     *    与用户对"清空"的预期直接冲突。
     */
    fun clearLog() {
        _entries.value = emptyList()
        val file = logFile ?: return
        val io = logIo ?: return
        io.execute {
            runCatching { synchronized(logWriteLock) { file.delete() } }
            for (i in 1..LOG_BACKUPS) {
                runCatching { File(file.parentFile, "${file.name}.$i").delete() }
            }
        }
    }

    /**
     * **完整日志文本**（本次 + 历史），供界面上的「复制全部」使用。
     *
     * 来源是**落盘文件**而不是内存：[entries] 只留最近 [MAX_ENTRIES] 条，
     * 而且进程重启就没了 —— 用户"用着出了问题、回头再复制"时，
     * 只有文件里的那份能给出真相。
     *
     * 落盘尚未就绪（[attach] 没被调用）时退回内存里的 [entries]，
     * 保证**至少不会给出空内容**。
     *
     * ⚠️ **suspend + IO 线程**：最多要读 512KB × 2 个文件，而调用点是
     *    「复制全部」的点击回调。同步读会把主线程卡住（低端机上直接掉帧/ANR）。
     */
    suspend fun fullLogText(): String = withContext(Dispatchers.IO) {
        val file = logFile
        val fromFile = runCatching {
            if (file == null || !file.exists()) {
                ""
            } else {
                buildString {
                    // 轮转出去的历史（更早）在前、当前文件在后 —— 读起来才是时间顺序。
                    for (i in LOG_BACKUPS downTo 1) {
                        val older = File(file.parentFile, "${file.name}.$i")
                        if (older.exists()) append(older.readText())
                    }
                    append(file.readText())
                }
            }
        }.getOrDefault("")
        if (fromFile.isNotBlank()) {
            fromFile.trimEnd()
        } else {
            // 退路：内存里的（旧 → 新）。
            _entries.value.asReversed().joinToString("\n") { formatLine(it) }
        }
    }

    /**
     * 一行日志的**落盘/导出格式**：`2026-10-10 23:12:34  [失败] 消息`。
     *
     * ⚠️ 与界面显示**故意不同**，两个原因：
     * · 界面只显示 `HH:mm:ss`（一次会话内够用），而导出的内容会跨越多次运行和好几天，
     *   没有日期根本对不上时间；
     * · 界面上"成功 / 失败"靠**颜色**区分，纯文本里颜色会丢，所以改用文字标记。
     */
    private fun formatLine(entry: SkipLogEntry): String {
        val mark = when (entry.handled) {
            true -> "[成功] "
            false -> "[失败] "
            null -> ""
        }
        return "${formatStamp(entry.timeMs)}  $mark${entry.message}"
    }

    /** 落盘用的时间戳。 */
    private fun formatStamp(timeMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMs))

    /** 把一行**整行文本**写进文件（异步，绝不占主线程做 IO）。 */
    private fun appendRaw(line: String) {
        val file = logFile ?: return
        val io = logIo ?: return
        runCatching {
            io.execute {
                runCatching {
                    synchronized(logWriteLock) {
                        if (file.length() > LOG_MAX_BYTES) rotate(file)
                        file.appendText(line + "\n")
                    }
                }
            }
        }
    }

    /**
     * 文件超限后轮转：`skipad.log` → `skipad.log.1`（更老的直接丢）。
     *
     * 为什么要轮转：日志会无限增长，而应用私有目录也在用户的存储里 ——
     * 一个跑了几百次的工具留几十 MB 日志说不过去。512KB × 2 份足够放下
     * 十几次完整运行的轨迹。
     */
    private fun rotate(file: File) {
        for (i in LOG_BACKUPS downTo 1) {
            val target = File(file.parentFile, "${file.name}.$i")
            val source = if (i == 1) file else File(file.parentFile, "${file.name}.${i - 1}")
            runCatching { target.delete() }
            runCatching { source.renameTo(target) }
        }
        runCatching { file.createNewFile() }
    }
}

/**
 * 把本应用拉回前台。
 *
 * 两处在用：
 * - 无障碍服务刚连上时（用户刚从系统设置里打开开关，把他送回来）；
 * - 一次处理会话结束时（别让用户停在系统设置页自己找回来的路）。
 *
 * 用 `getLaunchIntentForPackage` 而不是直接引用 `MainActivity`：
 * 调用方在 `adskip` 包、Activity 在根包，这样写不依赖具体 Activity 类，
 * 将来换入口也不用改这里。
 */
internal fun Context.bringAppToFront(): Boolean {
    val intent = packageManager
        .getLaunchIntentForPackage(packageName)
        ?.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                // CLEAR_TOP：本应用之上如果还压着别的页面，不要留着
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                // SINGLE_TOP：走 onNewIntent 而不是重建 Activity ——
                // 处理结果（状态卡 / 日志）都还在，用户回来能直接看到
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        ?: return false

    // ⚠️ 优先走 `PendingIntent.send()`，而不是直接 `startActivity()`。
    //
    // Android 10 起限制"后台应用启动 Activity"，而无障碍服务**在处理事件的过程中**
    // 恰好会被判定成后台。此时 `startActivity()` 既**不抛异常、也不返回失败**，
    // 只是**静默地把请求丢掉** —— 表现就是"开关已经关掉了，界面却停在系统设置页不回来"。
    //
    // `PendingIntent.send()` 是交给**系统**去执行的，不受这条限制
    // （通知点击能拉起 Activity 用的就是这个原理）。
    val sent = runCatching {
        PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ).send()
    }.isSuccess
    if (sent) return true

    // 兜底：直连 startActivity。少数 ROM 上 PendingIntent 会被拦、直连反而放行。
    return runCatching { startActivity(intent) }.isSuccess
}

/**
 * 某个应用包在这台设备上**是否可用** —— 已安装、且没有被停用。
 *
 * ## 为什么要连"停用"一起判断
 *
 * 只查"包名存不存在"是不够的：MIUI 允许用户**停用**预装应用
 * （设置 → 应用管理 → 停用），停用后包信息仍在 `packageManager` 里，
 * 但组件**一律无法启动**。这种项照样会在第 1 步"打开 XX"白等到超时。
 *
 * ## ⚠️ 这里**必须**配合 `QUERY_ALL_PACKAGES`（见 AndroidManifest.xml）
 *
 * `getApplicationInfo()` 属于**软件包可见性**（Android 11 / API 30 起）管辖的接口：
 * 没在清单里声明过的包，它会抛 `NameNotFoundException` ——
 * 与"真的没装"**完全无法区分**。
 *
 * 这个坑实际踩过：漏了那个权限声明时，清单里大半任务被误判成"未安装"直接跳过，
 * 主页进度环显示 `3/3`，而机器上其实一项都没少装。**且全程不报错**。
 *
 * 所以修改这里之前，先确认 `AndroidManifest.xml` 里那条声明还在。
 *
 * 查不到（没装）时返回 false，与"停用"合并成同一个结论：**这一项做不了**。
 */
internal fun Context.isPackageUsable(pkg: String): Boolean {
    val info = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                pkg,
                PackageManager.ApplicationInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(pkg, 0)
        }
    }.getOrNull()

    return info?.enabled == true
}
