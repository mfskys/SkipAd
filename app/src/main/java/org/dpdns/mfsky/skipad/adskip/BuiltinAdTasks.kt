package org.dpdns.mfsky.skipad.adskip

import android.content.Context

/**
 * # 内嵌任务脚本
 *
 * 这是**整个项目唯一需要维护的文件**。全部内容以 Kotlin 常量编译进包内，
 * 应用不需要网络权限、不读取任何外部文件。
 *
 * ## 一个任务 = 一串步骤
 *
 * ```
 * 打开设置 → 点搜索框 → 输入「广告」→ 点结果「系统广告」→ 等到详情页 → 关掉开关
 * ```
 *
 * 每一步都必须**等到上一步真的到位**才执行（靠 [AutomationStep.AwaitFor]），
 * 任何一步超时都会中止整个任务并如实上报 —— 绝不在用户手机上乱点。
 *
 * ## 每一步都写"多个候选"
 *
 * 匹配条件是一个**列表，按顺序尝试、任一命中即算命中**。这不是为了好看：
 * 同一个入口在不同 ROM / 系统版本上可能是完全不同的控件
 * （文字节点换成了图标、提示语不同、可点的是容器还是自身……）。
 *
 * **顺序有意义**：精确的条件写在前面，模糊的兜底写在后面 ——
 * 否则在符合预期的机型上会被模糊条件抢先命中，反而更容易出错。
 *
 * ## 维护约定
 *
 * 1. [AdTask.id] **一旦发布就不要改**：它是"已完成"的记录键。
 *    步骤内容真变了才换新 id，让用户能重新处理一次。
 * 2. **能靠文本就别靠 id**：系统设置的资源 id 在不同 ROM 上差异很大
 *    （本机实测搜索框 id 就叫 `input`，短得离谱），文本反而更稳。
 * 3. 每一步都**多写几个候选**。
 * 4. 用通用词做锚点时**必须**写 [Match.maxTextLength]。
 *
 * ## 实测结构（HyperOS / Android 17）
 *
 * 抓自 `uiautomator dump`，用来校准下面的规则：
 *
 * **设置首页**（`com.android.settings/.MiuiSettings`）
 * ```
 * TextView id=input  text="搜索系统设置项" desc="搜索" clickable=false  bounds=[204,593][1397,754]
 * ```
 * → 搜索框自身**不可点击**，要爬可点击祖先（[MatchTarget.ClickableAncestor] 已覆盖）。
 *
 * **搜索页**（同一 Activity，界面整体切换）
 * ```
 * EditText id=input  text="搜索系统设置项" clickable=true   bounds=[204,183][1191,344]
 * ```
 * → 进搜索页后同一个 id 变成了**可编辑、可点击**的 EditText，用来接收输入。
 *
 * **结果列表里有一对长得像的条目**（这是必须用精确匹配的原因）
 * ```
 * settings_search_item_name  text="系统广告"       ← 要点的
 * settings_search_item_path  text="设置系统广告"    ← 只是路径描述
 * ```
 *
 * **「系统广告」详情页**（实际属于 `com.xiaomi.account`，不是设置）
 * ```
 * Switch  desc="系统工具广告"  clickable=true  checkable=true  checked=true
 *         bounds=[44,824][1396,1029]     ← 整行都是它，自带描述且自己可点
 * TextView id=title  text="系统工具广告"
 * Switch  id=checkbox             bounds=[1159,868][1338,985]  ← 右侧那个小开关
 * ```
 */
/**
 * 小米画报关闭开关时的**确认弹窗**按钮。
 *
 * 实测「锁屏轮播」与「锁屏右滑」都会弹出一个结构完全相同的确认框：
 *
 * ```text
 * TextView text='关闭"锁屏轮播"？'                      id=alertTitle
 * TextView text='关闭后锁屏将无法推荐精美图片'            id=message
 * Button   text='取消'  id=button2
 * Button   text='关闭'  id=button1     ← 要点的这颗
 * ```
 *
 * ⚠️ **不点它，开关不会真的被关**：弹窗只是把那次点击拦下来等确认，
 *    开关保持 `checked=true`。而 [AutomationStep.ToggleOff] 的读回校验
 *    会一直读到"还没关"，最后超时判失败 —— 表现出来就是"点了没反应"。
 *
 * ⚠️ `viewIdContains = "button1"` 是**必须**的，不能只靠文本：
 *    弹窗标题里含"关闭"二字、页面上的开关行也叫「锁屏轮播」，
 *    只匹配文本 `"关闭"` 有多个候选。`Button` + `button1` 才能精确锁住那颗按钮。
 *
 * ⚠️ 而「早安画报」**没有**这个弹窗（实测点一下直接生效），
 *    所以它的 [AutomationStep.ToggleOff] 刻意不带 `confirm` ——
 *    给没有弹窗的开关硬加一个，会让脚本在那儿干等一个永远不出现的按钮。
 */
private val FASHIONGALLERY_CONFIRM_CLOSE = Match(
    text = "关闭",
    textMatch = MatchType.Exact,
    viewIdContains = "button1",
    className = "android.widget.Button",
)

/**
 * 「网页链接调用服务」—— **故意排在清单第 2 位**。
 *
 * 它和别的不一样：不是"某个应用的推荐位"，而是**系统设置深处的隐私开关**
 *（MIUI 用它把 App 里的链接直接交给对应服务打开，属于会打断用户的操作）。
 * 放在靠前的位置是因为用户想优先处理它。
 *
 * ## 曾经以为要 13 步，其实 5 步就够
 *
 * 用户手动走的那条路是：设置首页 → 滚动 →「隐私与安全」→「安全」tab
 * → 滚动 →「更多安全设置」→ 滚动 →「网页链接调用服务」。
 * 逐段实测后发现**中间两层各有更直接的入口**：
 *
 * | 层 | 结果 |
 * |---|---|
 * | `HttpInvokeAppSettings`（终点）| ✗ `not exported from uid 1000`，连 adb 都起不来 |
 * | `security.PrivacySafetyActivity`（安全中心那页）| ✓ 可直达，但**不需要**了 |
 * | 「更多安全设置」那页 | ✓ **`android.settings.SECURITY_SETTINGS` 这个标准 action 直接就到** |
 *
 * ⚠️ 那个 action 是 **AOSP 标准的**（不是 MIUI 私有扩展），
 *    所以本 ROM 上能用、别家 ROM 上大概率也能用 —— 这是首选而不是权宜之计。
 *
 * ⚠️ 「更多安全设置」对应的 Fragment 是
 *    `com.android.settings.security.SecuritySettings`（实测 `dumpsys activity` 拿到的），
 *    也可以用 `SubSettings --es :settings:show_fragment <它>` 直达。
 *    但既然有公开 action，就用 action —— 不必依赖内部类名。
 *
 * ## ⚠️ 终点页未导出，所以最后一段仍要走 UI
 *
 * 进来之后「网页链接调用服务」在页面**底部**（实测要滚好几屏才看得到），
 * 所以那一段必须挂 [AutomationStep.ScrollTo]。
 */
private val WebLinkInvokeTask = AdTask(
    id = "settings_web_link_invoke",
    title = "系统设置 · 网页链接调用服务",
    guide = "设置 → 隐私与安全 → 安全 → 更多安全设置 → 网页链接调用服务",
    allowedPackages = listOf("com.android.settings"),
    steps = listOf(
        // ① 直达「更多安全设置」页。
        //
        // ⚠️ 用 `android.settings.SECURITY_SETTINGS`（AOSP 标准 action）而不是
        //    从设置首页一层层点 —— 实测它能直接落到「安全情况 / 更多安全设置」那页。
        //
        // ⚠️ `clearTask` 用默认的 `true`：系统设置是**单 Activity 架构**
        //（首页、搜索页、各设置页全是 SubSettings + 不同 Fragment），
        // 设置已经停在别的子页面时，不带 CLEAR_TASK 只会把任务提到前台、
        // 界面还是那个子页面。
        AutomationStep.Launch(
            action = "android.settings.SECURITY_SETTINGS",
            label = "打开「更多安全设置」",
        ),

        // ② 等页面出来。判据取这页独有的两组条目名 ——
        //    别用「更多安全设置」：它同时是这页的**标题栏文字**，
        //    会和条目名撞上（本页确实有个同名的条目，那是另一个入口）。
        AutomationStep.AwaitFor(
            matches = listOf(
                Match(text = "安全更新", textMatch = MatchType.Exact),
                Match(text = "应用固定", textMatch = MatchType.Exact),
            ),
            timeoutMs = 15_000L,
            retryable = true,
            label = "等待「更多安全设置」页",
        ),

        // ③ 「网页链接调用服务」在页面**底部**，往下翻着找。
        AutomationStep.ScrollTo(
            match = Match(text = "网页链接调用服务", textMatch = MatchType.Exact),
            label = "滚动查找「网页链接调用服务」",
        ),
        AutomationStep.Click(
            match = Match(
                text = "网页链接调用服务",
                textMatch = MatchType.Exact,
                maxTextLength = 8,
                target = MatchTarget.ClickableAncestor,
            ),
            label = "点「网页链接调用服务」",
        ),

        // ④ 等终点页。
        //
        // ⚠️ 这里**必须**限定 `action_bar_title_expand`：上一步那个条目名
        //    和本页标题栏文字**完全一样**，不限定的话上一步的页面也能"命中"，
        //    于是会在还没跳过去时就通过、然后点到错的地方。
        AutomationStep.AwaitFor(
            match = Match(
                text = "网页链接调用服务",
                textMatch = MatchType.Exact,
                viewIdContains = "action_bar_title_expand",
            ),
            timeoutMs = 10_000L,
            retryable = true,
            label = "等待「网页链接调用服务」页",
        ),

        // ⑤ 关掉它。这页只有一个开关，外层整行 Switch 自带完整 desc。
        AutomationStep.ToggleOff(
            matches = listOf(
                Match(
                    descContains = "网页链接调用服务",
                    className = "android.widget.Switch",
                    target = MatchTarget.Self,
                ),
                // 兜底：desc 缺失时退回"标题锚点 → 同行开关"。
                Match(
                    text = "网页链接调用服务",
                    textMatch = MatchType.Exact,
                    maxTextLength = 8,
                    target = MatchTarget.SwitchInSameRow,
                ),
            ),
            label = "关闭「网页链接调用服务」",
        ),
    ),
)

/**
 * 「系统设置」里的三个隐私 / 广告开关。
 *
 * ## 为什么和第 2 位的 [WebLinkInvokeTask] 分开，而不是并成一个任务
 *
 * 这四项**都在 `android.settings.SECURITY_SETTINGS` 这一族页面里**，
 * 乍看"合并成一个任务、打开一次全办完"更省事 —— 但实测后否掉了：
 *
 * | 项 | 所在页面 | 能否直达 | 要不要滚动 |
 * |---|---|---|---|
 * | 加入"用户体验改进计划" | 「安全」页**首屏** | —（就在这页上） | ✗ |
 * | 使用情况和诊断数据 | `UsageAndDiagnosticsActivity` | ✔ 可直达 | ✗ |
 * | 广告服务 → 个性化广告推荐 | `ad.AdServiceSettings` | ✔ 可直达 | ✗ |
 * | 网页链接调用服务 | `HttpInvokeAppSettings` | ✗ **未导出** | ✔ 要滚到页底 |
 *
 * 前**三项完全不需要滚动**，第四项却必须滚到页底。合并的话任务逻辑会变成
 * "先在页首办三件、再滚到页底办一件"两段；而 [WebLinkInvokeTask] 已经跑通验证过，
 * 没有理由去动它。
 *
 * 拆开的代价只是**多打开一次「安全」页**（一次 Launch + 一次等待，约一秒），
 * 换来的是各项目标单一、失败时一眼能看出卡在哪一步。
 *
 * ## 三项为什么能这么省
 *
 * 前两项的终点页**没有 `intent-filter`，但 `exported=true`** —— 实测：
 *
 * ```text
 * am start -n com.android.settings/.UsageAndDiagnosticsActivity  → Starting ✔
 * am start -n com.android.settings/.ad.AdServiceSettings         → Starting ✔
 * am start -n com.android.settings/.HttpInvokeAppSettings        → ✗ Permission Denial
 *                                                                  (not exported from uid 1000)
 * ```
 *
 * 所以这两项直接 [AutomationStep.Launch] 过去，省掉"点条目 → 等子页 → 按返回"
 * 三步，也不必操心返回后的页面滚动位置。
 *
 * ⚠️ 拿不准时**不要凭直觉猜 `exported`**：`dumpsys package` 的 Activity Resolver
 *    表里查不到 ≠ 没导出（那只说明它没有 intent-filter）。
 *    最省事的判据就是拿 `am start -n` 试一次 —— 见 [AutomationStep.Launch] 的说明。
 *
 * ## 本机实测结构
 *
 * 「安全」页（`android.settings.SECURITY_SETTINGS`，标题栏文字是"安全"）首屏：
 *
 * ```text
 * TextView id=title  text='用户体验改进计划'                 ← 分组标题，占满整行
 * Switch  desc='加入"用户体验改进计划" 允许小米收集相关信息分析产品的使用情况，
 *               用以改善产品服务和提升用户体验。更多信息请查看用户体验改进计划'
 *         checked=true                                     ← ★ 要关的
 *   └ Switch id=checkbox checked=true
 * TextView id=title  text='使用情况和诊断数据'               ← 可点条目 → 独立页
 * TextView id=title  text='广告服务'                        ← 可点条目 → 独立页
 * ```
 *
 * `UsageAndDiagnosticsActivity`（整页只有这一个开关）：
 *
 * ```text
 * Switch desc='使用情况和诊断数据'  checked=true             ← ★ 要关的
 * ```
 *
 * `ad.AdServiceSettings`（整页只有这一个开关）：
 *
 * ```text
 * Switch desc='个性化广告推荐 个性化广告服务会根据您的喜好进行精准匹配。关闭后，
 *              广告数量不会因此减少，精准度会下降，建议您保持开启状态。'
 *         checked=true                                     ← ★ 要关的
 * ```
 */
