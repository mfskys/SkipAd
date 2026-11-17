package org.dpdns.mfsky.skipad.util

import android.content.pm.PackageManager
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dpdns.mfsky.skipad.adskip.AdSkipRuntime
import rikka.shizuku.Shizuku

/**
 * Shizuku 通道 —— 只做一件事：**以 shell 身份跑一条命令**。
 *
 * ## 用在哪
 *
 * 全程只有一处：`「无障碍服务」`那一行。本机装了并运行着 Shizuku 时，直接
 * `settings put secure enabled_accessibility_services …` 把本应用的无障碍打开，
 * 用户不必再跳系统设置（见 [ShizukuAccessibility]）。
 *
 * ⚠️ **它是可选的**：没装 / 没运行 / 没授权 / 任何一步失败时，一律降级回系统设置页。
 * 本应用不依赖 Shizuku 做任何"必需"的事。
 *
 * ## 为什么走 `Shizuku.newProcess` 而不是官方的 UserService(AIDL)
 *
 * 官方推荐 UserService，但那要一整套 AIDL + 绑定/解绑 + 超时重试，实现规模远超本需求；
 * 而我们只要跑两条 `settings` 命令、输出也就几十字节。
 * `newProcess` 起出来的进程身份与 Shizuku 本体一致（shell / root），
 * 正是 `settings put secure` 需要的身份。
 *
 * ## ⚠️ 两个"照直觉写就会静默失败"的点（都踩过）
 *
 * 1. **`newProcess` 是 `private` 的**，必须走 `declaredMethods` + `setAccessible(true)`。
 *    `javap -p` 实测（api 13.1.5）：
 *    ```text
 *    private static rikka.shizuku.ShizukuRemoteProcess newProcess(String[], String[], String);
 *    ```
 *    只查 `Shizuku::class.java.methods`（只含 public）会**永远找不到**，
 *    表现为 `exec` 返回 null、界面提示"Shizuku 命令没有返回" —— 而权限明明是给过的。
 * 2. **输出要在 `waitFor` 之前开始读**：子进程输出超过管道缓冲（64KB）时会写阻塞，
 *    而父进程此刻正卡在 `waitFor` 上 —— 经典死锁。我们的命令输出很小，
 *    但这条纪律几乎不花代价，留着。
 *
 * ⚠️ 反射私有成员**怕混淆**：release **已经**开了 R8（`optimization { enable = true }`），
 *   所以 `app/proguard-rules.pro` 里**必须**保着 `-keep class rikka.shizuku.** { *; }` ——
 *   少了它，`declaredMethods` 就找不到 `newProcess`，这条通路会**静默失效**
 *   （异常被 `runCatching` 吃掉，界面只提示"Shizuku 命令没有返回"，看不出真正原因）。
 */
object ShizukuShell {

    /** 授权请求码。Shizuku 用它区分是谁在申请（本项目只有这一处申请）。 */
    const val REQUEST_CODE = 1002

    /** 单条命令的最长等待时间 —— 防止 Shizuku 侧卡住时永久挂起。 */
    private const val EXEC_TIMEOUT_SECONDS = 15L

    /** 读线程的额外等待上限（命令已经结束时它应当立刻返回）。 */
    private const val READER_JOIN_TIMEOUT_MS = 5_000L

    /** Shizuku 应用在运行吗（binder 递进来了没有）。 */
    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 本应用拿到 Shizuku 授权了吗。 */
    fun isAuthorized(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 弹 **Shizuku 自己的**授权框（不是本应用的对话框）。
     *
     * 结果通过 `Shizuku.addRequestPermissionResultListener` 回来，
     * 见 `rememberShizukuAccessibilityOpener`。
     *
     * @return 请求有没有发出去（没装 / binder 不在时为 false）。
     */
    fun requestPermission(): Boolean = runCatching {
        Shizuku.requestPermission(REQUEST_CODE)
    }.isSuccess

    /**
     * 以 shell 身份执行一条命令。
     *
     * @return 命令的完整输出；**任何一步出错都返回 null**（调用方据此降级）。
     *   失败原因会写进应用日志（同时进 logcat，tag = `SkipAd`），
     *   日志里带 `[Shizuku]` 前缀，方便直接过滤。
     */
    suspend fun exec(command: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            log("exec：$command")

            val process = newShellProcess(command)

            // ⚠️ 先起读线程、再 `waitFor`（理由见类文档第 2 点）。
            var output = ""
            val reader = thread(name = "shizuku-shell-read") {
                output = runCatching {
                    // `use` 保证流关闭 —— 每次点击都会新建一个子进程，不关就是漏 FD。
                    process.inputStream.bufferedReader().use { it.readText() }
                }.getOrElse { e ->
                    log("读输出失败：${e.javaClass.simpleName}: ${e.message}")
                    ""
                }
            }

            // ⚠️ 必须带超时：Shizuku 侧进程若卡住（binder 断开 / shell 等输入），
            //    无超时的 `waitFor` 会永久挂起，界面停在"正在开启…"且永不返回。
            val finished = process.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                reader.join(READER_JOIN_TIMEOUT_MS)
                log("命令超时（${EXEC_TIMEOUT_SECONDS}s），已强制结束")
                return@runCatching null
            }
            reader.join(READER_JOIN_TIMEOUT_MS)

            log("命令结束：exitCode=${process.exitValue()}，输出 ${output.length} 字")
            output
        }.getOrElse { e ->
            // 这里**必须**留下原因：否则上层只能说"命令没有返回"，排查时毫无线索。
            log("命令失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * 反射调用 `Shizuku.newProcess(cmd, env, dir)` —— private 方法，理由见类文档第 1 点。
     *
     * ⚠️ 按"方法名 + 3 个参数"找，不按参数类型硬匹配：不同 Shizuku 版本上
     *    参数类型可能不完全一致，硬匹配会"NoSuchMethod"。找到后再按类型精确挑一次。
     */
    private fun newShellProcess(command: String): Process {
        val clazz = Shizuku::class.java
        val candidates = (clazz.declaredMethods.asList() + clazz.methods.asList())
            .filter { it.name == "newProcess" && it.parameterTypes.size == 3 }

        val method = candidates.firstOrNull { m ->
            val p = m.parameterTypes
            p[0].isArray && p[1].isArray && p[2] == String::class.java
        } ?: candidates.firstOrNull()

        if (method == null) {
            log("找不到 newProcess：declaredMethods 共 ${clazz.declaredMethods.size} 个，" +
                "含 'Process' 的 ${clazz.declaredMethods.count { it.name.contains("rocess") }} 个")
            throw NoSuchMethodException("Shizuku.newProcess 不存在（Shizuku 版本过旧？）")
        }

        val accessible = runCatching { method.isAccessible = true }.isSuccess
        if (!accessible) log("setAccessible 失败（可能是 R8 混淆或系统限制）")
        log("改用 ${method.toGenericString()}（可访问=${method.isAccessible}）")

        val result = method.invoke(null, arrayOf("/system/bin/sh", "-c", command), null, null)
        return result as? Process
            ?: throw IllegalStateException("Shizuku.newProcess 返回了 ${result?.javaClass?.name}")
    }

    /** 同时写应用日志（界面可复制）与 logcat（可实时 `adb logcat -s SkipAd`）。 */
    internal fun log(message: String) {
        runCatching { AdSkipRuntime.log("[Shizuku] $message") }
            .onFailure { android.util.Log.d("SkipAd", "[Shizuku] $message") }
    }
}
