package org.dpdns.mfsky.skipad

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 冒烟测试：确认被测包属于本项目、且版本信息可读。
 *
 * ⚠️ debug 变体的 `applicationId` 带 `.debug` 后缀，所以**不能**写
 *    `assertEquals("org.dpdns.mfsky.skipad", packageName)` —— 那样 debug 下必挂。
 *    这里只校验主包名前缀。
 */
@RunWith(AndroidJUnit4::class)
class PackageSmokeTest {

    @Test
    fun packageNameBelongsToThisProject() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "意外的包名：${appContext.packageName}",
            appContext.packageName.startsWith("org.dpdns.mfsky.skipad"),
        )
    }

    @Test
    fun versionInfoIsAvailable() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // minSdk 33 起用 PackageInfoFlags 重载，避开旧 API 的弃用告警。
        val info = appContext.packageManager
            .getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0))
        assertTrue("versionName 缺失", !info.versionName.isNullOrBlank())
        assertTrue("versionCode 非法", info.longVersionCode > 0)
    }
}