private val PrivacySwitchesTask = AdTask(
    id = "settings_privacy_switches",
    title = "系统设置 · 隐私与广告开关",
    guide = "设置 → 安全 → 用户体验改进计划 / 使用情况和诊断数据 / 广告服务",
    allowedPackages = listOf("com.android.settings"),
    steps = listOf(
        // ① 打开「安全」页。
        //
        // ⚠️ 用 `android.settings.SECURITY_SETTINGS`（AOSP 标准 action）——
        //    和第 2 位那个任务同一个入口，那里记了为什么不去点设置首页。
        AutomationStep.Launch(
            action = "android.settings.SECURITY_SETTINGS",
            label = "打开「安全」页",
        ),

        // ② 等页面出来。判据沿用第 2 位任务里验证过的两条 ——
        //    「安全更新」「应用固定」是这页独有的条目名。
        AutomationStep.AwaitFor(
            matches = listOf(
                Match(text = "安全更新", textMatch = MatchType.Exact),
                Match(text = "应用固定", textMatch = MatchType.Exact),
            ),
            timeoutMs = 15_000L,
            retryable = true,
            label = "等待「安全」页",
        ),

        // ③ 关「加入"用户体验改进计划"」。
        //
        // ⚠️ 它在**首屏**（实测 y≈2175，屏高 3200），所以这一步不需要滚动。
        //
        // ⚠️ 匹配只取 desc 的**开头那两个字**：整条 desc 里"用户体验改进计划"
        //    出现了三次（标题一次、结尾"更多信息请查看…"又一次），
        //    只有最前面那一段带着"加入"。`descContains = "加入"` 既短又唯一。
        AutomationStep.ToggleOff(
            matches = listOf(
                Match(
                    descContains = "加入",
                    className = "android.widget.Switch",
                    target = MatchTarget.Self,
                ),
                // 兜底：desc 缺失时退回"标题锚点 → 同行开关"。
                // ⚠️ 标题里的引号是**中文全角引号**，必须原样保留。
                Match(
                    text = "加入“用户体验改进计划”",
                    textMatch = MatchType.Exact,
                    target = MatchTarget.SwitchInSameRow,
                ),
            ),
            label = "关闭「加入用户体验改进计划」",
        ),

        // ④ 直达「使用情况和诊断数据」页 —— 见类文档，这个组件导出着，不用点进去。
        AutomationStep.Launch(
            component = "com.android.settings/.UsageAndDiagnosticsActivity",
            label = "打开「使用情况和诊断数据」",
        ),

        // ⑤ 等子页，再关掉它唯一那个开关。
        //
        // ⚠️ 锚点必须取**外层 Switch 的 desc**，不能取 `text='使用情况和诊断数据'`：
        //    后者在上一页（「安全」页）也有一模一样的一条（那是"点进去"的入口），
        //    拿它当"子页到了"的判据，会在还没跳过去时就通过。
        //    加上 `className = Switch` 才能把两者分开。
        AutomationStep.AwaitFor(
            match = Match(
                descContains = "使用情况和诊断数据",
                className = "android.widget.Switch",
            ),
            timeoutMs = 10_000L,
            retryable = true,
            label = "等待「使用情况和诊断数据」页",
        ),
        AutomationStep.ToggleOff(
            matches = listOf(
                Match(
                    descContains = "使用情况和诊断数据",
                    className = "android.widget.Switch",
                    target = MatchTarget.Self,
                ),
                Match(
                    text = "使用情况和诊断数据",
                    textMatch = MatchType.Exact,
                    maxTextLength = 9,
                    target = MatchTarget.SwitchInSameRow,
                ),
            ),
            label = "关闭「使用情况和诊断数据」",
        ),

        // ⑥ 直达「广告服务」页。
        //
        // ⚠️ **页面名和开关名不是一回事**：这一页标题是「广告服务」，
        //    而里面那个开关叫「个性化广告推荐」。别照着页面名去找开关。
        AutomationStep.Launch(
            component = "com.android.settings/.ad.AdServiceSettings",
            label = "打开「广告服务」",
        ),

        // ⑦ 等子页，再关掉它唯一那个开关。
        //
        // ⚠️ 匹配词只能取「个性化广告推荐」，**不能**取 desc 里的"关闭后…"——
        //    那段说明文字里本来就有"关闭"二字（"关闭后，广告数量不会因此减少"），
        //    拿它当锚点等于命中自己这行的说明，既没信息量又容易撞上别的开关。
        AutomationStep.AwaitFor(
            match = Match(
                descContains = "个性化广告推荐",
                className = "android.widget.Switch",
            ),
            timeoutMs = 10_000L,
            retryable = true,
            label = "等待「广告服务」页",
        ),
        AutomationStep.ToggleOff(
            matches = listOf(
                Match(
                    descContains = "个性化广告推荐",
                    className = "android.widget.Switch",
                    target = MatchTarget.Self,
                ),
                Match(
                    text = "个性化广告推荐",
                    textMatch = MatchType.Exact,
                    maxTextLength = 7,
                    target = MatchTarget.SwitchInSameRow,
                ),
            ),
            label = "关闭「个性化广告推荐」",
        ),
    ),
)

