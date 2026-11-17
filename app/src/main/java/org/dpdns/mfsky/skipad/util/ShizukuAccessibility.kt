package org.dpdns.mfsky.skipad.util

import org.dpdns.mfsky.skipad.adskip.AdSkipPermission

/**
 * 用 Shizuku 把**本应用的无障碍服务**直接打开。
 *
 * ## 它替代的那条路
 *
 * 没有它时（老路子）：点「去开启无障碍」→ 跳系统设置 → 用户在无障碍列表里
 * **自己找到本应用** → 拨开关 → 切回来。四步里有两步（找到它、拨开关）纯属体力活。
 *
 * 有它时：点一下 → （首次会弹 Shizuku 的授权框）→ 直接开 → 首页那段
 * "无障碍刚开启 → 自动接上第 2~4 步"立刻接管，用户一个开关都不用拨。
 *
 * ## 两条硬约束
 *
 * 1. **不能覆盖写** `enabled_accessibility_services`：那会把用户已启用的
 *    其它无障碍服务（输入法、屏幕朗读、手势导航…）一起抹掉。
 *    所以是"读 → 去重 → 追加 → 写回"。
 * 2. **必须同时写总开关**：只写列表、不写 `accessibility_enabled 1`，
 *    系统不会去绑定服务 —— 表现为"设置里看着是开的，其实没跑"。
 *
 * ⚠️ 两条命令都以 shell 身份执行（见 [ShizukuShell]），这是 `settings put secure`
 *    能成功的前提；普通应用身份写这个 key 会被 `SecurityException` 拒绝。
 *
 * ⚠️ 每一步都留日志（前缀 `[Shizuku]`）：这条链路上任何一个环节断掉，
 *    上层都只能笼统地说"失败了" —— 而**具体是哪一环**才是唯一有用的信息。
 */
object ShizukuAccessibility {

    /**
     * 打开本应用的无障碍服务。
     *
     * @param context 只为拿 applicationId —— 组件名里的包名**不能写死**
     *   （debug 变体带 `.debug` 后缀，写死会去开另一个包的服务，见
     *   [AdSkipPermission.serviceComponent]）。
     * @return **null 表示成功**；否则是给用户看的一句话（由调用方弹 Snackbar 并降级去系统设置）。
     */
    suspend fun enable(context: android.content.Context): String? {
        val component = AdSkipPermission.serviceComponent(context)

        ShizukuShell.log(
            "准备直接开无障碍：binder=${ShizukuShell.isRunning()}，" +
                "已授权=${ShizukuShell.isAuthorized()}，组件=$component",
        )

        // ── ① 读当前列表 ──
        val raw = ShizukuShell.exec("settings get secure enabled_accessibility_services")
        if (raw == null) {
            return "Shizuku 命令没有返回（原因见日志里的 [Shizuku] 行），已改为打开系统设置"
        }
        val enabled = raw.trim()
            .takeIf { it.isNotEmpty() && it != "null" }
            ?.split(":")
            ?.filter { it.isNotBlank() }
            .orEmpty()
        // 只记条目数：完整列表会暴露用户启用了哪些无障碍工具
        //（输入法、屏幕朗读、其它自动化工具），与排查本应用无关。
        ShizukuShell.log("当前无障碍列表：${enabled.size} 项")

        // ── ② 不在列表里才追加（幂等）──
        //
        // ⚠️ 用 [AdSkipPermission.componentMatches] 而不是字符串相等：系统存的是
        //    **缩写式**（`包名/.类名`），直接比会认不出"已经在里面了"，
        //    于是每次点都往列表里再塞一条重复项。
        if (enabled.none { AdSkipPermission.componentMatches(it, context) }) {
            val merged = (enabled + component).joinToString(":")
            ShizukuShell.log("追加后写回：${merged.split(":").size} 项")
            if (ShizukuShell.exec("settings put secure enabled_accessibility_services '$merged'") == null) {
                return "写入无障碍列表失败（原因见日志），已改为打开系统设置"
            }
        } else {
            ShizukuShell.log("列表里已经有它了，跳过写入（幂等）")
        }

        // ── ③ 总开关 ──
        if (ShizukuShell.exec("settings put secure accessibility_enabled 1") == null) {
            return "打开无障碍总开关失败（原因见日志），已改为打开系统设置"
        }

        // ── ④ 读回验证 ──
        //
        // ⚠️ 这一读很关键：`settings put` 成功**不代表真的写进去了**
        //   （权限不足时它是静默失败的）。不验证的话会出现"提示成功、其实没开"。
        val verify = ShizukuShell.exec("settings get secure enabled_accessibility_services")
        val verifyItems = verify?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" }
            ?.split(":")
            ?.filter { it.isNotBlank() }
            .orEmpty()
        ShizukuShell.log(
            "写回后读回：${verifyItems.size} 项，" +
                "含本应用=${verifyItems.any { AdSkipPermission.componentMatches(it, context) }}",
        )

        return null
    }
}
