package org.dpdns.mfsky.skipad.adskip

import android.provider.Settings

/**
 * 权限自动获取任务 —— 本应用唯一一个不关广告的任务。
 *
 * 无障碍服务开启后，权限页与普通设置页没有区别（可读节点树 + 可点控件），
 * 所以「获取应用列表 / 后台弹出界面 / 显示悬浮窗」可以一次自动办完，
 * 且顺序与系统页面一致。
 *
 * ⚠️ 无障碍服务与「安装未知应用」必须用户手动：
 * · 前者是自动化的前提；
 * · 后者那一页的开关**不在无障碍树里**（ROM 把列表项标成 `noHideDescendants`，
 *   `flagIncludeNotImportantViews` 穿透不了），只能由用户去系统页拨一下。
 *   `uiautomator dump` 能看到它，别因此误判成匹配条件写错。
 *
 * ⚠️ 权限条目是**原地展开**的三选一（拒绝 / 仅在使用中允许 / 始终允许），
 * 选项是 `RadioButton` + `CheckedTextView id=title`，全程不启动新页面。
 * 行尾状态图标与选项文字都含"始终允许"，[APPROVE] 必须用 `className` 区分。
 */
internal object PermissionTasks {

    /**
     * 本应用包名，用于 `package:` 形式的 Intent data。
     *
     * ⚠️ **必须是占位符，不能写死** `org.dpdns.mfsky.skipad`：任务定义是静态数据
     *（见 [AdTask]，拿不到 Context），而 debug 变体带 `.debug` 后缀
     *（见 `app/build.gradle.kts` 的 `applicationIdSuffix`）—— 写死的后果是**假成功**：
     * debug 包会打开 release 包的应用信息页，页面照常显示、看着正常，授权却授到了另一个包。
     *
     * 替换发生在引擎侧（[AutomationRunner] 的 `resolveSelf`）。
     */
    private const val PKG = AutomationStep.SELF_PACKAGE

    /**
     * 权限选择列表里的「始终允许」。
     *
     * 两个候选覆盖 MIUI 上"外层吃点击、内层是文字"的经典布局 ——
     * 实测两者居同一个 `bounds`，点哪个都行。
     *
     * ⚠️ `className` 是**必须**的，理由见类文档最后一段。
     */
    private val APPROVE = listOf(
        Match(
            descContains = "始终允许",
            className = "android.widget.RadioButton",
        ),
        Match(
            text = "始终允许",
            viewIdContains = "title",
            className = "android.widget.CheckedTextView",
        ),
    )

    /**
     * 「其他权限」页上的一行。
     *
     * 实测这些行的标题节点 id 都是 `title`，配上文本即可精确定位；
     * 节点本身不可点，交给 [NodeClicker] 的三级回退向上找可点祖先。
     */
    private fun row(text: String) = Match(text = text, viewIdContains = "title")

    /** 任务的稳定标识 —— 它**不会**进处理进度，仅用于日志与排查。 */
    const val TASK_ID = "permission_grant"

    val task: AdTask = AdTask(
        id = TASK_ID,
        title = "自动获取权限",
        guide = "应用信息 → 权限管理 → 其他权限",
        // 整条路会经过 MIUI 安全中心与系统设置两个包，都要列全，
        // 否则 AutomationRunner 的"跑偏检测"会把正常步骤判成偏离路径。
        allowedPackages = listOf(
            "com.miui.securitycenter",
            "com.android.settings",
        ),
        steps = buildList<AutomationStep> {
            // ① 应用信息页。用 MIUI 安全中心那个（`ACTION_APPLICATION_DETAILS_SETTINGS`
            //    在本机被解析到安全中心，而不是 AOSP 设置），它有「权限管理」入口。
            add(
                AutomationStep.Launch(
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    data = "package:$PKG",
                    label = "打开应用信息页",
                ),
            )

            // ② 权限管理。跨应用打开页面可能慢，超时给足；
            //    页面没起来时重跑整个任务（重新 Launch 一次即可）。
            add(
                AutomationStep.AwaitFor(
                    row("权限管理"),
                    timeoutMs = 15_000L,
                    retryable = true,
                    label = "等待「权限管理」",
                ),
            )
            add(AutomationStep.Click(row("权限管理"), label = "点「权限管理」"))

            // ③ 其他权限
            add(AutomationStep.AwaitFor(row("其他权限"), label = "等待「其他权限」"))
            add(AutomationStep.Click(row("其他权限"), label = "点「其他权限」"))

            // ④ 获取应用列表 —— 点行 → 原地展开选项 → 选「始终允许」。
            //
            // ⚠️ 顺序跟着**系统页上的顺序**走："设置相关"分组下的
            //    「获取应用列表」在「后台弹出界面」「显示悬浮窗」**之前**，
            //    脚本按同一顺序处理，免得来回跳。
            //
            // 这一步对功能其实是**保险**：实测本机把它保持"拒绝"时，
            // 读取应用列表依然正常（真正的闸门是系统的 `QUERY_ALL_PACKAGES`，
            // 见 AdSkipPermission.isAppListAllowed）。
            // 但不同 ROM 版本的政策未必一致，而脚本此刻就站在这页上，
            // 顺路办掉几乎不花时间 —— 也省得用户看到系统页写着"拒绝"而来问。
            add(AutomationStep.AwaitFor(row("获取应用列表"), label = "等待「获取应用列表」"))
            add(AutomationStep.Click(row("获取应用列表"), label = "点「获取应用列表」"))
            add(AutomationStep.AwaitFor(APPROVE, label = "等待权限选项"))
            add(AutomationStep.Click(APPROVE, label = "选「始终允许」"))
            add(AutomationStep.PressBack())

            // ⑤ 后台弹出界面 —— 点行 → 原地展开选项 → 选「始终允许」。
            //    权限已经开着时这一步同样安全：选项本来就选中"始终允许"，
            //    再点一次不改变任何东西（幂等）。
            add(AutomationStep.AwaitFor(row("后台弹出界面"), label = "等待「后台弹出界面」"))
            add(AutomationStep.Click(row("后台弹出界面"), label = "点「后台弹出界面」"))
            add(AutomationStep.AwaitFor(APPROVE, label = "等待权限选项"))
            add(AutomationStep.Click(APPROVE, label = "选「始终允许」"))
            // ⚠️ 选完之后**必须按一次返回**：MIUI 的选项列表是**原地展开**的，
            //    并不会自己收起。不收起来的话，下一行的「显示悬浮窗」压根不在
            //    节点树里 —— 实测会一直等到超时，失败快照里页面上只剩
            //    `拒绝 / 始终允许` 两行，其余节点全被替换掉了。
            add(AutomationStep.PressBack())

            // ⑥ 显示悬浮窗 —— 与⑤同一页，选项收起后继续。
            add(AutomationStep.AwaitFor(row("显示悬浮窗"), label = "等待「显示悬浮窗」"))
            add(AutomationStep.Click(row("显示悬浮窗"), label = "点「显示悬浮窗」"))
            add(AutomationStep.AwaitFor(APPROVE, label = "等待权限选项"))
            add(AutomationStep.Click(APPROVE, label = "选「始终允许」"))
            // 同样收起选项列表，让页面回到干净的权限列表再跳走。
            add(AutomationStep.PressBack())
        },
    )
}