internal val BuiltinAdTasks: List<AdTask> = listOf(

    // =========================================================================
    // 1. 系统广告 · 系统工具广告
    // =========================================================================
    //
    // ## 为什么走「账号」而不走「搜索」
    //
    // 原先这条路径是：设置首页 → 点搜索框 → 输入「广告」→ 点结果。
    // 它的麻烦全在**搜索链路本身**：
    //   · 查询逻辑挂在 IME 上，必须先用真实触摸唤醒软键盘（`ACTION_CLICK` 唤不醒）；
    //   · 首页搜索入口与搜索页输入框**同 id、同占位文字**，得靠"是否持焦"区分；
    //   · 结果列表异步渲染，`Contains` 会命中"设置系统广告"那条路径描述，必须精确匹配；
    //   · 写入后要读回比对、超时还要原地重发输入……
    //
    // 换成**设置首页顶部的账号入口**后，整条链路变成清一色的"点文本" ——
    // 没有输入、没有 IME、没有网络等待，每步判据都是静态文本。代价只是多点两下。
    //
    // ⚠️ `AdTask.id` 跟着换了（加了 `_v2`）：步骤内容整个变了，
    //    按"id 是已完成记录键"的约定换 id，让这一项重新回到待处理。
    // =========================================================================
    AdTask(
        id = "mi_system_tool_ad_v2",
        title = "系统工具广告",
        guide = "设置 → 账号 → 关于小米账号 → 系统广告",
        // 防呆判据：这条路径上会出现的所有包。
        // ⚠️ `com.xiaomi.account` 必须列进来 —— 本机实测「系统广告」详情页
        //    属于它而不是设置。漏掉的话，脚本一进详情页就会被判成"走偏"而反复重跑。
        allowedPackages = listOf(
            "com.android.settings",
            "com.xiaomi.account",
        ),
        steps = listOf(
            // ① 打开系统设置。用**公开** action，跨 ROM 最稳。
            AutomationStep.Launch(
                action = "android.settings.SETTINGS",
                label = "打开系统设置",
            ),

            // ② 等设置首页出来。锚点取搜索框的提示文字，
            //    它比"WLAN / 蓝牙"这些条目更稳定（各 ROM 都会有个搜索入口）。
            AutomationStep.AwaitFor(
                matches = listOf(
                    // 主选：提示文字，配长度上限防误点。
                    Match(
                        text = "搜索系统设置项",
                        textMatch = MatchType.Contains,
                        maxTextLength = 20,
                    ),
                    // 兜底：提示语可能是别的写法（如「搜索设置项」），
                    // 退化成"只要有个搜索输入框就算首页出来了"。
                    Match(
                        text = "搜索",
                        textMatch = MatchType.Contains,
                        maxTextLength = 8,
                    ),
                ),
                // 允许重跑：设置万一停在了子页面（首页锚点出不来），
                // 重跑会带着 CLEAR_TASK 再启动一次，通常就回到首页了。
                retryable = true,
                label = "等待设置首页",
            ),

            // ③ 点顶部的账号入口。
            //
            // ⚠️ 锚点是**固定的副标题文案**「账号、云服务、会员权益等」，
            //    不能锚定同一行上方那个用户名 —— 它因人而异（本机是账号昵称）。
            //    该文本节点自身 `clickable=false`（实测 `android:id/summary`），
            //    所以要爬可点击祖先。
            AutomationStep.Click(
                matches = listOf(
                    // 主选：完整文案。
                    Match(
                        text = "账号、云服务、会员权益等",
                        textMatch = MatchType.Contains,
                        maxTextLength = 20,
                        target = MatchTarget.ClickableAncestor,
                    ),
                    // 兜底：文案可能微调（少个「等」字之类）。
                    Match(
                        text = "账号、云服务",
                        textMatch = MatchType.Contains,
                        maxTextLength = 20,
                        target = MatchTarget.ClickableAncestor,
                    ),
                ),
                label = "点账号入口",
            ),

            // ④ 等小米账号页出来。
            //
            // 判据用页面里的「关于小米账号」条目 —— 不用账号名（因人而异），
            // 也不用页面标题（账号页标题里往往就带用户名）。
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(
                        text = "关于小米账号",
                        textMatch = MatchType.Contains,
                        maxTextLength = 12,
                    ),
                    // 兜底：文案不同（如「关于账号」）时，退化成"页面上有个『关于…』条目"。
                    Match(
                        text = "关于",
                        textMatch = MatchType.Prefix,
                        maxTextLength = 12,
                    ),
                ),
                retryable = true,
                label = "等待小米账号页",
            ),

            // ⑤ 点「关于小米账号」。
            AutomationStep.Click(
                match = Match(
                    text = "关于小米账号",
                    textMatch = MatchType.Contains,
                    maxTextLength = 12,
                    target = MatchTarget.ClickableAncestor,
                ),
                label = "点「关于小米账号」",
            ),

            // ⑥ 等「系统广告」条目出现。
            //
            // ⚠️ 仍然要 `Exact` + 长度上限：这一页里同样可能存在
            //    "设置系统广告"这类路径描述，`Contains` 会把它一起命中 ——
            //    搜索路径踩过同一个坑（提前通过 → 点到正在重建的节点 → 卡住）。
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(text = "系统广告", textMatch = MatchType.Exact),
                    Match(text = "系统广告", textMatch = MatchType.Contains, maxTextLength = 5),
                ),
                // 不再给 12 秒：这一页是**本地渲染**（不走网络），6 秒足够。
                // 也不再需要 `retryPreviousInput` —— 它只对"紧跟在 Input 后面的等待"有意义。
                timeoutMs = 6_000L,
                retryable = true,
                label = "等待「系统广告」条目",
            ),

            // ⑦ 点「系统广告」进入详情页。
            AutomationStep.Click(
                matches = listOf(
                    // 主选：精确文本 + 爬可点击祖先。
                    Match(
                        text = "系统广告",
                        textMatch = MatchType.Exact,
                        target = MatchTarget.ClickableAncestor,
                    ),
                    // 兜底：长度上限排除"设置系统广告"那条路径描述（它 6 个字）。
                    Match(
                        text = "系统广告",
                        textMatch = MatchType.Contains,
                        maxTextLength = 5,
                        target = MatchTarget.ClickableAncestor,
                    ),
                ),
                label = "点「系统广告」",
            ),

            // ⑧ 等详情页里的目标开关出现。
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(text = "系统工具广告", textMatch = MatchType.Contains, maxTextLength = 12),
                    // 兜底：标题文本读不到（被截断/异体字）时，
                    // 靠开关自己的 contentDescription 也能确认页面到了。
                    Match(descContains = "系统工具广告"),
                ),
                // 也允许重跑：万一第 ⑦ 步点歪了（碰到那条路径描述），
                // 从头再来一次通常就能点对，比直接判失败友好。
                retryable = true,
                label = "等待广告设置页",
            ),

            // ⑨ 关掉它。幂等：已经是关的就判定成功且不点击；点完还会**读回状态验证**。
            //
            // ⚠️ 用 contentDescription + Switch 自身，而不是"锚点文本 + 找同行开关"。
            //    本机实测这一页的结构：
            //      Switch  desc="系统工具广告"  clickable=true  checkable=true  checked=true
            //              bounds=[44,824][1396,1029]     ← 整行都是它，自带描述且自己可点
            //      Switch  id=checkbox             bounds=[1159,868][1338,985]  ← 右侧那个小开关
            //    直接命中大 Switch 最省事，也避开了"同行多个 Switch 到底点哪个"的歧义。
            AutomationStep.ToggleOff(
                matches = listOf(
                    // 主选：整行的大 Switch（自带描述、自己可点）。
                    Match(
                        descContains = "系统工具广告",
                        className = "android.widget.Switch",
                        target = MatchTarget.Self,
                        onlyWhenChecked = true,
                    ),
                    // 兜底：别的 ROM 可能不带描述，那就回到
                    // "按标题文本找同一行的开关"这条经典路线。
                    Match(
                        text = "系统工具广告",
                        textMatch = MatchType.Contains,
                        maxTextLength = 12,
                        target = MatchTarget.SwitchInSameRow,
                        onlyWhenChecked = true,
                    ),
                ),
                label = "关闭「系统工具广告」",
            ),
        ),
    ),

    // -------------------------------------------------------------------------
    // 系统设置 · 网页链接调用服务　★ 用户要求排在**第 2 位**
    // -------------------------------------------------------------------------
    // 任务本身定义在文件开头（[WebLinkInvokeTask]），那里写了完整的路径
    // 与实测依据。这里只负责把它放进清单的第二位 ——
    // 用户想优先处理它，所以不要挪到后面去。
    WebLinkInvokeTask,

    // -------------------------------------------------------------------------
    // 系统设置 · 隐私与广告开关
    // -------------------------------------------------------------------------
    // 紧挨着第 2 位放，因为**同属"系统设置"这一族**：它们打开的是同一族页面
    // （`android.settings.SECURITY_SETTINGS`），跑的时候设置页不用来回切应用。
    //
    // ⚠️ 但它**不能**并进上面那个任务 —— 理由写在 [PrivacySwitchesTask] 的文档里：
    //    这三项都不需要滚动（一项在首屏、两项可直达），而"网页链接调用服务"
    //    必须滚到页底，两者形态不同；且后者已经跑通验证过，没有必要去动。
    PrivacySwitchesTask,

    // -------------------------------------------------------------------------
    // 下载管理 · 信息流广告
    // -------------------------------------------------------------------------
    AdTask(
        id = "download_manager_infoflow",
        title = "下载管理 · 信息流",
        guide = "下载管理 → 设置 → 信息流设置 → 关闭「资源推荐」「热榜推荐」",
        allowedPackages = listOf("com.android.providers.downloads.ui"),
        steps = listOf(
            // ① 直达信息流设置页。
            //
            // 该页**没有 intent-filter**（不出现在 `dumpsys package` 的
            // Activity Resolver Table 里），隐式 action 无从谈起 ——
            // 但 APK 清单里声明了 `exported=true`（已用 aapt2 逐项核对），
            // 于是可以用**显式组件**一步到位：省掉"进设置页 → 点「信息流设置」"
            // 两步操作，也省掉两次误点风险。
            AutomationStep.Launch(
                component = "com.android.providers.downloads.ui/.activity.InfoFlowSettingActivity",
                // ⚠️ 必须显式 `clearTask = false`。
                //
                // 这是**跨应用**启动（下载管理有自己的任务栈），而我们此前统一定的
                // 「设置类页面一律带 CLEAR_TASK」那条规则，在这里会让启动**卡住不返回**：
                // 既不成功也不失败，日志停在"打开信息流设置"，整个会话就此僵死 ——
                // 实测稳定复现（连下一步的 [2/4] 都出不来）。
                //
                // 道理上也说得通：那是**别人的任务**，我们没有理由去清空它。
                clearTask = false,
                label = "打开信息流设置",
            ),

            // ②【可选】清掉上一次中断可能留下的确认弹窗。
            //
            // ⚠️ 为什么需要它：如果上次任务是在"弹窗刚出现"时中断的（被停止 / 失败），
            //    这个弹窗会**留在屏幕上盖住整个页面** —— 下次进来时连「资源推荐」
            //    都找不到，整项直接超时（实测踩到过）。
            //
            //    这是"有则处理、无则忽略"的动作，所以标 `optional = true`：
            //    1.5 秒内没出现弹窗就跳过，**不算失败**。
            //
            //    点「取消」而不是「确定」：这一步只为**清场**，
            //    真正要关的开关交给后面两步，让它们走完整的"点击 → 验证"流程。
            AutomationStep.Click(
                matches = listOf(
                    Match(text = "取消", textMatch = MatchType.Exact, viewIdContains = "button2"),
                    Match(text = "确定", textMatch = MatchType.Exact, viewIdContains = "button1"),
                ),
                optional = true,
                label = "清掉残留弹窗（可选）",
            ),

            // ③ 等页面就绪。
            //
            // 判据用**目标开关本身**，而不是页面标题：标题可能在窗口过渡动画里
            // 提前出现，而开关真的挂上去才代表可以动手了。
            // `retryable` 让"启动被系统拦住 / 页面没起来"能整项重跑一次。
            AutomationStep.AwaitFor(
                match = Match(
                    descContains = "资源推荐",
                    className = "android.widget.Switch",
                ),
                // ⚠️ 超时必须给足：这里是**跨应用冷启动**（下载管理进程可能没起），
                //    实测等过 **29 秒**才把「资源推荐」这一行渲染出来。
                //
                //    用默认的 5 秒会刚超时就判失败、触发整项重跑，而重跑要
                //    **重新启动一次页面** —— 于是用户看到的是长达半分钟的
                //    "什么都没发生"（实测反馈原话："还是没有去点击啊"）。
                //
                //    配套的"仍在等待…（已等 N 秒）"日志让长超时也是可见的，
                //    不会像卡死。见 AutomationRunner 的 WAIT_LOG_INTERVAL_MS。
                timeoutMs = 30_000L,
                retryable = true,
                label = "等待信息流设置页",
            ),

            // ④⑤ 关掉两个推荐开关。
            //
            // ⚠️ 与「系统工具广告」同一套路：直接命中**整行的大 Switch**
            //    （`desc` = 标题，`clickable` / `checkable` 都为 true），
            //    而不是去找"右侧那个小开关"。本机实测这一页的结构：
            //      Switch  desc="资源推荐"  checked=true  checkable=true  clickable=true
            //              bounds=[44,800][1396,1005]     ← 整行都是它
            //      Switch  id=checkbox               bounds=[1159,844][1338,961] ← 右侧小开关
            //    `ToggleOff` 自带**幂等**（本来就是关的 → 直接算成功、不点击）
            //    与**结果验证**（点完读回 isChecked，真的变成 false 才算完成）。
            AutomationStep.ToggleOff(
                match = Match(
                    descContains = "资源推荐",
                    className = "android.widget.Switch",
                ),
                // ⚠️ 实测：关「资源推荐」会弹一个确认框，**不点掉它开关不会真的变**；
                //    而同一页的「热榜推荐」是直接生效的 —— 只能逐个声明，不能一刀切。
                confirm = Match(
                    text = "确定",
                    textMatch = MatchType.Contains,
                    maxTextLength = 4,
                ),
                label = "关闭「资源推荐」",
            ),
            AutomationStep.ToggleOff(
                match = Match(
                    descContains = "热榜推荐",
                    className = "android.widget.Switch",
                ),
                label = "关闭「热榜推荐」",
            ),
        ),
    ),

    // -------------------------------------------------------------------------
    // 应用包管理组件 · 资源推荐
    // -------------------------------------------------------------------------
    AdTask(
        id = "package_installer_resource_recommend",
        title = "应用包管理组件 · 资源推荐",
        guide = "安装界面 → 右上角设置 → 关闭「资源推荐」",
        allowedPackages = listOf("com.miui.packageinstaller"),
        steps = listOf(
            // ① 唤起安装界面 —— 拿**本应用自己的 APK** 当"门票"。
            //
            // ## 为什么必须这么绕
            //
            // 设置页 `com.android.packageinstaller.SettingsActivity` **没有导出**，
            // 直接启动会被系统拒绝（实测连 `adb shell` 都不行）：
            //
            //     Permission Denial: ... not exported from uid 10112
            //
            // 它**唯一的入口**是安装界面右上角的「设置」按钮，所以要进那个页面，
            // 就得先唤起一次安装界面 —— 也就必须交给安装器一个 APK。
            // 用本应用自己的 APK 最省事：不需要用户准备任何文件。
            //
            // ⚠️ 副作用（已知且接受）：跑这一项时用户会看到安装界面，
            //    它显示"本应用 版本 1.0 -> 1.0"。**千万不要点「安装」** ——
            //    本任务只是借道，第 ③ 步就点右上角设置走掉了。
            //
            // ⚠️ `clearTask = false`：跨应用启动，带 CLEAR_TASK 会卡住不返回
            //    （「信息流设置」那项实测踩过）。
            AutomationStep.Launch(
                // action + data + mimeType 这三样是**一起验证过的组合**：
                // `adb shell am start -a android.intent.action.VIEW
                //  -d file:///…apk -t application/vnd.android.package-archive`
                // 能稳定打开安装界面。App 内走同一条路，唯一区别是 data 由
                // `SELF_APK_DATA` 现取（见 buildLaunchIntent）。
                action = "android.intent.action.VIEW",
                data = AutomationStep.SELF_APK_DATA,
                // ⚠️ 必须带 type：目标是"以查看方式打开一个包文件"，
                //    系统靠它把请求路由到安装器（少了它无人认领，报 ActivityNotFound）。
                mimeType = "application/vnd.android.package-archive",
                clearTask = false,
                label = "打开安装界面",
            ),

            // ②【可选】首次运行时系统会问"是否允许安装未知应用"。
            //
            // 先勾"记住我的选择"、再点「允许」，这样只会弹这一次。
            // 两个都是可选步骤：已经授权过就不会出现，1.5 秒内没命中即跳过。
            //
            // ⚠️ **必须限定控件类**：这条路径的第一步只是"唤起"安装界面，
            //    此时前台可能还停在**本应用**（安装界面尚未渲染出来），
            //    而本应用主页的日志卡片里正好有「记住我的选择」这几个字 ——
            //    实测被它命中过（日志里那句"候选#1 **自命中**"点的就是它）。
            //    限定 `CheckBox` / `Button` 之后就不会误点自己的界面。
            AutomationStep.Click(
                matches = listOf(
                    Match(
                        text = "记住我的选择",
                        textMatch = MatchType.Contains,
                        className = "android.widget.CheckBox",
                    ),
                ),
                optional = true,
                label = "勾选「记住我的选择」（如出现）",
            ),
            AutomationStep.Click(
                matches = listOf(
                    Match(
                        text = "允许",
                        textMatch = MatchType.Exact,
                        className = "android.widget.Button",
                    ),
                ),
                optional = true,
                label = "放行「允许安装」（如出现）",
            ),

            // ③ 等安装界面就绪。
            //
            // 判据是**右上角的「设置」按钮**（本机实测：`desc="设置"`、
            // `class=android.widget.Button`、没有 id）。
            // ⚠️ 必须限定 `Button` —— 这个界面上的标题文字也是"设置"，
            //    只在描述上匹配会命中标题（那是 TextView，点不动）。
            AutomationStep.AwaitFor(
                match = Match(
                    descContains = "设置",
                    className = "android.widget.Button",
                ),
                timeoutMs = 30_000L,
                retryable = true,
                label = "等待安装界面",
            ),

            // ④ 点右上角「设置」，进入安装设置页。
            AutomationStep.Click(
                matches = listOf(
                    Match(
                        descContains = "设置",
                        className = "android.widget.Button",
                    ),
                ),
                label = "点右上角「设置」",
            ),

            // ⑤ 等设置页。
            //
            // 判据用**目标开关本身**而不是页面标题：标题可能在窗口过渡动画里
            // 提前出现，而开关真的挂上去才代表可以动手了。
            AutomationStep.AwaitFor(
                match = Match(
                    descContains = "资源推荐",
                    className = "android.widget.Switch",
                ),
                timeoutMs = 30_000L,
                retryable = true,
                label = "等待安装设置页",
            ),

            // ⑥ 关掉它。
            //
            // ⚠️ 与「系统工具广告」同一套路：直接命中**整行的大 Switch**
            //    （`desc` = 标题，`clickable` / `checkable` 都为 true），
            //    而不是去找右边那个小开关。本机实测这一页的结构：
            //
            //      Switch  desc="资源推荐"  checkable=true  clickable=true
            //              bounds=[44,580][1396,785]        ← 整行都是它
            //      Switch  id=checkbox              bounds=[1159,624][1338,741] ← 右边小开关
            //      TextView text="资源推荐" id=android:id/title
            //
            //    `ToggleOff` 自带**幂等**（本来就是关的 → 直接算成功、不点击）
            //    与**结果验证**（点完读回 `isChecked`，真的变成 false 才算完成）。
            AutomationStep.ToggleOff(
                match = Match(
                    descContains = "资源推荐",
                    className = "android.widget.Switch",
                ),
                label = "关闭「资源推荐」",
            ),
        ),
    ),

    // -------------------------------------------------------------------------
    // 垃圾清理 · 推荐内容
    // -------------------------------------------------------------------------
    //
    // 本页结构（`uiautomator dump` 实测，`com.miui.cleanmaster` 的
    // `com.miui.optimizecenter.settings.SettingsActivity`）：
    //
    // ```
    // TextView text='推荐内容' id=title          clickable=false   ← 只是标题，点它没用
    // Switch   desc='推荐内容'                    clickable=true checkable=true checked=false
    // Switch   desc='仅在WLAN下推荐'              ← 干扰项
    // Switch   desc='扫描内存' / '使用云端扫描' / '自动更新清理库'   ← 都是干扰项
    // ```
    //
    // → 所以**必须**用 `content-desc` + `className = Switch` 定位，
    //   而不能只按文本（文本节点不可点击）。
    AdTask(
        id = "cleanmaster_recommend",
        title = "垃圾清理 · 推荐内容",
        guide = "垃圾清理 → 设置 → 推荐内容",
        allowedPackages = listOf("com.miui.cleanmaster"),
        steps = listOf(
            // ① 直达设置页。
            //
            // 实测 `am start -n` 能被接受（该页 `exported=true`，不是 not exported），
            // 所以用**显式组件**一步到位 —— 省掉"先进主页、再找「设置」入口"两步，
            // 也省掉两次误点风险。
            //
            // ⚠️ 必须 `clearTask = false`。
            //
            // 这是**跨应用**启动（垃圾清理有自己的任务栈），而我们平时给"设置类页面"
            // 定的规矩是一律带 CLEAR_TASK。那条规矩在这里会让启动**卡住不返回**：
            // 既不成功也不失败，日志停在"打开垃圾清理设置"，整个会话就此僵死 ——
            // 下载管理·信息流那一项踩过一模一样的坑。
            AutomationStep.Launch(
                component = "com.miui.cleanmaster/com.miui.optimizecenter.settings.SettingsActivity",
                clearTask = false,
                label = "打开垃圾清理设置",
            ),

            // ② 等「推荐内容」这个开关出现（它就是设置页加载完成的标志）。
            AutomationStep.AwaitFor(
                match = Match(
                    descContains = "推荐内容",
                    className = "android.widget.Switch",
                ),
                label = "等待设置页",
            ),

            // ③ 关掉它。幂等：本来就是关的直接算成功；点完会读回 `checked` 验证。
            //
            // ⚠️ 这里 `descContains = "推荐内容"` 已经足够精确 ——
            //    「仅在WLAN下推荐」不含"推荐内容"这四个字，不会被误命中，
            //    所以不需要再加长度上限。
            AutomationStep.ToggleOff(
                matches = listOf(
                    // 主选：整行的 Switch，自带描述、自己可点。
                    Match(
                        descContains = "推荐内容",
                        className = "android.widget.Switch",
                        target = MatchTarget.Self,
                        onlyWhenChecked = true,
                    ),
                    // 兜底：描述缺失时退回"按标题文本找同一行开关"。
                    Match(
                        text = "推荐内容",
                        textMatch = MatchType.Exact,
                        maxTextLength = 6,
                        target = MatchTarget.SwitchInSameRow,
                        onlyWhenChecked = true,
                    ),
                ),
                label = "关闭「推荐内容」",
            ),
        ),
    ),

    // -------------------------------------------------------------------------
    // 天气 · 内容推广
    // -------------------------------------------------------------------------
    //
    // 这一项的**路径要四步才到目标页**，全部结构取自 `uiautomator dump` 实测
    // （`com.miui.weather2`，页面都是 `ActivityWeatherMain` —— 它的各级"页"其实是
    //  同一个 Activity 里的浮层，所以没有任何独立的 Activity 可以直达）：
    //
    // ```
    // ① 天气主页          Button desc='城市管理'          bounds=[88,2920][249,3081]
    // ② 城市管理页        Button desc='更多设置'          bounds=[1235,191][1396,352]
    // ③ 弹出菜单          View   desc='设置'              bounds=[693,359][1367,521]
    //                     View   desc='反馈天气'          ← 干扰项
    // ④ 设置页            View   desc='用户体验计划'       bounds=[44,3000][1396,3111]
    // ⑤ 用户体验计划页    Switch desc='内容推广'           ← 目标
    //                     Switch desc='天气视频卡片'       ← 干扰项
    // ```
    //
    // ⚠️ 全程只能用 `content-desc`：这一整条路径上**没有可用的 resource-id**
    //    （dump 里 `resource-id` 全为空），但描述文字给得很完整。
    AdTask(
        id = "weather_content_promotion",
        title = "天气 · 内容推广",
        guide = "天气 → 城市管理 → 更多设置 → 设置 → 用户体验计划",
        allowedPackages = listOf("com.miui.weather2"),
        steps = listOf(
            // ① 打开天气。它有正常的 launcher 入口，所以用最宽容的
            //    `appPackage` 方式（等价于点桌面图标），不走显式组件。
            AutomationStep.Launch(
                appPackage = "com.miui.weather2",
                label = "打开天气",
            ),

            // ② 等主页加载出来 + 点「城市管理」（左下角那个按钮）。
            //
            // 用户口头把它叫"更多按钮"，但它的 `content-desc` 其实是"城市管理" ——
            // 所以这里按**真实描述**匹配，不要按口头叫法写。
            AutomationStep.Click(
                matches = listOf(
                    Match(descContains = "城市管理", className = "android.widget.Button"),
                    Match(descContains = "城市管理"),
                ),
                label = "点「城市管理」",
            ),

            // ③ 城市管理页右上角的「更多设置」—— 它就是真正的"更多"。
            AutomationStep.Click(
                matches = listOf(
                    Match(descContains = "更多设置", className = "android.widget.Button"),
                    Match(descContains = "更多设置"),
                ),
                label = "点「更多设置」",
            ),

            // ④ 弹出的菜单里点「设置」。
            //
            // ⚠️ 这里 `descContains = "设置"` 之所以安全，是因为**菜单弹出后
            //    "更多设置"那个按钮已经不在树里了**（dump 实测：菜单里只有
            //    "反馈天气"和"设置"两项）。若哪天菜单改成覆盖式、旧按钮仍在，
            //    这里会误命中"更多设置"—— 到时要靠 `maxTextLength` 之类再收紧。
            AutomationStep.Click(
                matches = listOf(
                    Match(descContains = "设置", className = "android.view.View"),
                    Match(descContains = "设置"),
                ),
                label = "点「设置」",
            ),

            // ⑤ 设置页最下面的「用户体验计划」入口。
            AutomationStep.Click(
                match = Match(descContains = "用户体验计划"),
                label = "点「用户体验计划」",
            ),

            // ⑥ 关掉「内容推广」。幂等 + 读回 `checked` 验证。
            //
            // 干扰项「天气视频卡片」与它词完全不同，`descContains` 不会误命中。
            AutomationStep.ToggleOff(
                matches = listOf(
                    Match(
                        descContains = "内容推广",
                        className = "android.widget.Switch",
                        target = MatchTarget.Self,
                        onlyWhenChecked = true,
                    ),
                    Match(
                        text = "内容推广",
                        textMatch = MatchType.Exact,
                        maxTextLength = 6,
                        target = MatchTarget.SwitchInSameRow,
                        onlyWhenChecked = true,
                    ),
                ),
                label = "关闭「内容推广」",
            ),
        ),
    ),

    // -------------------------------------------------------------------------
    // 日历 · 内容推广
    // -------------------------------------------------------------------------
    //
    // 这一项**可以一步直达**，是三者里最省事的一个 —— 因为小米日历把
    // 「用户体验计划」做成了**独立 Activity**（不像天气那样藏在同一 Activity 的浮层里）：
    //
    // ```
    // com.android.calendar/.settings.UserExperienceActionBarActivity
    //   Switch desc='内容推广'  clickable=true checkable=true
    //   TextView text='内容推广' id=title  ← 只是标题，不可点击
    // ```
    //
    // 所以不必走"主页 → 更多 → 设置 → 滚到底"那四步 ——
    // 直接开页，然后关开关，两步完事。
    AdTask(
        id = "calendar_content_promotion",
        title = "日历 · 内容推广",
        guide = "日历 → 设置 → 用户体验计划 → 内容推广",
        allowedPackages = listOf("com.android.calendar"),
        steps = listOf(
            // ① 直达「用户体验计划」页。
            //
            // ⚠️ `clearTask = false`：这是**跨应用**启动（日历有自己的任务栈），
            //    带 CLEAR_TASK 会让 startActivity 卡住不返回（垃圾清理、下载管理
            //    两项都踩过这个坑）。
            AutomationStep.Launch(
                component = "com.android.calendar/com.android.calendar.settings" +
                    ".UserExperienceActionBarActivity",
                clearTask = false,
                label = "打开用户体验计划",
            ),

            // ② 等页面加载出来。
            //    文本和描述两条路都写上：这页两个都有，谁先就绪都能过。
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(text = "内容推广", textMatch = MatchType.Exact),
                    Match(descContains = "内容推广"),
                ),
                label = "等待页面",
            ),

            // ③ 关掉「内容推广」。幂等 + 读回 `checked` 验证。
            AutomationStep.ToggleOff(
                matches = listOf(
                    // 主选：整行的 Switch，自带描述、自己可点。
                    Match(
                        descContains = "内容推广",
                        className = "android.widget.Switch",
                        target = MatchTarget.Self,
                        onlyWhenChecked = true,
                    ),
                    // 兜底：描述缺失时退回"按标题文本找同一行开关"。
                    Match(
                        text = "内容推广",
                        textMatch = MatchType.Exact,
                        maxTextLength = 4,
                        target = MatchTarget.SwitchInSameRow,
                        onlyWhenChecked = true,
                    ),
                ),
                label = "关闭「内容推广」",
            ),
        ),
    ),

    // -------------------------------------------------------------------------
    // 应用商店 · 功能推荐位（导航栏 / 游戏服务 / 福利活动）
    // -------------------------------------------------------------------------
    //
    // ⚠️ 这一页的开关个数**随应用商店版本差别很大**，脚本必须两种都吃下：
    //
    // | 版本 | 这一页有几个开关 | 要关哪几个 |
    // | --- | --- | --- |
    // | 旧版（内置脚本原本适配的） | **2** | 显示福利活动 / 显示游戏抽奖 |
    // | **新版（现测机型，2026-10-14）** | **13** | 除下面那 6 个"功能项"外全部 |
    //
    // **新版实测结构**（`FunctionSettingsFragmentActivity`，滚到顶部后 dump）：
    //
    // ```text
    // 【导航栏管理】
    //   Switch desc='视频专区 关闭后，将为您替换为榜单专区'                    checked=true
    //   Switch desc='活动专题页 关闭后，将不再为您展示活动专题页内容'            checked=true
    //   Switch desc='软件-全球上网 关闭后，将不再为您展示全球上网功能入口'       checked=true
    //   Switch desc='软件-快应用 关闭后，将不再为您展示快应用功能入口'           checked=true
    // 【游戏服务管理】
    //   Switch desc='自动生成云游戏桌面图标 启动云游戏时…'                     checked=false ← 功能项
    //   Switch desc='显示游戏抽奖 关闭后，将无法使用游戏抽奖功能'               checked=true
    //   Switch desc='小游戏服务 关闭后，将不再为您展示小游戏相关内容'            checked=true
    //   Switch desc='显示首页最近使用的小游戏 关闭后，将不再展示最近使用的小游戏' checked=true
    // 【我的页功能管理】← 全是**功能**，一个都不能关
    //   Switch desc='手机清理功能 …' / '开启应用安全检测 …' / '应用锁 …'
    //          / '权限管理 …' / '互联升级 …'
    // 【其他功能管理】
    //   Switch desc='显示福利活动 关闭后，将无法使用福利活动相关功能'           checked=true
    //   Switch desc='未成年人模式 …'                                          checked=true ← 功能项
    // ```
    //
    // ⚠️ **绝不能改成 `ToggleOffAll`**。这一页混着两类语义完全不同的开关：
    //    一半是"推荐位"（该关），另一半是**功能开关**（手机清理 / 应用锁 /
    //    权限管理 / 未成年人模式…，关掉会让用户的功能用不了）。
    //    "全关"在这页上是**破坏性**操作，只能逐个点名。
    //
    // ⚠️ 匹配用 `descContains` + 完整标题（如 "软件-快应用"）而不是文本：
    //    实测这些行的 `content-desc` = "标题 + 一段'关闭后…'的说明"，
    //    命中规则写在描述开头，写全标题才不会互相串到。
    //
    // ⚠️ 一个任务里出现多个 `ToggleOff` 是合法的：它们各自幂等、各自读回验证，
    //    最后一个跑完才算任务完成。
    AdTask(
        id = "market_benefit_lottery",
        title = "应用商店 · 功能推荐位",
        guide = "应用商店 → 我的 → 设置 → 功能设置 → 关闭各推荐位开关",
        allowedPackages = listOf("com.xiaomi.market"),
        steps = listOf(
            // ① 打开应用商店**主页**。
            //
            // ⚠️ **不能直达「功能设置」页**，这是实测撞出来的硬限制：
            //
            //     com.xiaomi.market/.business_ui.settings.function
            //         .FunctionSettingsFragmentActivity
            //
            //     exported=false —— 外部启动直接被系统拒绝：
            //     `Permission Denial: ... not exported from uid 10146`
            //     （连 adb shell / uid 2000 都起不来，与「应用包管理组件」
            //       的设置页是同族问题，见 AutomationStep.SELF_APK_DATA 那段注释）。
            //
            //     更坑的是：从无障碍服务里用 `PendingIntent.send()` 发这个 Intent
            //     **不抛异常**，日志只会打"启动请求已受理"，然后页面纹丝不动 ——
            //     实测就是这样白等 5 秒超时的。
            //
            // 所以只能老老实实走 UI：
            //     主页 →「我的」→ 右上角齿轮 → 滚动找「功能设置」
            //
            // ⚠️⚠️ 但"打开主页"这件事**必须是确定性的** —— 实测第二次跑这个任务时
            //     整项失败过，第 ② 步白等 5 秒：
            //
            //     ```
            //     [1/9] 打开应用商店 → 正在启动：com.xiaomi.market/.ui.MarketTabActivity
            //                            （flags=0x10000000）
            //     [2/9] 点「我的」   → （5 秒后）页面快照：action_bar_title_expand='功能设置'
            //     ```
            //
            //     根因：上一轮跑完时商店停在「功能设置」页，而
            //     `Launch(component = MarketTabActivity, clearTask = false)` 只是把
            //     **那个已存在的任务提到前台**，界面照旧是「功能设置」——
            //     `MarketTabActivity` 是所有页面的**宿主**（各页是它内部的 Fragment），
            //     所以"恢复"出来的正是旧页面。于是第 ② 步在这一页上找不到底部「我的」标签。
            //
            //     解法：改用 `appPackage`（等价于点桌面图标）**并保留默认的
            //     `clearTask = true`** —— 先清掉商店自己的任务栈再启动 launcher
            //     Activity，必然落在主页。
            //     ⚠️ 这个组合在本 ROM 上是实测可用的：「天气」那一项用的就是它
            //       （日志里 `flags=0x10008000`，启动后直接点「城市管理」成功），
            //       所以"跨应用带 CLEAR_TASK 会卡住不返回"**不适用于 `appPackage` 这种方式**。
            //       清掉的是商店自己的任务栈，不碰别的应用，也不会丢用户数据。
            AutomationStep.Launch(
                appPackage = "com.xiaomi.market",
                label = "打开应用商店主页",
            ),

            // ② 切到「我的」标签页。
            AutomationStep.Click(
                matches = listOf(
                    Match(
                        text = "我的",
                        textMatch = MatchType.Exact,
                        maxTextLength = 4,
                        target = MatchTarget.ClickableAncestor,
                    ),
                ),
                label = "点「我的」",
            ),

            // ③ 点右上角的「设置」齿轮。
            //
            // 用 `viewIdContains` 而不是文本/描述：这一步的可靠锚点就是它的 id
            // （`setting_icon`），而描述是空的。
            AutomationStep.Click(
                matches = listOf(
                    Match(viewIdContains = "setting_icon"),
                    // 兜底：按描述找（部分版本给这个图标带了 content-desc）。
                    Match(descContains = "设置", className = "android.widget.ImageView"),
                ),
                label = "点「设置」",
            ),

            // ④ 先等设置页出来（锚点是标题栏的"设置"）。
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(text = "功能设置", textMatch = MatchType.Exact),
                    Match(text = "隐私设置", textMatch = MatchType.Exact),
                ),
                retryable = true,
                label = "等待设置页",
            ),

            // ⑤ 往下翻着找「功能设置」—— 它在列表靠后处，一屏显示不下（实测要滚两屏）。
            AutomationStep.ScrollTo(
                match = Match(text = "功能设置", textMatch = MatchType.Exact),
                label = "滚动查找「功能设置」",
            ),

            // ⑥ 点进去。
            AutomationStep.Click(
                matches = listOf(
                    Match(
                        text = "功能设置",
                        textMatch = MatchType.Exact,
                        maxTextLength = 6,
                        target = MatchTarget.ClickableAncestor,
                    ),
                ),
                label = "点「功能设置」",
            ),

            // ⑦ 等功能设置页加载出来。
            //
            // ⚠️ 锚点必须**两种版本的页面都能命中**（这一页的开关个数随应用商店版本变，
            //    实测差异很大，见上方 KDoc）：
            //    · 新版（13 个开关）：首屏最上面是「导航栏管理」组的「视频专区」；
            //    · 旧版（2 个开关）  ：首屏就是「显示游戏抽奖」。
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(text = "视频专区", textMatch = MatchType.Exact),
                    Match(
                        descContains = "显示游戏抽奖",
                        className = "android.widget.Switch",
                    ),
                ),
                retryable = true,
                label = "等待功能设置页",
            ),

            // ⑧~⑮ 逐个关掉要关的开关。
            //
            // ⚠️ 顺序**与页面上到下的顺序一致**（新版是
            //    「导航栏管理」→「游戏服务管理」→ … →「其他功能管理」）。
            //    这不只是好看：`ToggleOff` 不滚动，靠顺序能让相邻的开关
            //    尽量落在同一个屏幕里，少走冤枉路。
            //
            // ⚠️ **新版独有的那 6 个一律 `optional = true`** —— 旧版页面
            //    根本没有这几项，不标的话整项任务会在旧版上直接失败。
            //    两版都有的（显示游戏抽奖 / 显示福利活动）保持严格，
            //    免得匹配条件写错时被 `optional` 悄悄吞掉。
            AutomationStep.ToggleOff(
                match = Match(
                    descContains = "视频专区",
                    className = "android.widget.Switch",
                    onlyWhenChecked = true,
                ),
                optional = true,
                label = "关闭「视频专区」",
            ),
            AutomationStep.ToggleOff(
                match = Match(
                    descContains = "活动专题页",
                    className = "android.widget.Switch",
                    onlyWhenChecked = true,
                ),
                optional = true,
                label = "关闭「活动专题页」",
            ),
            AutomationStep.ToggleOff(
                match = Match(
                    descContains = "软件-全球上网",
                    className = "android.widget.Switch",
                    onlyWhenChecked = true,
                ),
                optional = true,
                label = "关闭「软件-全球上网」",
            ),
            AutomationStep.ToggleOff(
                match = Match(
                    descContains = "软件-快应用",
                    className = "android.widget.Switch",
                    onlyWhenChecked = true,
                ),
                optional = true,
                label = "关闭「软件-快应用」",
            ),

            // 「显示游戏抽奖」—— 两版都有，保持严格（不写 optional）。
            AutomationStep.ToggleOff(
                match = Match(
                    descContains = "显示游戏抽奖",
                    className = "android.widget.Switch",
                    onlyWhenChecked = true,
                ),
                label = "关闭「显示游戏抽奖」",
            ),
            AutomationStep.ToggleOff(
                match = Match(
                    descContains = "小游戏服务",
                    className = "android.widget.Switch",
                    onlyWhenChecked = true,
                ),
                optional = true,
                label = "关闭「小游戏服务」",
            ),
            AutomationStep.ToggleOff(
                match = Match(
                    descContains = "显示首页最近使用的小游戏",
                    className = "android.widget.Switch",
                    onlyWhenChecked = true,
                ),
                optional = true,
                label = "关闭「显示首页最近使用的小游戏」",
            ),

            // ⑯ 「显示福利活动」在最后一组（「其他功能管理」），新版要往下滑一屏。
            //
            // ⚠️ 目标用 `ScrollTo` 而不是直接 `ToggleOff`：屏幕外的节点**中心点
            //    落在屏外**，`NodeClicker` 会按安全规则拒绝点击（见坑 #21），
            //    表现是"这一步反复重试然后超时"。
            //    旧版页面上它本来就在首屏 —— `ScrollTo` 是"先看在不在这屏、
            //    不在才滚"，所以对旧版是**零操作**，不会白滚。
            AutomationStep.ScrollTo(
                match = Match(text = "显示福利活动", textMatch = MatchType.Exact),
                label = "滚动查找「显示福利活动」",
            ),
            AutomationStep.ToggleOff(
                match = Match(
                    descContains = "显示福利活动",
                    className = "android.widget.Switch",
                    onlyWhenChecked = true,
                ),
                label = "关闭「显示福利活动」",
            ),
        ),
    ),

    // =========================================================================
    // 8. 小米画报 · 锁屏推广
    // =========================================================================
    //
    // ## 只能直达，没有别的入口
    //
    // 小米画报是个**没有主界面**的组件 —— 实测它的 launcher 入口不存在：
    //
    // ```text
    // adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER com.mfashiongallery.emag
    // → No activity found
    // ```
    //
    // 桌面找不到它、也没有任何"应用内导航"可以走到设置页，
    // 而 `SysSettingActivity` 本身是 `exported=true`（adb 实测能起）。
    // 所以这一项**必须**用显式组件直达 —— 这和「应用商店」那种
    // "能走 UI 就别直达"的情况正好相反。
    //
    // ## 本机实测结构（`com.mfashiongallery.emag/.syssetting.SysSettingActivity`）
    //
    // **每一行都是两层 Switch**：
    //
    // ```text
    // Switch desc="锁屏轮播"                    ← 外层，覆盖整行，自带 desc、自己可点
    //   └ Switch id=checkbox                    ← 内层，右侧那枚
    // TextView id=title text="锁屏轮播"          ← clickable=false，只能当兜底锚点
    // ```
    //
    // 所以首选直接点**外层**：它同时提供了"认出是哪一行"（desc）和"点哪里"（整行）
    // 两个信息。`id=title` 那些 TextView 一律不可点，只能配
    // [MatchTarget.SwitchInSameRow] 去找同行开关。
    //
    // ## ⚠️ 两个开关有**确认弹窗**，早安画报没有
    //
    // 「锁屏轮播」和「锁屏右滑」点下去只会弹框，**开关状态不动**，
    // 必须再点弹窗里的「关闭」才真的生效 —— 见 [FASHIONGALLERY_CONFIRM_CLOSE]。
    // 「早安画报」点一下直接生效，所以它刻意不带 `confirm`。
    //
    // ## ⚠️ 页面上的项**会随开关状态增减**
    //
    // 实测对比两次 dump：这页不是固定列表 ——
    //
    // | 「锁屏轮播」状态 | 页面上多出来的项 |
    // | --- | --- |
    // | 开着 | 「使用流量加载图片」 |
    // | 关着 | 「轮播设置」「我的壁纸」「浏览历史」 |
    //
    // 所以**不要把这页当成"三个开关固定排列"**，也别让某个步骤靠"前面有几行"来定位。
    // 目前三个目标开关（锁屏轮播 / 锁屏右滑 / 早安画报）在两种状态下都同屏可见、
    // 不需要滚动，所以脚本按 `desc` 逐行点即可，不受增删影响。
    //
    // ⚠️ 词之间不会互相误命中："锁屏轮播" 与 "锁屏右滑" 是完全不同的词；
    //    `descContains = "早安画报"` 也不会命中 `text = "早安画报推送历史"`
    //    （那条只有 text、没有 desc）。
    // =========================================================================
    AdTask(
        id = "fashiongallery_lockscreen_promotion",
        title = "小米画报 · 锁屏推广",
        guide = "小米画报 → 锁屏轮播 / 锁屏右滑 / 早安画报",
        allowedPackages = listOf("com.mfashiongallery.emag"),
        steps = listOf(
            // ① 直达画报设置页。见上面的说明：这是唯一入口。
            //
            // ⚠️ `clearTask = false`：那是**别人应用的任务栈**，
            //    没有理由去清空它（而且实测跨应用带 CLEAR_TASK 会卡住不返回）。
            AutomationStep.Launch(
                component = "com.mfashiongallery.emag/.syssetting.SysSettingActivity",
                clearTask = false,
                label = "打开画报设置",
            ),

            // ② 等页面出来。锚点取页面副标题，它比开关本身更稳定
            //    （无论开关开着还是关着，这行文字都在）。
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(text = "精美壁纸自动更换", textMatch = MatchType.Contains),
                    // 兜底：副标题文案可能变，退回到"至少有一个我们认识的开关"。
                    Match(
                        descContains = "锁屏轮播",
                        className = "android.widget.Switch",
                    ),
                ),
                retryable = true,
                label = "等待画报设置页",
            ),

            // ③ 关「锁屏轮播」。
            //
            // 主选直接点外层那个整行 Switch：它自带 desc、自己可点，
            // 一轮就能定位到"哪一行 + 点哪"。
            AutomationStep.ToggleOff(
                matches = listOf(
                    Match(
                        descContains = "锁屏轮播",
                        className = "android.widget.Switch",
                        target = MatchTarget.Self,
                        onlyWhenChecked = true,
                    ),
                    // 兜底：文本锚点 → 同行开关。`maxTextLength` 防止
                    // "锁屏轮播设置"之类的长文案被 Contains 命中。
                    Match(
                        text = "锁屏轮播",
                        textMatch = MatchType.Exact,
                        maxTextLength = 4,
                        target = MatchTarget.SwitchInSameRow,
                        onlyWhenChecked = true,
                    ),
                ),
                // ⚠️ 必须点掉确认框，否则开关保持 checked=true，读回校验会判失败。
                confirm = FASHIONGALLERY_CONFIRM_CLOSE,
                label = "关闭「锁屏轮播」",
            ),

            // ④ 关「锁屏右滑」。它的 desc 是"锁屏右滑 在锁屏亮屏时，右滑进入画报…"，
            //    用 Contains 匹配前缀即可。
            AutomationStep.ToggleOff(
                matches = listOf(
                    Match(
                        descContains = "锁屏右滑",
                        className = "android.widget.Switch",
                        target = MatchTarget.Self,
                        onlyWhenChecked = true,
                    ),
                    Match(
                        text = "锁屏右滑",
                        textMatch = MatchType.Exact,
                        maxTextLength = 4,
                        target = MatchTarget.SwitchInSameRow,
                        onlyWhenChecked = true,
                    ),
                ),
                // 与「锁屏轮播」同一个确认框（实测标题/按钮结构完全一致）。
                confirm = FASHIONGALLERY_CONFIRM_CLOSE,
                label = "关闭「锁屏右滑」",
            ),

            // ⑤ 关「早安画报」。
            AutomationStep.ToggleOff(
                matches = listOf(
                    Match(
                        descContains = "早安画报",
                        className = "android.widget.Switch",
                        target = MatchTarget.Self,
                        onlyWhenChecked = true,
                    ),
                    Match(
                        text = "早安画报",
                        textMatch = MatchType.Exact,
                        maxTextLength = 4,
                        target = MatchTarget.SwitchInSameRow,
                        onlyWhenChecked = true,
                    ),
                ),
                label = "关闭「早安画报」",
            ),
        ),
    ),

    // =========================================================================
    // 9. 搜狗输入法小米版 · 节日活动提醒
    // =========================================================================
    //
    // ## 这一项**只能**从系统设置走进去
    //
    // 两条捷径都被堵死，全是实测结论：
    //
    // ```text
    // ① 直达设置页
    //    am start -n com.sohu.inputmethod.sogou.xiaomi
    //        /com.sohu.inputmethod.settings.activity.InputSettings
    //    → SecurityException: not exported from uid 10162
    //
    // ② 走桌面图标
    //    cmd package resolve-activity -a MAIN -c LAUNCHER <pkg>
    //    → No activity found
    // ```
    //
    // 这其实是**输入法应用的通性**：它本质是一个 IME 服务
    //（`com.sohu.inputmethod.sogou.xiaomi/.SogouIME`）+ 一堆设置页，
    // **不提供桌面图标**。所以"直接启动这个应用"这件事本身就不存在。
    //
    // ## 实测路径（每一步都在真机上验证过）
    //
    // ```text
    // 系统设置 → 语言与输入法        com.android.settings/.SubSettings
    //   └ 输入法管理
    //       └ 点「搜狗输入法小米版」左侧文字
    //           → com.sohu.inputmethod.sogou.SogouIMESettings
    //             └ 输入设置
    //                 → com.sohu.inputmethod.settings.activity.InputSettings
    //                   └ 往下滚 → 「节日活动」分组 → 节日活动提醒
    // ```
    //
    // ## ⚠️ 为什么必须点"左侧文字"而不是整行
    //
    // 「输入法管理」页每一行都是**两个独立热区**，靠祖先链分得很干净：
    //
    // ```text
    // LinearLayout [[44,967][1396,1223]]                     ← 整行容器（不可点）
    //   ├ LinearLayout id=head clickable=true [[44,967][1077,1223]]   ← 点它 = 进设置
    //   │   └ TextView id=title text='搜狗输入法小米版'
    //   └ Switch id=enable_mode desc='搜狗输入法小米版' [[1158,1032][1337,1149]]
    //                                                          ← 点它 = 启用/停用输入法
    // ```
    //
    // `id=head` 的右边界是 **1077**，够不到从 **1158** 开始的开关 ——
    // 所以 [MatchTarget.ClickableAncestor] 找到它、点它的中心 `(560,1095)`
    // 正好落在文字区，**不会误触右侧那个启用开关**。
    //
    // ⚠️ 这一点很重要：误触那个开关会把输入法**停用**掉，用户就没法打字了。
    //
    // ## ⚠️ 「输入法管理」这个名字在页面里出现两次
    //
    // 标题栏（`action_bar_title_expand`）和列表行（`id=title`）同名。
    // [NodeMatcher.findAnchor] 是深度优先、**命中即返回**，而 uiautomator 的树序里
    // 列表行排在标题栏（`action_bar`）之前 —— 所以会先命中列表行。
    //
    // 另外 `ClickableAncestor` 本身也起了一层保险：标题栏那个节点爬不到可点祖先
    //（它的父 `action_bar` 不可点），真要是先命中它，`resolveTarget` 会返回 null。
    //
    // ## ⚠️ 这一页的 Switch 全是 `id=c0a`、且没有 `content-desc`
    //
    // 搜狗用了混淆后的资源名，同一个页面里 6 个开关**共享同一个 id**。
    // 所以定位只能靠**标题文本 + [MatchTarget.SwitchInSameRow]**，
    // 不能像小米画报那样走 `descContains`。
    // =========================================================================
    AdTask(
        id = "sogou_ime_holiday_promo",
        title = "搜狗输入法 · 节日活动提醒",
        guide = "设置 → 语言与输入法 → 输入法管理 → 搜狗输入法小米版 → 输入设置 → 节日活动提醒",
        // 这条路径会跨两个包：系统设置 + 搜狗自己。防呆检测必须都列上。
        allowedPackages = listOf(
            "com.android.settings",
            "com.sohu.inputmethod.sogou.xiaomi",
        ),
        steps = listOf(
            // ① 打开「语言与输入法」。用标准公开 action，跨 ROM 最稳。
            AutomationStep.Launch(
                action = "android.settings.INPUT_METHOD_SETTINGS",
                label = "打开语言与输入法",
            ),

            // ② 等页面出来。锚点用页面上的两个条目名 ——
            //    比"语言 / 输入法"这类分组标题更不容易受 ROM 差异影响。
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(text = "输入法管理", textMatch = MatchType.Exact),
                    Match(text = "安全键盘", textMatch = MatchType.Exact),
                ),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待语言与输入法",
            ),
            AutomationStep.Click(
                match = Match(
                    text = "输入法管理",
                    textMatch = MatchType.Exact,
                    maxTextLength = 5,
                    target = MatchTarget.ClickableAncestor,
                ),
                label = "点「输入法管理」",
            ),

            // ③ 等列表 + 点搜狗那一行的**左侧文字**（见类文档：右边是启用开关）。
            AutomationStep.AwaitFor(
                match = Match(text = "搜狗输入法小米版", textMatch = MatchType.Exact),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待输入法管理页",
            ),
            AutomationStep.Click(
                match = Match(
                    text = "搜狗输入法小米版",
                    textMatch = MatchType.Exact,
                    maxTextLength = 9,
                    target = MatchTarget.ClickableAncestor,
                ),
                label = "点「搜狗输入法小米版」",
            ),

            // ④ 等搜狗设置页 + 点「输入设置」。
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(text = "输入设置", textMatch = MatchType.Exact),
                    Match(text = "键盘设置", textMatch = MatchType.Exact),
                ),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待搜狗设置页",
            ),
            AutomationStep.Click(
                match = Match(
                    text = "输入设置",
                    textMatch = MatchType.Exact,
                    maxTextLength = 4,
                    target = MatchTarget.ClickableAncestor,
                ),
                label = "点「输入设置」",
            ),

            // ⑤ 等输入设置页。
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(text = "手势", textMatch = MatchType.Exact),
                    Match(text = "拼音", textMatch = MatchType.Exact),
                ),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待输入设置页",
            ),

            // ⑥ 往下滚找「节日活动提醒」—— 它在页面最底部（实测 y≈2811）。
            //
            // ⚠️ 这一页**会随设置项增减变长**（实测两次 dump 内容差很多），
            //    所以不能用"滚 N 次"硬编码，必须滚到找着为止。
            AutomationStep.ScrollTo(
                match = Match(text = "节日活动提醒", textMatch = MatchType.Exact),
                label = "滚动查找「节日活动提醒」",
            ),

            // ⑦ 关掉它。靠标题锚点找同行开关 —— 这一页所有 Switch 的 id 都是 `c0a`。
            AutomationStep.ToggleOff(
                match = Match(
                    text = "节日活动提醒",
                    textMatch = MatchType.Exact,
                    maxTextLength = 6,
                    target = MatchTarget.SwitchInSameRow,
                ),
                label = "关闭「节日活动提醒」",
            ),
        ),
    ),

    // =========================================================================
    // 10. 钱包 · 首页推荐位
    // =========================================================================
    //
    // ## 这一页有二十多个开关，而且长度随版本变
    //
    // 实测分组：「2025年钱包新首页」「钱包个人中心」「活动中心」……
    // 每一组下面都是"某个卡片 / 推荐位 / 广告位"的开关，全部默认打开。
    //
    // 所以这一项**不用逐个 [AutomationStep.ToggleOff] 写死**，而是用
    // [AutomationStep.ToggleOffAll]："这一页上的都关掉"。
    // 理由见那个步骤的 KDoc —— 逐个写死的话，版本一变就要改脚本，
    // 漏写一个那个广告位就还在。
    //
    // ## ⚠️ 必须排除「全部开启」
    //
    // 它是页面最上面那个**总开关**（一键把下面全部打开），本身不是广告位。
    // 把它关掉属于帮倒忙。实测它的标题和开关同在一行：
    //
    // ```text
    // TextView id=title text='全部开启'   bounds=[120,710][376,796]
    // Switch   id=switchWidget            bounds=[1118,703][1320,804]   ← y 中心 753
    // ```
    //
    // 开关的 y 中心 753 落在标题的 710..796 里，所以按"行"排除能把它挡住。
    //
    // ## ⚠️ 这一页的所有 Switch 都叫 `id=switchWidget`、且没有 desc
    //
    // 和搜狗那页一样只能靠标题定位。而 `ToggleOffAll` 根本不需要知道标题 ——
    // 它直接找"所有还开着的 Switch"，这正是它比逐个写死更省的地方。
    //
    // ## 入口
    //
    // 设置页 `MiuiStyleSettingActivity` **未导出**（`not exported from uid 10258`），
    // 但钱包有桌面图标，所以从主页走：
    //     钱包主页 →「我的」→ 右上角「设置」→「功能设置」
    //
    // ⚠️ 「我的」和「设置」都只能靠 `content-desc` 定位，而且**必须用 Exact** ——
    //    主页上 `desc` 含"我的"的还有「我的钱包」「我的消息 9」「我的头像」
    //    「我的资产 *****」，Contains 会先命中它们（树序更靠前）。
    // =========================================================================
    AdTask(
        id = "wallet_home_feed_switches",
        title = "钱包 · 首页推荐位",
        guide = "钱包 → 我的 → 设置 → 功能设置 → 关闭各个推荐位（保留「全部开启」）",
        allowedPackages = listOf("com.mipay.wallet"),
        steps = listOf(
            // ① 打开钱包主页。
            //
            // ⚠️ 用 `appPackage`（等价于点桌面图标）而不是 `component` ——
            //    这是系统**最宽容**的启动方式：实测从无障碍服务里启动别的应用的
            //    **具体 Activity**（哪怕 exported=true）会阻塞不返回，
            //    而"打开应用主界面"是正常的。见 [AutomationStep.Launch.appPackage]。
            //
            // ⚠️ **用默认的 `clearTask = true`**（见坑 #38）——
            //    这一步要的是"**确定性地回到主页**"，而 `NEW_TASK` 只是把已有的任务
            //    提到前台、**不保证**回到主页：钱包上一轮跑完停在「功能设置」子页，
            //    下一轮就会直接停在那儿，第 ② 步在子页上找主页的「首页」标签，
            //    一路等到超时、整项重跑 —— 用户看到的就是"卡住了，一直在等"。
            //
            // ⚠️ 这里原本写着 `clearTask = false`，理由是"跨应用带 CLEAR_TASK 会卡住" ——
            //    **那条结论不适用于 `appPackage` 这种方式**（它是拿 `component` 直达
            //    那批实测出来的）。同一次日志里「天气」用的就是 `appPackage` ＋
            //    `flags=0x10008000`（含 CLEAR_TASK），启动后直接点下一步成功。
            //    清掉的只是**钱包自己的**任务栈，不碰别的应用、也不丢用户数据。
            AutomationStep.Launch(
                appPackage = "com.mipay.wallet",
                label = "打开钱包",
            ),

            // ② 等主页 + 切到「我的」标签页。
            AutomationStep.AwaitFor(
                match = Match(descContains = "首页", descMatch = MatchType.Exact),
                timeoutMs = 20_000L,
                retryable = true,
                label = "等待钱包主页",
            ),
            AutomationStep.Click(
                match = Match(
                    descContains = "我的",
                    // ⚠️ Exact 是必须的，理由见类文档。
                    descMatch = MatchType.Exact,
                    target = MatchTarget.ClickableAncestor,
                ),
                label = "点「我的」",
            ),

            // ③ 等「我的」页 + 点右上角那个设置入口。
            //
            // ⚠️ 这里**必须同时兼容两种形态**（实测踩到，见坑 #39）：
            //
            // | 形态 | 设置入口长什么样 |
            // |---|---|
            // | 广告**没关**时（旧） | 一个**独立**的图标节点，`desc` 恰好是 `"设置"` |
            // | 广告**关掉**后（现） | 标题「我的钱包」和齿轮被自绘框架**合并成一个节点**：`desc='我的钱包\n设置'`，bounds 覆盖整条顶栏 `[0,0]-[1440,411]` |
            //
            // 旧代码只写了 `descMatch = Exact`（"设置"）→ 新形态下
            // `"我的钱包\n设置" ≠ "设置"` → 一直等不到 → 白等 10 秒超时、整项重跑：
            //
            //     [4/9] 等待「我的」页 → 等待超时（等待「我的」页）→ 重新开始这一项
            //
            // 所以两个候选都写上：**精确的在前（旧形态），包含的在后（新形态）**。
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(descContains = "设置", descMatch = MatchType.Exact),
                    Match(descContains = "设置", descMatch = MatchType.Contains),
                ),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待「我的」页",
            ),
            AutomationStep.Click(
                matches = listOf(
                    Match(
                        descContains = "设置",
                        descMatch = MatchType.Exact,
                        target = MatchTarget.ClickableAncestor,
                    ),
                    // 合并节点那一种：**节点边界 ≠ 热区** —— 它的中心点
                    // (720,205) 点了毫无反应，能进设置的是右侧偏下那一小块
                    // （实测 (1370,290) ≈ 相对 0.95, 0.70），所以要带 clickFraction。
                    // 见 [Match.clickFraction]。
                    Match(
                        descContains = "设置",
                        descMatch = MatchType.Contains,
                        target = MatchTarget.ClickableAncestor,
                        clickFraction = 0.95f to 0.70f,
                    ),
                ),
                label = "点「设置」",
            ),

            // ④ 等设置页 + 点「功能设置」。
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(text = "功能设置", textMatch = MatchType.Exact),
                    Match(text = "隐私设置", textMatch = MatchType.Exact),
                ),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待设置页",
            ),
            AutomationStep.Click(
                // 这一项实测自己就 `clickable=true`，不需要爬祖先。
                match = Match(
                    text = "功能设置",
                    textMatch = MatchType.Exact,
                    maxTextLength = 4,
                ),
                label = "点「功能设置」",
            ),

            // ⑤ 等功能设置页出来。锚点取首尾两项 —— 只要有一个在，页就到位了。
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(text = "全部开启", textMatch = MatchType.Exact),
                    Match(text = "积分大富翁", textMatch = MatchType.Exact),
                ),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待功能设置页",
            ),

            // ⑥ 把这一页上其余开关全部关掉 —— 边滚边关，滚到底为止。
            //
            // ⚠️ 排除「全部开启」：它是总开关，关掉会把用户/我们刚关好的
            //    东西又恢复成默认（全开），属于帮倒忙。
            AutomationStep.ToggleOffAll(
                exclude = listOf(
                    Match(text = "全部开启", textMatch = MatchType.Exact),
                ),
                label = "关闭推荐位开关",
            ),
        ),
    ),

    // =========================================================================
    // 11. 搜索 · 首页展示模块与热榜
    // =========================================================================
    //
    // ## 入口：可以直达，但它没有桌面图标
    //
    // ```text
    // resolve-activity -a MAIN -c LAUNCHER com.android.quicksearchbox
    // → No activity found
    // ```
    //
    // 而设置页 `preferences.SearchSettingsPreferenceActivity` 是 **exported=true**
    //（adb 实测能起），所以用 `component` 直达 —— 与「小米画报」同一种情况。
    //
    // ## 实测的页面树（⚠️ 有**两个** Activity 混用，不是一层 Fragment）
    //
    // ```text
    // SearchSettingsPreferenceActivity              ← 搜索设置
    //   └ 首页展示模块
    //       → OtherSettingsPreferenceActivity       ← 这里开始换 Activity 了
    //           ├ 搜索提示词        Switch（要关的）
    //           └ 热搜榜单
    //               → 我的热榜（还在 OtherSettingsPreferenceActivity）
    //                   ├ 推荐        Switch（要关的）
    //                   ├ 热搜 → 热榜管理    微博 / 资讯 / 全网
    //                   ├ 生活 → RankSecondSettingActivity   笔记
    //                   ├ 娱乐 → RankSecondSettingActivity   短剧 / 音乐
    //                   └ 话题 → RankSecondSettingActivity   知乎
    // ```
    //
    // ⚠️ 「热榜管理」这个标题**四个子页面共用**（热搜/生活/娱乐/话题 进去都叫它），
    //    所以**不能拿它当"已经在子页面了"的判据** —— 判据要用各页独有的条目名
    //    （微博 / 笔记 / 短剧 / 知乎）。
    //
    // ## ⚠️ 这一族的 Switch 结构有两处和之前不同
    //
    // 1. **外层那个整行 Switch 的 `content-desc` 是空的**（钱包那页至少还带 desc）。
    //    所以只能拿 `id=title` 的 TextView 当锚点 + [MatchTarget.SwitchInSameRow]。
    // 2. 「首页展示模块」页上**有 Switch 的行和没有 Switch 的行混在一起**
    //    （应用推荐 / 热搜榜单 是没有的，点它们等于"进子页面"）——
    //    好在这不影响定位：`SwitchInSameRow` 找的是"同行开关"，
    //    没有开关的行自然找不到。
    //
    // ## 为什么四个子页面都用 [AutomationStep.ToggleOffAll]
    //
    // 它们各自的内容都是"把这一页的开关全关掉"（微博/资讯/全网、笔记、短剧/音乐、知乎），
    // 而且分属三个不同的 Activity / 页面。逐个 `ToggleOff` 写死一是不短，
    // 二是这页的条目实测**会变**（版本更新增减板块）。用 `ToggleOffAll`
    // 既短又能自动适应增减。
    //
    // ⚠️ 它们的 `exclude` 留空 —— 这几页**没有**总开关，全是普通推荐位。
    //    而「首页展示模块」那页**不能**用 `ToggleOffAll`：
    //    用户只要关「搜索提示词」，同页的「搜索历史」「设置入口」「扫一扫」不该动。
    // =========================================================================
    AdTask(
        id = "search_home_modules_promo",
        title = "搜索 · 首页展示模块与热榜",
        guide = "搜索设置 → 首页展示模块 → 搜索提示词；热搜榜单 → 推荐；热搜 / 生活 / 娱乐 / 话题",
        allowedPackages = listOf("com.android.quicksearchbox"),
        steps = listOf(
            // ① 直达搜索设置页。
            // ⚠️ `clearTask = false`：那是**别人应用的任务栈**，没理由清空它
            //   （而且跨应用带 CLEAR_TASK 实测会卡住不返回）。
            AutomationStep.Launch(
                component = "com.android.quicksearchbox/.preferences" +
                    ".SearchSettingsPreferenceActivity",
                clearTask = false,
                label = "打开搜索设置",
            ),

            // ② 等搜索设置页。
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(text = "首页展示模块", textMatch = MatchType.Exact),
                    Match(text = "常规设置", textMatch = MatchType.Exact),
                ),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待搜索设置页",
            ),

            // ③ 进「首页展示模块」。
            AutomationStep.Click(
                match = Match(
                    text = "首页展示模块",
                    textMatch = MatchType.Exact,
                    maxTextLength = 6,
                    target = MatchTarget.ClickableAncestor,
                ),
                label = "点「首页展示模块」",
            ),

            // ④ 等这页出来 —— 用页面独有的条目名做判据
            //    （"首页展示模块"在这页是标题栏文字，两页都有，不能用）。
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(text = "搜索提示词", textMatch = MatchType.Exact),
                    Match(text = "热搜榜单", textMatch = MatchType.Exact),
                ),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待首页展示模块页",
            ),

            // ⑤ 关「搜索提示词」。
            //
            // ⚠️ 这里**不能**用 `ToggleOffAll` —— 同页还有「搜索历史」「设置入口」
            //    「扫一扫」等开关，用户没要求动它们。
            //
            // ⚠️ `optional = true`：**别的机型上这一行可能压根没有**
            //   （搜索属于系统组件，各家定制差异很大）。缺了它，这一步会一路等到超时、
            //    把整项任务判失败 —— 而它其实没什么可处理的。
            //    找不到时会打一条"本机没有这一项，跳过"，不会静默糊过去。
            AutomationStep.ToggleOff(
                matches = listOf(
                    Match(
                        text = "搜索提示词",
                        textMatch = MatchType.Exact,
                        maxTextLength = 5,
                        target = MatchTarget.SwitchInSameRow,
                    ),
                ),
                optional = true,
                label = "关闭「搜索提示词」",
            ),

            // ⑥ 进「热搜榜单」（进去后标题叫「我的热榜」）。
            AutomationStep.Click(
                match = Match(
                    text = "热搜榜单",
                    textMatch = MatchType.Exact,
                    maxTextLength = 4,
                    target = MatchTarget.ClickableAncestor,
                ),
                label = "点「热搜榜单」",
            ),

            // ⑦ 等「我的热榜」页。
            //
            // ⚠️ 判据要**把可能出现的入口名都列上** —— 这页在不同 ROM 上
            //    保留哪几个分组完全不确定（本机是 热搜 / 生活 / 娱乐 / 话题）。
            //    只写其中两个，某台机器上恰好那两个都没保留就会白等到超时，
            //    而且会连累整项任务（这一步是 `retryable`，会整个重跑）。
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(text = "热搜", textMatch = MatchType.Exact, maxTextLength = 2),
                    Match(text = "生活", textMatch = MatchType.Exact, maxTextLength = 2),
                    Match(text = "娱乐", textMatch = MatchType.Exact, maxTextLength = 2),
                    Match(text = "话题", textMatch = MatchType.Exact, maxTextLength = 2),
                ),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待「我的热榜」页",
            ),

            // ⑧ 关「推荐」。
            //
            // ⚠️ `optional = true`：同第 ⑤ 步的理由 —— 「我的热榜」页上这一行
            //    在别的 ROM 上未必存在（热榜模块本身就可能被裁剪掉）。
            AutomationStep.ToggleOff(
                match = Match(
                    text = "推荐",
                    textMatch = MatchType.Exact,
                    maxTextLength = 2,
                    target = MatchTarget.SwitchInSameRow,
                ),
                optional = true,
                label = "关闭「推荐」",
            ),

            // ⑨ 进「热搜」→ 热榜管理（微博 / 资讯 / 全网），全部关掉。
            //
            // ⚠️ 从这里开始的四个子页是**本任务最主要的适配点**：
            //    「我的热榜」下有 热搜 / 生活 / 娱乐 / 话题 四个入口，
            //    而别的 ROM 可能只有一两个、甚至一个都没有。
            //
            //    所以这一段统一按"**有则处理、无则忽略**"写，三件套缺一不可：
            //
            //    | 步骤 | 加什么 | 少了它会怎样 |
            //    |---|---|---|
            //    | `Click` / `AwaitFor` | `optional = true` | 入口不在 → 一路超时 → 整项失败 |
            //    | `ToggleOffAll` | `guard` | 没进到子页 → **把「我的热榜」页的开关全关掉** |
            //    | `PressBack` | `guard` | 没进过子页 → **把上一层也退掉**，后面全乱 |
            //
            //    ⚠️ 只加 `optional` 而不加两个 `guard`，比不改还危险。
            AutomationStep.Click(
                match = Match(
                    text = "热搜",
                    textMatch = MatchType.Exact,
                    maxTextLength = 2,
                    target = MatchTarget.ClickableAncestor,
                ),
                optional = true,
                label = "点「热搜」",
            ),
            AutomationStep.AwaitFor(
                match = Match(text = "微博", textMatch = MatchType.Exact, maxTextLength = 2),
                timeoutMs = 10_000L,
                optional = true,
                label = "等待「热搜」子页",
            ),
            AutomationStep.ToggleOffAll(
                guard = Match(text = "微博", textMatch = MatchType.Exact, maxTextLength = 2),
                label = "关闭热榜（热搜）",
            ),

            // ⑩ 返回「我的热榜」→ 进「生活」→ 全关。
            AutomationStep.PressBack(
                guard = Match(text = "微博", textMatch = MatchType.Exact, maxTextLength = 2),
            ),
            AutomationStep.AwaitFor(
                match = Match(text = "生活", textMatch = MatchType.Exact, maxTextLength = 2),
                timeoutMs = 10_000L,
                optional = true,
                label = "返回「我的热榜」",
            ),
            AutomationStep.Click(
                match = Match(
                    text = "生活",
                    textMatch = MatchType.Exact,
                    maxTextLength = 2,
                    target = MatchTarget.ClickableAncestor,
                ),
                optional = true,
                label = "点「生活」",
            ),
            AutomationStep.AwaitFor(
                match = Match(text = "笔记", textMatch = MatchType.Exact, maxTextLength = 2),
                timeoutMs = 10_000L,
                optional = true,
                label = "等待「生活」子页",
            ),
            AutomationStep.ToggleOffAll(
                guard = Match(text = "笔记", textMatch = MatchType.Exact, maxTextLength = 2),
                label = "关闭热榜（生活）",
            ),

            // ⑪ 返回 → 进「娱乐」→ 全关。
            AutomationStep.PressBack(
                guard = Match(text = "笔记", textMatch = MatchType.Exact, maxTextLength = 2),
            ),
            AutomationStep.AwaitFor(
                match = Match(text = "娱乐", textMatch = MatchType.Exact, maxTextLength = 2),
                timeoutMs = 10_000L,
                optional = true,
                label = "返回「我的热榜」",
            ),
            AutomationStep.Click(
                match = Match(
                    text = "娱乐",
                    textMatch = MatchType.Exact,
                    maxTextLength = 2,
                    target = MatchTarget.ClickableAncestor,
                ),
                optional = true,
                label = "点「娱乐」",
            ),
            AutomationStep.AwaitFor(
                match = Match(text = "短剧", textMatch = MatchType.Exact, maxTextLength = 2),
                timeoutMs = 10_000L,
                optional = true,
                label = "等待「娱乐」子页",
            ),
            AutomationStep.ToggleOffAll(
                guard = Match(text = "短剧", textMatch = MatchType.Exact, maxTextLength = 2),
                label = "关闭热榜（娱乐）",
            ),

            // ⑫ 返回 → 进「话题」→ 全关。
            AutomationStep.PressBack(
                guard = Match(text = "短剧", textMatch = MatchType.Exact, maxTextLength = 2),
            ),
            AutomationStep.AwaitFor(
                match = Match(text = "话题", textMatch = MatchType.Exact, maxTextLength = 2),
                timeoutMs = 10_000L,
                optional = true,
                label = "返回「我的热榜」",
            ),
            AutomationStep.Click(
                match = Match(
                    text = "话题",
                    textMatch = MatchType.Exact,
                    maxTextLength = 2,
                    target = MatchTarget.ClickableAncestor,
                ),
                optional = true,
                label = "点「话题」",
            ),
            AutomationStep.AwaitFor(
                match = Match(text = "知乎", textMatch = MatchType.Exact, maxTextLength = 2),
                timeoutMs = 10_000L,
                optional = true,
                label = "等待「话题」子页",
            ),
            AutomationStep.ToggleOffAll(
                guard = Match(text = "知乎", textMatch = MatchType.Exact, maxTextLength = 2),
                label = "关闭热榜（话题）",
            ),
        ),
    ),

    // =========================================================================
    // 13. 负一屏 · 推荐卡片与热门活动
    // =========================================================================
    //
    // ## 入口：没有桌面图标，只能直达
    //
    // 负一屏是**从桌面往右滑出来的**，不是独立图标，实测：
    //
    // ```text
    // resolve-activity -a MAIN -c LAUNCHER com.miui.personalassistant
    // → No activity found
    // ```
    //
    // 而设置页 `settings.PASettingActivity` 是 `exported=true`（adb 实测能起）。
    // 所以这一项和「小米画报」同族：**没有主界面可导航，只能直达设置页**。
    //
    // ## 本机实测结构（`com.miui.personalassistant/.settings.PASettingActivity`）
    //
    // ```text
    // Switch desc='负一屏'                          checked=true    ← 总开关，别动
    // Switch desc='搜索框 提供全局搜索和本地搜索'      checked=true    ← 别动
    // Switch desc='推荐卡片 推荐精选小部件'            checked=false   ← ★ 要关的
    // Switch desc='热门活动 为您展示近期热门小部件'     checked=false   ← ★ 要关的
    // Switch desc='智能置顶 将有信息动态变化的小部件置顶显示' checked=false  ← 别动
    // ```
    //
    // ⚠️ 这一页的 Switch 比小米画报那页**好认**：外层那个覆盖整行的 Switch
    //    **自带完整的 `content-desc`，且拼接了"标题 + 副标题"** ——
    //    所以一轮就能同时确定"是哪一行"和"点哪里"。
    //    内层那枚 `id=checkbox` 只有右侧一小块，`id=title` 的 TextView 又
    //    `clickable=false`，两者都只配当兜底。
    //
    //    用"标题"这一段做 `descContains` 前缀匹配即可（如 `"推荐卡片"`），
    //    比匹配整句稳 —— 副标题文案改了也不影响。
    //
    // ## ⚠️ 这一项**不能**用 [AutomationStep.ToggleOffAll]
    //
    // 同一页上还有三个开关：**「负一屏」总开关、「搜索框」、「智能置顶」**。
    // 用户只要求关那两个推荐类的，其余三个**不能碰** ——
    // 「负一屏」是总开关（关掉整页功能都没了）、「搜索框」是工具不是广告。
    //
    // 所以这里逐个 `ToggleOff`，而不是图省事"整页关掉"。
    // =========================================================================
    AdTask(
        id = "personalassistant_recommend_cards",
        title = "负一屏 · 推荐卡片与热门活动",
        guide = "负一屏设置 → 推荐卡片 / 热门活动",
        allowedPackages = listOf("com.miui.personalassistant"),
        steps = listOf(
            // ① 直达负一屏设置页。见上面的说明：它没有别的入口。
            //
            // ⚠️ `clearTask = false`：那是**别人应用的任务栈**，
            //    没有理由去清空它（而且实测跨应用带 CLEAR_TASK 会卡住不返回）。
            AutomationStep.Launch(
                component = "com.miui.personalassistant/.settings.PASettingActivity",
                clearTask = false,
                label = "打开负一屏设置",
            ),

            // ② 等页面出来。
            //
            // ⚠️ 锚点用**总开关「负一屏」**而不是某个推荐项：总开关在，
            //    整页就一定加载好了；而下面那几个推荐项是**可以全被关掉**的，
            //    拿它们当"页面到了"的判据，会在用户已经手动关完时误判成没到位。
            //
            //    （标题栏也有 `text='负一屏'`，靠 `className` 限定到 Switch 上，
            //      不会跟它撞。）
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(
                        descContains = "负一屏",
                        className = "android.widget.Switch",
                    ),
                    // 兜底：换用页面上的另一个常驻条目（它在"负一屏"总开关下面第二行）。
                    Match(text = "出行助手", textMatch = MatchType.Exact),
                ),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待负一屏设置页",
            ),

            // ③ 关「推荐卡片」。
            AutomationStep.ToggleOff(
                matches = listOf(
                    Match(
                        descContains = "推荐卡片",
                        className = "android.widget.Switch",
                        target = MatchTarget.Self,
                    ),
                    // 兜底：文本锚点 → 同行开关。
                    Match(
                        text = "推荐卡片",
                        textMatch = MatchType.Exact,
                        maxTextLength = 4,
                        target = MatchTarget.SwitchInSameRow,
                    ),
                ),
                label = "关闭「推荐卡片」",
            ),

            // ④ 关「热门活动」。
            AutomationStep.ToggleOff(
                matches = listOf(
                    Match(
                        descContains = "热门活动",
                        className = "android.widget.Switch",
                        target = MatchTarget.Self,
                    ),
                    Match(
                        text = "热门活动",
                        textMatch = MatchType.Exact,
                        maxTextLength = 4,
                        target = MatchTarget.SwitchInSameRow,
                    ),
                ),
                label = "关闭「热门活动」",
            ),
        ),
    ),

    // =========================================================================
    // 15. 浏览器 · 主页简洁版与首页模块
    // =========================================================================
    //
    // ## 两段互不相干的设置，但都在浏览器里
    //
    // 用户要办两件事：
    //   ① 「主页设置」里把主页换成**简洁版**；
    //   ② 回浏览器主页 → 右上角设置 → 关掉「快捷网址」「全网热榜」。
    //
    // 第二件**必须在浏览器主页上操作**（那个齿轮只长在主页右上角），
    // 所以两段之间要回一次主页。
    //
    // ⚠️ 回主页**不能靠返回键**（实测踩过，详见第 ⑥ 步的注释）：
    //    第 ① 步直达设置页、带 `CLEAR_TASK`，浏览器任务栈里只剩设置页，
    //    连按两次返回会**直接退出浏览器**，后面的步骤全部落空。
    //    改用 `appPackage`（等价于点桌面图标）重新打开主页才对。
    //
    // ## 实测结构
    //
    // `BrowserSettingsActivity`（**可直达** —— `am start -n` 实测 `Starting:` 无异常）：
    //
    // ```text
    // 标题栏 text='设置'
    //   「常规」分组 → TextView id=title text='主页设置'    ← 点它进子页
    // ```
    //
    // 「主页设置」子页（**同一个 Activity**，按返回才回到设置主页）：
    //
    // ```text
    // FrameLayout desc='默认版主页'        id=normal_rl   clickable=true
    // FrameLayout desc='探索版主页'        id=premium_fl  clickable=true
    // FrameLayout desc='简洁版主页已选中'   id=simple_rl   clickable=true   ← ★ 要选的
    // FrameLayout desc='自定义主页'        id=custom_rl   clickable=true
    // ```
    //
    // ⚠️ **选中的那个 desc 会多一个"已选中"后缀**（`简洁版主页` → `简洁版主页已选中`），
    //    所以判据不能写死成等号，得用 `Contains` —— 两种状态都能命中。
    //    好在 `simple_rl` 这个 id 两种状态下都一样，拿它当主选更稳。
    //
    // ⚠️ 这页的条目**会变**：实测两次 dump，`premium_fl`（探索版的 FrameLayout）
    //    有一次就整个不见了，只剩它的 `premium_item`。
    //    所以定位一律靠 id / desc，**不要靠位置**。
    //
    // 浏览器主页（`BrowserActivity`，简洁版）：
    //
    // ```text
    // ImageView desc='设置' id=simple_page_settings  bounds=[1285,218][1392,325]  ← 右上角
    // ImageView desc='退出' id=iv_change_pro_ui      bounds=[48,218][154,324]     ← ⚠️ 别碰
    // LinearLayout id=quicklinks_page_container                                   ← 快捷网址区域
    // LinearLayout desc='全网热搜榜' id=top_title_bar
    // ```
    //
    // 点右上角那个齿轮后弹出的小菜单（PopupWindow）：
    //
    // ```text
    // TextView text='页面布局'                                   ← 分组标题
    // TextView text='快捷网址'  Switch id=quick_link_check_box   checked=true  ← ★
    // TextView text='全网热榜'  Switch id=hot_words_check_box    checked=true  ← ★
    // ```
    //
    // ## ⚠️ 这个菜单里**所有节点都是 `clickable=false`**
    //
    // 实测把整个 PopupWindow 的节点全列出来看过：两个 `Switch`、
    // 它们各自那层 `LinearLayout` 父节点、连文本 `TextView` 在内，
    // **一律 `clickable=false`** —— 也就是说 `ACTION_CLICK` 在这页完全没用武之地。
    //
    // 幸好 [NodeClicker.click] 有**第三级回退**：前两级（自己可点 / 父可点）
    // 都不成立时，会在**节点中心落一次真实手势**。实测直接把手势打在
    // `Switch` 中心就能切换状态（`checked` true→false），而且**菜单不会关**，
    // 所以两个开关可以连着关。
    //
    // 匹配因此直接落在 `Switch` 自己身上（`target = Self`）——
    // 附带好处是**读回校验**也直接读它，不必再去找同行开关。
    // =========================================================================
    AdTask(
        id = "browser_homepage_simple",
        title = "浏览器 · 主页简洁版与首页模块",
        guide = "浏览器 → 设置 → 主页设置（简洁版）；回主页 → 右上角设置 → " +
            "关「快捷网址」「全网热榜」",
        allowedPackages = listOf("com.android.browser"),
        steps = listOf(
            // ① 直达浏览器设置页。见上面的说明：这个 Activity 导出着，不用点进去。
            AutomationStep.Launch(
                component = "com.android.browser/.BrowserSettingsActivity",
                label = "打开浏览器设置",
            ),

            // ② 等设置页。判据取这页独有的两个条目名 ——
            //    别用「设置」：那是标题栏文字，而且浏览器主页上也有个叫「设置」的图标。
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(text = "网页浏览设置", textMatch = MatchType.Exact),
                    Match(text = "密码管理", textMatch = MatchType.Exact),
                ),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待浏览器设置页",
            ),

            // ③ 点「主页设置」进子页。
            //
            // 那一行本身是 `clickable=true` 的 `ViewGroup`，文字节点在它里面 ——
            // 所以用 `ClickableAncestor` 从文字往上找可点的容器。
            AutomationStep.Click(
                match = Match(
                    text = "主页设置",
                    textMatch = MatchType.Exact,
                    maxTextLength = 4,
                    target = MatchTarget.ClickableAncestor,
                ),
                label = "点「主页设置」",
            ),

            // ④ 等子页出来。
            //
            // ⚠️ 判据必须用 `simple_rl` 这个 id，**不能**用 `text='主页设置'`：
            //    上一步那个条目名和子页的**标题栏文字完全一样**，
            //    拿它当"到了"的判据会在还没跳过去时就通过。
            AutomationStep.AwaitFor(
                match = Match(viewIdContains = "simple_rl"),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待「主页设置」子页",
            ),

            // ⑤ 选「简洁版」。
            //
            // ⚠️ 这一步**是幂等的，不需要"先判断再点"** —— 实测对着已经选中的
            //    简洁版再点一次，desc 仍是"简洁版主页已选中"，没有任何副作用。
            //    加一套"先看再看要不要点"的分支只会多一个出错的地方。
            AutomationStep.Click(
                matches = listOf(
                    Match(viewIdContains = "simple_rl"),
                    // 兜底：id 变了就按描述找。用 Contains 是因为
                    // 选中时 desc 会多出"已选中"后缀。
                    Match(
                        descContains = "简洁版主页",
                        target = MatchTarget.Self,
                    ),
                ),
                label = "选「简洁版」",
            ),

            // ⑥ 回浏览器主页。
            //
            // ⚠️ **这里不能用返回键** —— 实测踩过，而且踩得很典型：
            //
            //    第 ① 步是 `component` 直达设置页，默认带 `CLEAR_TASK`，
            //    所以浏览器任务栈里**只有 `BrowserSettingsActivity`**，
            //    `BrowserActivity`（主页）压根不在栈里。
            //    于是在设置页按返回：第一次退回「设置」主页（同一个 Activity
            //    内切 Fragment，不动栈），**第二次就直接出栈退出浏览器** ——
            //    日志里 `mAppVisible = true`，画面回到了本应用主页，
            //    后面几步全落在那儿，一路等到超时。
            //
            //    （手动试的时候"两次返回正好回主页"是个**假象**：那会儿
            //      浏览器是自己开过的，栈里有 `BrowserActivity`。）
            //
            // 正确做法是**重新打开浏览器主界面**（等价于点桌面图标）——
            // 这正是 [AutomationStep.Launch.appPackage] 存在的意义。
            // 实测在任务栈被清空的情况下，它照样稳定落到 `BrowserActivity`。
            //
            // ⚠️ **用默认的 `clearTask = true`**（理由同坑 #38）：
            //    这一步要的是"确定性地回到主页"，而 `NEW_TASK` 只保证"把浏览器的任务
            //    提到前台" —— 如果栈顶还压着上一轮的设置页，回来看到的就是它。
            //    清掉的只是**浏览器自己的**任务栈，不碰别的应用、也不丢用户数据。
            //
            //    ⚠️ 它与第 ① 步（直达 `BrowserSettingsActivity`）是配套的：
            //       直达那一步已经清过一次栈，这里再来一次是为了兜住
            //       "**用户自己开过浏览器、正停在某个子页**" 的情况。
            AutomationStep.Launch(
                appPackage = "com.android.browser",
                label = "回到浏览器主页",
            ),

            // ⑦ 等浏览器主页。
            //
            // 判据用右上角那个齿轮的 id：它**只在主页上存在**，
            // 而下一步正好要点它 —— 一个判据管两件事。
            AutomationStep.AwaitFor(
                match = Match(viewIdContains = "simple_page_settings"),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待浏览器主页",
            ),

            // ⑧ 点主页右上角的「设置」齿轮。
            //
            // ⚠️ **不要**图省事用 `descContains = "设置"`：主页左上角还有个
            //    `desc='退出'` 的图标（`iv_change_pro_ui`，点它会切回完整版主页），
            //    菜单里也一堆"设置"字样。锁 id 最稳。
            AutomationStep.Click(
                match = Match(viewIdContains = "simple_page_settings"),
                label = "点主页右上角设置",
            ),

            // ⑨ 等弹出菜单。判据取菜单里那个分组标题 + 开关 id ——
            //    两者都**不会在主页上出现**，所以不会误判成"菜单已经出来了"。
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(viewIdContains = "quick_link_check_box"),
                    Match(text = "页面布局", textMatch = MatchType.Exact),
                ),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待主页设置菜单",
            ),

            // ⑩ 关「快捷网址」。
            //
            // ⚠️ 主选直接锁 `Switch` 自己（理由见类文档：菜单里全节点
            //    `clickable=false`，靠 [NodeClicker.click] 第三级的"真实手势
            //    落在节点中心"生效）。这样读回校验也直接读它。
            AutomationStep.ToggleOff(
                matches = listOf(
                    Match(
                        viewIdContains = "quick_link_check_box",
                        target = MatchTarget.Self,
                    ),
                    // 兜底：id 变了就按文本锚点 → 同行开关。
                    Match(
                        text = "快捷网址",
                        textMatch = MatchType.Exact,
                        maxTextLength = 4,
                        target = MatchTarget.SwitchInSameRow,
                    ),
                ),
                label = "关闭「快捷网址」",
            ),

            // ⑪ 关「全网热榜」。
            //
            // ⚠️ 实测关掉上一项之后菜单**仍然开着**，所以这两步能连着走；
            //    但菜单也可能在别的情况下自己收起 —— 因此**别在它后面
            //    再放依赖这个菜单的步骤**。
            AutomationStep.ToggleOff(
                matches = listOf(
                    Match(
                        viewIdContains = "hot_words_check_box",
                        target = MatchTarget.Self,
                    ),
                    Match(
                        text = "全网热榜",
                        textMatch = MatchType.Exact,
                        maxTextLength = 4,
                        target = MatchTarget.SwitchInSameRow,
                    ),
                ),
                label = "关闭「全网热榜」",
            ),
        ),
    ),

    // =========================================================================
    // 16. 智能服务 · 通知与高级权限
    // =========================================================================
    //
    // ## ⚠️ 入口：**无法直达** —— MIUI 把这个入口转发给安全中心了
    //
    // 目标是「智能服务」（`com.miui.systemAdSolution`）在**系统设置**里的
    // 那个**谷歌原生**应用信息页（不是安全中心那个小米自己的页面）。
    //
    // 从 APK 清单里**确实**查到了独立组件：
    //
    // ```text
    // E: activity        name="com.android.settings.applications.InstalledAppDetailsTop"
    //                    exported=true
    // E: activity-alias  name="com.android.settings.applications.InstalledAppDetails"
    //                    targetActivity = 上面那个
    // ```
    //
    // ⚠️ 但它**进去之后会被 MIUI 甩走**。logcat 里能直接看到连着两条 START：
    //
    // ```text
    // START u0 {dat=package: flg=0x10008000
    //           cmp=com.android.settings/.applications.InstalledAppDetails}   ← 组件确实启动了
    //        ↓ 紧接着，由 com.android.settings 自己发出
    // START u0 {act=miui.intent.action.APP_MANAGER_APPLICATION_DETAIL
    //           cmp=com.miui.securitycenter/…ApplicationsDetailsActivity (has extras)}
    //           from uid 1000 … callingPackage com.android.settings            ← ★ 转发给安全中心
    // ```
    //
    // 也就是说：**既不是"没能启动"、也不是"data 没传进去"**，而是这个组件
    // 拿到 `package:` 之后**主动把用户转给安全中心**。所以这条直达路是封死的，
    // 调参数也没用 —— 别再往这儿花时间。
    //
    // | 试过的写法 | 结果 |
    // |---|---|
    // | `-n …InstalledAppDetails` / `…InstalledAppDetailsTop` ＋ `-d package:` | 都被转发到安全中心 |
    // | data 放最前 / 设备端加引号 / `cmd activity start-activity` | 同上 |
    // | `--es package …` | extras 进去了（`(has extras)`），同样被转发 |
    // | `-a android.settings.APPLICATION_DETAILS_SETTINGS -d package:` | 直接被路由到安全中心 |
    // | `-n …/.applications.appinfo.AppInfoDashboardActivity` | `Error type 3`（本 ROM 没有这个组件）|
    //
    // ⚠️ **别被 `dat=package:` 骗了**：`am` / `cmd activity` 打印 Intent 用的是
    //    `Intent.toString()` → `Uri.toSafeString()`，它**本来就会省略 URI 内容**
    //   （`-d https://example.com` 也只显示成 `dat=https://example.com/...`）。
    //    而上面第二条 START 带着 `(has extras)`，恰恰证明**包名是传进去了的** ——
    //    我先前把它读成"data 没传进去"，白查了好几轮。
    //
    // ## 实测路径
    //
    //   所有应用 → 底部「更多」→ 「显示系统应用」→ 滑到底找「智能服务」→ 点进去
    //
    // ## 实测结构
    //
    // **「所有应用」页**（`com.android.settings/.applications.ManageApplications`，
    // 可直达，标题栏是"所有应用"）。它底部有个**悬浮**的「更多」按钮：
    //
    // ```text
    // Button desc='更多'   clickable=true   bounds=[559,2876][881,3081]
    // ```
    //
    // 点开后是一个 PopupWindow 菜单：
    //
    // ```text
    // TextView text='显示系统应用'        id=title  clickable=false
    // TextView text='重置应用偏好设置'     id=title  clickable=false
    // ```
    //
    // ⚠️ 菜单项**自己不可点**，而这个 PopupWindow 也未必有可点的父容器 ——
    //    所以匹配一律用 `target = Self`，交给 [NodeClicker.click] 第三级的
    //    "真实手势落在节点中心"兜底（实测有效）。
    //
    // ⚠️ 「显示系统应用」是个**开关式**菜单项：已经显示着的时候它会变成
    //    「隐藏系统应用」。这里敢写死，是因为第 ① 步带了 `CLEAR_TASK` 重建
    //    列表页 —— 实测重进之后菜单**固定**是「显示系统应用」（幂等）。
    //
    // **智能服务的应用信息页**（`com.android.settings/.SubSettings`）：
    //
    // ```text
    // TextView id=entity_header_title  text='智能服务'        ← 判据用它
    // Button '停用' / Button '强行停止'
    // TextView '通知' / '每周大约 0 条通知'                    ← ★ 第一站
    // TextView '权限' / '附近的设备和通知'
    // TextView '存储空间和缓存' …
    // TextView '应用的电池使用情况' …
    // TextView '默认打开' / '允许'
    // TextView '闲置应用设置' → '管理闲置应用' [Switch]
    // 【高级】
    //   TextView '显示在其他应用的上层' / '不允许'             ← ★ 第二站
    //   TextView '修改系统设置' / '不允许'                     ← ★ 第三站
    // ```
    //
    // ⚠️ 后两项在页面**下半部分**，而且**每次从子页返回，滚动位置都会重置** ——
    //    所以每站之前都得重新 `ScrollTo`。
    //
    // 三个子页（**全都是同一个 `SubSettings`**，标题栏统统写着"设置"，
    // 所以**不能**拿标题栏当"到了"的判据）：
    //
    // ```text
    // 通知页：     TextView '允许通知'                 Switch id=switchWidget
    // 上层显示页：  TextView '允许显示在其他应用的上层'  Switch id=switchWidget
    // 修改设置页：  TextView '允许修改系统设置'         Switch id=switchWidget
    // ```
    //
    // ⚠️ 三个开关的 id **全都是 `switchWidget`**，区分只能靠**文本**。
    //    而子页的文本比列表项**多一个"允许"前缀**
    //   （`允许显示在其他应用的上层` vs `显示在其他应用的上层`）——
    //    所以一律 `MatchType.Exact`，**千万别用 Contains**：
    //    用 Contains 的话，"显示在其他应用的上层"会连应用信息页上那个入口行一起命中。
    //
    // ⚠️ 子页里那个外层 Switch **没有 `content-desc`**（只有内层的
    //    `id=switchWidget` 有状态可读），所以主选用"文本锚点 → 同行开关"
    //    （[MatchTarget.SwitchInSameRow]），再用 id 兜底。
    // =========================================================================
    AdTask(
        id = "systemad_solution_permissions",
        title = "智能服务 · 通知与高级权限",
        guide = "所有应用 → 底部更多 → 显示系统应用 → 智能服务 → " +
            "通知 / 显示在其他应用的上层 / 修改系统设置",
        allowedPackages = listOf("com.android.settings"),
        steps = listOf(
            // ① 打开「所有应用」页。
            //
            // ⚠️ 这一步**试过直达，走不通**：那个页面确实有导出的独立组件
            //   （`…applications.InstalledAppDetailsTop`），但 MIUI 会把它
            //    转发给安全中心 —— 详见类文档里的 logcat 证据。只能走 UI。
            //
            // ⚠️ `CLEAR_TASK` 在这里**不只是**为了"确保到列表页"——
            //    它还让第 ⑤ 步那个「显示系统应用」菜单项**固定出现**
            //   （不清栈时可能残留成"隐藏系统应用"）。两者见类文档。
            AutomationStep.Launch(
                component = "com.android.settings/.applications.ManageApplications",
                label = "打开「所有应用」",
            ),

            // ② 等列表页。判据取标题栏的"所有应用"（用 id 限定到标题栏那次命中）。
            AutomationStep.AwaitFor(
                matches = listOf(
                    Match(
                        text = "所有应用",
                        textMatch = MatchType.Exact,
                        viewIdContains = "action_bar_title_expand",
                    ),
                    Match(descContains = "所有应用"),
                ),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待「所有应用」页",
            ),

            // ③ 点底部那个悬浮的「更多」按钮。
            //
            // ⚠️ **首选那个 `clickable=false` 的文字节点**，而不是外面那个
            //    `Button`。理由：MIUI 给这个悬浮按钮注入的实现，对无障碍
            //    `ACTION_CLICK` 的响应**不可靠**（同坑 #3 —— 安全中心的列表行
            //    就是"返回 true 但界面没反应"），而 [NodeClicker.click] 只在
            //    "自己和父节点都不可点"时才会退到真实手势。
            //    挑文字节点能把第一级直接跳过，更容易落到手势上（实测坐标点击有效）。
            //
            // ⚠️ 也不能只用 `text = "更多"` 匹配：`action_bar` 里还有别的
            //    "更多"文案。锁到 `action_menu_item_child_text` 才唯一。
            AutomationStep.Click(
                matches = listOf(
                    Match(
                        text = "更多",
                        textMatch = MatchType.Exact,
                        viewIdContains = "action_menu_item_child_text",
                    ),
                    // 兜底：文字节点不在时，退回按按钮的 desc 找。
                    Match(
                        descContains = "更多",
                        className = "android.widget.Button",
                    ),
                ),
                label = "点底部「更多」",
            ),

            // ④ 等菜单弹出来。
            //
            // 判据用菜单里**另一条**固定项（"重置应用偏好设置"）而不是
            // "显示系统应用" —— 后者会随状态变文案，拿它当"菜单到了"的判据
            // 会在状态相反时白等一场。
            AutomationStep.AwaitFor(
                match = Match(text = "重置应用偏好设置", textMatch = MatchType.Exact),
                timeoutMs = 8_000L,
                retryable = true,
                label = "等待「更多」菜单",
            ),

            // ⑤ 点「显示系统应用」。
            //
            // ⚠️ `target = Self` 是**故意的**：菜单项自己 `clickable=false`，
            //    也没有可靠的父容器可点 —— 让 [NodeClicker.click] 走到第三级
            //    "真实手势落在节点中心"就行（实测有效）。
            AutomationStep.Click(
                match = Match(
                    text = "显示系统应用",
                    textMatch = MatchType.Exact,
                    target = MatchTarget.Self,
                ),
                label = "点「显示系统应用」",
            ),

            // ⑥ 往下翻着找「智能服务」。
            //
            // ⚠️ 这一项在列表**最末尾**（拼音 z 打头），而且实测还有两个坑：
            //
            // 1. **滚到最底部之后还要再滑一次**，那一段才露出来。
            // 2. 这一页**不能只靠 `ACTION_SCROLL_FORWARD`**：实测每按一次只挪
            //    一两行（还返回 true），于是额度很快耗尽 —— 30 次滚动只跑到 T~W
            //    段就报"滚到页底也没找到"（见坑 #40）。`ScrollTo` 现在会按
            //    "内容有没有变"自动改用**真实手势**一次滑一屏。
            //
            // `maxSwipes` 给到 45：那页有 130+ 个应用，一屏十几个，手势也要
            // 十几二十次；30 次实测**刚好不够**，45 留了余量。
            AutomationStep.ScrollTo(
                match = Match(text = "智能服务", textMatch = MatchType.Exact),
                maxSwipes = 45,
                label = "滚动查找「智能服务」",
            ),

            // ⑦ 点进应用信息页。
            AutomationStep.Click(
                match = Match(
                    text = "智能服务",
                    textMatch = MatchType.Exact,
                    maxTextLength = 4,
                    target = MatchTarget.ClickableAncestor,
                ),
                label = "点「智能服务」",
            ),

            // ⑧ 等应用信息页出来。
            //
            // ⚠️ 判据必须是 `entity_header_title`（页面里那个大字标题），
            //    不能只用 `text='智能服务'` —— 上一步那个列表项名字一模一样，
            //    不限定的话会在还没跳过去时就通过。
            AutomationStep.AwaitFor(
                match = Match(
                    text = "智能服务",
                    textMatch = MatchType.Exact,
                    viewIdContains = "entity_header_title",
                ),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待智能服务应用信息页",
            ),

            // ⑨ 点「通知」进第一站。
            // ⚠️ `maxTextLength = 2` + Exact：防止命中"通知管理"之类更长的词。
            AutomationStep.Click(
                match = Match(
                    text = "通知",
                    textMatch = MatchType.Exact,
                    maxTextLength = 2,
                    target = MatchTarget.ClickableAncestor,
                ),
                label = "点「通知」",
            ),

            // ⑩ 等通知页。
            // ⚠️ "允许通知"只在这页出现（应用信息页上那条叫"通知"），
            //    所以 Exact 匹配就够，不会误判。
            AutomationStep.AwaitFor(
                match = Match(text = "允许通知", textMatch = MatchType.Exact),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待通知页",
            ),

            // ⑪ 关「允许通知」。
            //
            // ⚠️ 只关这一个，**同页的「桌面角标」「悬浮通知」「锁屏通知」
            //    「发声」「振动」「常驻通知」一律不碰** —— 那些是正常的
            //    通知行为，不是广告。所以用 `ToggleOff` 而不是 `ToggleOffAll`。
            AutomationStep.ToggleOff(
                matches = listOf(
                    Match(
                        text = "允许通知",
                        textMatch = MatchType.Exact,
                        maxTextLength = 4,
                        target = MatchTarget.SwitchInSameRow,
                    ),
                    // 兜底：这一页只有它是 `switchWidget`（其余都是 `checkbox`）。
                    Match(
                        viewIdContains = "switchWidget",
                        target = MatchTarget.Self,
                    ),
                ),
                label = "关闭「允许通知」",
            ),

            // ⑫ 返回应用信息页。
            //
            // ⚠️ 带**守卫**：只有确实还停在通知子页上才按。
            //    万一前面某一步没把我们带进来（比如页面加载慢、而 `Click` 已经判成功），
            //    这一下就会把**应用信息页也退掉**，后面两站全落在列表页上。
            AutomationStep.PressBack(
                guard = Match(text = "允许通知", textMatch = MatchType.Exact),
            ),

            // ⑬ 往下翻着找「显示在其他应用的上层」（第二站）。
            // ⚠️ 返回之后滚动位置**会重置**，所以要重新滚。
            AutomationStep.ScrollTo(
                match = Match(text = "显示在其他应用的上层", textMatch = MatchType.Exact),
                label = "滚动查找「显示在其他应用的上层」",
            ),

            // ⑭ 点进去。
            AutomationStep.Click(
                match = Match(
                    text = "显示在其他应用的上层",
                    textMatch = MatchType.Exact,
                    target = MatchTarget.ClickableAncestor,
                ),
                label = "点「显示在其他应用的上层」",
            ),

            // ⑮ 等子页。⚠️ 这里的"允许"前缀是**必须的** ——
            //    少了它就会命中上一页那个入口行（见类文档）。
            AutomationStep.AwaitFor(
                match = Match(
                    text = "允许显示在其他应用的上层",
                    textMatch = MatchType.Exact,
                ),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待「显示在其他应用的上层」页",
            ),

            // ⑯ 关掉它。
            AutomationStep.ToggleOff(
                matches = listOf(
                    Match(
                        text = "允许显示在其他应用的上层",
                        textMatch = MatchType.Exact,
                        target = MatchTarget.SwitchInSameRow,
                    ),
                    Match(
                        viewIdContains = "switchWidget",
                        target = MatchTarget.Self,
                    ),
                ),
                label = "关闭「允许显示在其他应用的上层」",
            ),

            // ⑰ 返回应用信息页。
            // ⚠️ 同样带守卫，理由见第 ⑫ 步。
            AutomationStep.PressBack(
                guard = Match(
                    text = "允许显示在其他应用的上层",
                    textMatch = MatchType.Exact,
                ),
            ),

            // ⑱ 往下翻着找「修改系统设置」（第三站）。
            AutomationStep.ScrollTo(
                match = Match(text = "修改系统设置", textMatch = MatchType.Exact),
                label = "滚动查找「修改系统设置」",
            ),

            // ⑲ 点进去。
            AutomationStep.Click(
                match = Match(
                    text = "修改系统设置",
                    textMatch = MatchType.Exact,
                    target = MatchTarget.ClickableAncestor,
                ),
                label = "点「修改系统设置」",
            ),

            // ⑳ 等子页。
            AutomationStep.AwaitFor(
                match = Match(text = "允许修改系统设置", textMatch = MatchType.Exact),
                timeoutMs = 10_000L,
                retryable = true,
                label = "等待「修改系统设置」页",
            ),

            // ㉑ 关掉它。
            AutomationStep.ToggleOff(
                matches = listOf(
                    Match(
                        text = "允许修改系统设置",
                        textMatch = MatchType.Exact,
                        target = MatchTarget.SwitchInSameRow,
                    ),
                    Match(
                        viewIdContains = "switchWidget",
                        target = MatchTarget.Self,
                    ),
                ),
                label = "关闭「允许修改系统设置」",
            ),
        ),
    ),

    // =========================================================================
    // 后续按同样的形状追加……
    //
    // AdTask(
    //     id = "mi_xxx",
    //     title = "开关名",
    //     guide = "设置 → … → …",
    //     allowedPackages = listOf("com.android.settings"),
    //     steps = listOf(
    //         AutomationStep.Launch("android.settings.SETTINGS"),
    //         ... 走到那个页面的步骤 ...
    //         AutomationStep.ToggleOff(
    //             matches = listOf(
    //                 Match(descContains = "开关名", className = "android.widget.Switch", target = MatchTarget.Self),
    //                 Match(text = "开关名", target = MatchTarget.SwitchInSameRow, onlyWhenChecked = true),
    //             ),
    //         ),
    //     ),
    // ),
    // =========================================================================
)

