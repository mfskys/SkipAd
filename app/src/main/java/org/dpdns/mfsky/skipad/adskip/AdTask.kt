package org.dpdns.mfsky.skipad.adskip

/** 文本匹配方式。默认 [Contains]：系统文案常带后缀或版本差异。 */
enum class MatchType {
    /** 完全相等。 */
    Exact,

    /** 以该文本开头。 */
    Prefix,

    /** 包含该文本。 */
    Contains,
}

/**
 * 命中锚点后要操作哪个节点。
 *
 * 锚点是"认出这个开关在哪"的节点（通常是 TextView），它本身往往不是开关。
 */
enum class MatchTarget {
    /** 直接操作锚点自身。 */
    Self,

    /** 锚点所在同一行里的开关；配合 [Match.onlyWhenChecked] 做到幂等。 */
    SwitchInSameRow,

    /** 从锚点向上找最近的可点击祖先（如搜索框的文字节点不可点）。 */
    ClickableAncestor,
}

/**
 * 一条匹配条件。多个候选之间是 **OR**，字段之间是 **AND**。
 *
 * @param text 锚点文本
 * @param textMatch 文本匹配方式
 * @param viewIdContains viewId 子串（资源 id 带包名，所以用"包含"）
 * @param descContains contentDescription 的匹配值
 * @param descMatch 其匹配方式；底部导航这类场景**必须**用 [MatchType.Exact]
 *   （否则会先命中「我的钱包」「我的消息」等更靠前的节点）
 * @param className 类名（全等）
 * @param clickable 要求可点击；用于锁定节点、排除同名干扰项
 * @param editable 要求可编辑（锁定输入框）
 * @param focused 要求持有焦点。MIUI 设置首页入口与搜索页输入框的 id / 文案相同，
 *   唯一稳定差异是后者进入时自动持焦
 * @param maxTextLength 文本长度上限，防误点
 * @param target 命中后要操作的目标
 * @param onlyWhenChecked 只在开关处于打开状态时才点
 * @param clickFraction 手势落点改用节点内的相对位置（0f..1f）。
 *   仅用于"节点边界 ≠ 热区"的自绘页面；指定后直接走真实手势
 */
data class Match(
    val text: String? = null,
    val textMatch: MatchType = MatchType.Contains,
    val viewIdContains: String? = null,
    val descContains: String? = null,
    val descMatch: MatchType = MatchType.Contains,
    val className: String? = null,
    val clickable: Boolean? = null,
    val editable: Boolean? = null,
    val focused: Boolean? = null,
    val maxTextLength: Int? = null,
    val target: MatchTarget = MatchTarget.Self,
    val onlyWhenChecked: Boolean = false,
    val clickFraction: Pair<Float, Float>? = null,
)

/**
 * 一个待处理项 —— 本应用的工作单元。
 *
 * @param id 稳定标识，用于记录"已完成"；**改动它等于让用户重新处理一遍**。
 * @param title 展示名
 * @param guide 失败时给用户照着手动走的路径
 * @param allowedPackages 防呆判据：前台不在这些包里（且不是本应用）就中止并重跑
 * @param steps 按顺序执行的步骤，见 [AutomationStep]
 */
data class AdTask(
    val id: String,
    val title: String,
    val guide: String,
    val allowedPackages: List<String> = emptyList(),
    val steps: List<AutomationStep>,
)

/**
 * 任务依赖的应用包 —— 从步骤里的 [AutomationStep.Launch] 自动推导。
 *
 * 用于"应用没装/被停用就跳过"，避免清单里留一条永远失败的红项。
 * 隐式 `action` 指向系统标准页面，推不出也不需要检查。
 *
 * ⚠️ 不能复用 [AdTask.allowedPackages]：那是路径上会经过的所有包，不是"必需存在"。
 */
val AdTask.requiredPackages: List<String>
    get() = steps
        .filterIsInstance<AutomationStep.Launch>()
        .mapNotNull { step ->
            when {
                // 显式组件：格式固定为 `包名/类名`，取斜杠前那段。
                step.component != null ->
                    step.component.substringBefore('/').takeIf(String::isNotBlank)

                // "点桌面图标"式启动：包名原样就是它。
                step.appPackage != null -> step.appPackage

                // 隐式 action / 纯 data：推不出目标应用（也不需要检查）。
                else -> null
            }
        }
        .distinct()
