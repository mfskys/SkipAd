package org.dpdns.mfsky.skipad.ui.design.component

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.dpdns.mfsky.skipad.adskip.AdSkipRuntime

/**
 * 项目对外链接。
 *
 * 目前只有设置页「关于」组使用；链接与打开规则集中在这里，页面只调用 [openUrl]。
 */

/** 开源仓库地址（网页地址，不带 `.git`）。 */
const val PROJECT_REPO_URL = "https://github.com/mfskys/SkipAd"

/** 界面上的显示形式（去掉协议前缀）。 */
const val PROJECT_REPO_LABEL = "github.com/mfskys/SkipAd"

/**
 * 用系统默认方式打开链接。
 *
 * ⚠️ `FLAG_ACTIVITY_NEW_TASK` 不能省：从应用上下文启动 Activity 时缺了它会抛异常。
 *
 * @return 是否成功发出；没有任何应用能处理时返回 false，由调用方给用户反馈。
 */
fun openUrl(context: Context, url: String): Boolean = runCatching {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}.onFailure { e ->
    AdSkipRuntime.log("打开链接失败：${e.javaClass.simpleName}: ${e.message}")
}.isSuccess
