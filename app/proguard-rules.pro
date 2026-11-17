# =============================================================================
# SkipAd · R8 规则（release 专用）
#
# ## 写法纪律
#
# 这里**只放"必须保"的**，每一条都要写清"为什么"和"依据在哪个文件" ——
# 否则以后没人敢删。Compose / Navigation / DataStore / AndroidX 这些库自带的
# consumer 规则已经够用，**不要**顺手加 `-keep class androidx.** { *; }` 那种：
# 多写一条 = 多留一批可反编译的符号，等于自己削弱混淆。
#
# ## 验证方式
#
# 1. **这类问题只有 release 才暴露**（debug 不混淆）—— 改完规则必须打 release
#    并在真机启动一次。
# 2. 不靠猜：`app/build/outputs/mapping/release/` 下的
#    `seeds.txt`（被保下来的）、`usage.txt`（被删掉的）、`mapping.txt`（反解线上堆栈）。
# =============================================================================


# ── ① Shizuku：我们反射调它的 private 方法 ──────────────────────────────────
#
# 依据：`util/ShizukuShell.kt` 通过 `Shizuku::class.java.declaredMethods`
#       反射调用 `private static Shizuku.newProcess(String[], String[], String)`。
#       它**没有**混淆保护就不能混淆 —— 方法名/签名任一被改，
#       "点一下直接开无障碍"会**静默失效**（`NoSuchMethodException` 被 runCatching 吃掉）。
#
# 整包保留。代价是 rikka.shizuku 这部分不混淆 ——
# 但它本来就是个公开库，保它不是"泄密"，而是"保功能"。
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**


# ── ② kotlinx.serialization：Nav3 的 NavKey 靠它保存返回栈 ──────────────────
#
# `@Serializable` / `Companion` / `serializer()` / `keepattributes` 已由
# kotlinx-serialization 的 consumer 规则覆盖（见 `mapping/release/configuration.txt`），
# 这里只补一条更强的：整个 `$$serializer` 类保留，
# 防止 R8 把它判成"没人调用"而删掉（失败只在运行期暴露：返回栈恢复不了）。
-keep,includedescriptorclasses class org.dpdns.mfsky.skipad.**$$serializer { *; }
-dontwarn kotlinx.serialization.**


# ── ③ 无障碍服务：类名**必须是这个字符串** ─────────────────────────────────
#
# `adskip/AdSkipAccessibilityService.kt` 里 `AdSkipPermission.SERVICE_COMPONENT`
# 是一个硬编码字符串，用来：
#   · 与系统设置 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 比对；
#   · 判断"服务到底开没开"。
# 它同时也在 AndroidManifest 里被引用（AGP 会自动保名），这里再显式写一遍是
# **零成本的保险**：只保名字，不冻结成员，仍允许混淆方法名。
-keepnames class org.dpdns.mfsky.skipad.adskip.AdSkipAccessibilityService


# ── ④ 加固（成本很低，收益是"反编译时少几条线索"）──────────────────────────
#
# 源码文件名不进 class（否则反编译能看到 `Foo.kt`，等于把代码结构送出去）
-renamesourcefileattribute SourceFile
# 类名不用大小写混合 —— 避免在大小写不敏感的文件系统上解包冲突
-dontusemixedcaseclassnames


# ── ⑤ 刻意**不**做的事（写出来免得后人手痒）────────────────────────────────
#
# · 不裁日志：本项目的排查链路**就是** logcat（`AdSkipRuntime.log` 走 `Log.d`），
#   裁掉等于自断手脚。真要裁请另开一个 buildType
#   （多一个 `releaseLogs` 变体保留日志，release 才裁）。
# · 不加 `-repackageclasses` / `-allowaccessmodification`：
#   收益是"包结构不可辨"，代价是 release 崩溃风险上升，且可能影响
#   `serialization` 这类按包名/类名找东西的逻辑。
#   若哪天要开，**必须**同时保留 AdSkipAccessibilityService 的类名（见 ③）。
# · 不做字符串加密 / 字典混淆：那是商用加固的范畴，与 R8 无关；
#   本项目是开源工具，收益远小于"以后自己排不出崩溃"的成本。