/**
 * 本机**实际可处理**的任务 —— 把依赖的应用不存在（或已被停用）的项滤掉。
 *
 * ## 为什么需要"跳过"而不是让它失败
 *
 * 清单里的项散落在好几个独立应用里，而**用户可以只卸载其中一部分**：
 * 天气、日历是小米预装里最常被删的，应用商店也有不少人不用，垃圾清理同理。
 *
 * 没有这道过滤的话，那些项会在第 1 步"打开 XX"就卡住 —— 应用根本不存在，
 * `startActivity` 解析不到，只能干等到超时，然后**在清单里留下一条永远失败的红项**。
 * 用户既没法处理，也不知道为什么失败。
 *
 * 跳过是**如实告知**：这一项在这台机器上无从下手，不是我们没做到。
 *
 * ## ⚠️ 判据是"包的可用性"，不是"包名在不在"
 *
 * 见 [isPackageUsable]：除了"没装"，还包含**被停用**这一种 ——
 * 停用的应用同样启动不了，但它确实还在 `packageManager` 里。
 * 只看"能不能查到"会漏掉这种，然后照样白等超时。
 *
 * ## ⚠️ 刻意**不**把跳过写进"已完成"记录
 *
 * 用户哪天把天气装回来了，这一项应当**重新回到待处理**。
 * 若把它记成"已完成"，那个开关就永远没人处理了。
 */
internal fun usableAdTasks(context: Context): List<AdTask> =
    BuiltinAdTasks.filter { task ->
        task.requiredPackages.all { context.isPackageUsable(it) }
    }

/**
 * 某个任务在本机**是否可用**（它依赖的包是否都在）。
 *
 * 界面用它给清单行标"未安装"，引擎用它决定跳过 —— 两边共用同一份判据，
 * 避免出现"界面上还能点、点下去引擎却跳过"这种不一致。
 */
internal fun AdTask.isUsable(context: Context): Boolean =
    requiredPackages.all { context.isPackageUsable(it) }
