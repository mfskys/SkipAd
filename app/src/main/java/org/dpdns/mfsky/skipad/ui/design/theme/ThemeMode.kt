package org.dpdns.mfsky.skipad.ui.design.theme

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 应用内可选的主题模式。
 *
 * 默认 [FollowSystem]：绝大多数用户不需要动这个开关 ——
 * 独立深浅色开关是给「系统深色下想看浅色」这类少数场景准备的。
 */
enum class ThemeMode(val label: String, val description: String) {
    FollowSystem("跟随系统", "与系统深色模式保持一致（默认）"),
    Light("浅色", "始终使用浅色主题"),
    Dark("深色", "始终使用深色主题"),
}

/**
 * 主题偏好的**唯一事实源（SSOT）**：DataStore preferences。
 *
 * 设计要点：
 * - 对外只暴露 `Flow`（读）与 suspend 写函数，**不暴露可变状态** ——
 *   调用方（MainActivity）`collectAsState`，写入方（设置页）挂协程作用域调用，
 *   不存在"两份状态可能不同步"的问题。
 * - 存 `ordinal` 而不是名字字符串：enum 顺序由本文件控制，改名不会破坏已存数据
 *   （但**不能调整 entries 的既有顺序**，只能往尾部追加 —— 新值读取用 `getOrElse` 兜底）。
 * - 文件很小（一个 int），读写开销可忽略，不需要内存缓存层。
 *
 * ⚠️ 只在**这一个**文件里声明这个 DataStore。**不要**在别的文件里再写一次同名的
 * `preferencesDataStore` —— 同一个文件名存在多个 DataStore 实例时，运行期会直接抛异常。
 */
private val Context.appearanceDataStore by preferencesDataStore(name = "appearance")

object ThemeRepository {

    private val KEY_THEME_MODE = intPreferencesKey("theme_mode")

    /** 当前主题模式。无历史记录时回落 [ThemeMode.FollowSystem]。 */
    fun themeMode(context: Context): Flow<ThemeMode> =
        context.appearanceDataStore.data.map { prefs ->
            ThemeMode.entries.getOrElse(prefs[KEY_THEME_MODE] ?: 0) { ThemeMode.FollowSystem }
        }

    suspend fun setThemeMode(context: Context, mode: ThemeMode) {
        context.appearanceDataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.ordinal
        }
    }
}
