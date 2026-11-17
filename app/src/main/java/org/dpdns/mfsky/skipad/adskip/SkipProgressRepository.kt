package org.dpdns.mfsky.skipad.adskip

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 处理进度（哪些任务已处理完）的唯一事实源。
 *
 * 中断后再开始时，整项跳过已完成的任务，不重新走页面。
 * 只记成功的（失败下次重试）；记录只增不减，除非用户点「重新处理全部」。
 *
 * ⚠️ 这个 DataStore 只能在本文件声明；同名文件存在多个实例会在运行期抛异常。
 */
private val Context.skipProgressDataStore by preferencesDataStore(name = "skip_progress")

object SkipProgressRepository {

    private val KEY_DONE_TASKS = stringSetPreferencesKey("done_task_ids")

    /** 已完成的任务 id 集合。无记录时为空集。 */
    fun doneTaskIds(context: Context): Flow<Set<String>> =
        context.skipProgressDataStore.data.map { it[KEY_DONE_TASKS] ?: emptySet() }

    /** 标记某个任务已完成。 */
    suspend fun markDone(context: Context, taskId: String) {
        context.skipProgressDataStore.edit { prefs ->
            prefs[KEY_DONE_TASKS] = (prefs[KEY_DONE_TASKS] ?: emptySet()) + taskId
        }
    }

    /** 清空全部进度 —— 「重新处理全部」用。 */
    suspend fun clear(context: Context) {
        context.skipProgressDataStore.edit { prefs ->
            prefs.remove(KEY_DONE_TASKS)
        }
    }
}
