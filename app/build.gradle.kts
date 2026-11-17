import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ⚠️ 版本号只有这一处来源：`defaultConfig` 与 APK 产物命名都用它。
// 发布纪律：每次发版 `appVersionCode` **必须 +1**（Android 靠它判断"能不能覆盖升级"），
// `appVersionName` 给人看（语义化，如 1.1.0）。
val appVersionName = "1.1"
val appVersionCode = 2

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // ⚠️ 这里**不能**用 version catalog 的 alias 形式：`kotlin-parcelize` 已随 Kotlin 插件
    // 进入 classpath，再显式指定 version 会报
    // 「already on the classpath with an unknown version」。
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    namespace = "org.dpdns.mfsky.skipad"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "org.dpdns.mfsky.skipad"
        minSdk = 33
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ── 签名：口令**只从属性 / 环境变量读，绝不入库** ──────────────────────
    //
    // 四个键给齐才启用（缺一个就出未签名包，便于没有密钥的机器上也能编译 release）。
    // 本机用法：在 `~/.gradle/gradle.properties` 里写下面四行（或设同名环境变量）：
    //
    //     RELEASE_STORE_FILE=../keys/skipad.jks
    //     RELEASE_STORE_PASSWORD=…
    //     RELEASE_KEY_ALIAS=skipad
    //     RELEASE_KEY_PASSWORD=…
    //
    // ⚠️ 这个 keystore **发布之后就不能换**：Android 只认签名，换密钥老用户无法覆盖
    //    安装（只能卸载重装、丢数据）。而它**丢了**同样发不了升级包。
    //    → 生成后立刻备份到仓库之外的至少两处（见 PROJECT.md「发布与加固」）。
    // ⚠️ 别把 keystore 放进仓库：`.gitignore` 已排除 `*.jks` / `*.keystore`。
    signingConfigs {
        create("release") {
            val storePath = (project.findProperty("RELEASE_STORE_FILE") as String?)
                ?: System.getenv("RELEASE_STORE_FILE")
            val storePwd = (project.findProperty("RELEASE_STORE_PASSWORD") as String?)
                ?: System.getenv("RELEASE_STORE_PASSWORD")
            val alias = (project.findProperty("RELEASE_KEY_ALIAS") as String?)
                ?: System.getenv("RELEASE_KEY_ALIAS")
            val keyPwd = (project.findProperty("RELEASE_KEY_PASSWORD") as String?)
                ?: System.getenv("RELEASE_KEY_PASSWORD")
            if (storePath != null && storePwd != null && alias != null && keyPwd != null) {
                storeFile = file(storePath)
                storePassword = storePwd
                keyAlias = alias
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        debug {
            // ⚠️ 没有这个后缀，debug 包会**覆盖**已装的正式包（applicationId 相同），
            //    两个版本无法共存，而且会把正式版的数据一起换掉。
            //    本项目的「处理记录」是本地数据，两个包共用 applicationId 会互相覆盖。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }

        release {
            // ── R8：代码收缩 + 混淆 + 优化 ──
            optimization {
                enable = true
                // ⚠️ **默认值就是 `"**"`（全部包）**，这里显式写出来是防呆：
                //    曾经这里写的是 `setOf("androidx.**", "kotlin.**", "kotlinx.**")`，
                //    那是"只优化这三个包" —— 结果是**本项目自己的代码反而不混淆**，
                //    与"上 R8 加密"的目的正好相反。别改小它。
                packageScope = setOf("**")
                // 默认规则（等价于 `getDefaultProguardFile("proguard-android-optimize.txt")`）
                // 默认就是开的；显式写出来，是让"改它的人必须先看到这一行"。
                keepRules { includeDefault = true }
            }
            // 资源收缩：删掉无人引用的 res（配合下面 lint 保留普通检查）
            isShrinkResources = true
            // ⚠️ 自定义规则文件放在 app/proguard-rules.pro（模块根目录）。
            //    默认规则已由上面 keepRules.includeDefault 带入，不必重复 getDefaultProguardFile()。
            proguardFiles("proguard-rules.pro")

            // 签名：四个键齐了才挂（软开关，见上面的 signingConfigs）
            val signingKeys = listOf(
                "RELEASE_STORE_FILE",
                "RELEASE_STORE_PASSWORD",
                "RELEASE_KEY_ALIAS",
                "RELEASE_KEY_PASSWORD",
            )
            if (signingKeys.all { project.findProperty(it) != null || System.getenv(it) != null }) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }
    buildFeatures {
        compose = true
    }
}

// ── 产物命名：把变体与版本写进文件名 ────────────────────────────────────────
// 否则满目录 `app-release.apk` / `app-debug.apk`，分发时根本分不清是哪个版本。
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("澎湃SkipAd_${variant.name}_${appVersionName}.apk")
        }
    }
}

dependencies {
    // ---- Compose BOM：ui / foundation / runtime / animation / material3 / adaptive 全部交给它 ----
    implementation(platform(libs.androidx.compose.bom))

    // ---- Compose 核心（不写版本）----
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.animation)

    // ---- Material 3 Expressive + 自适应 ----
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.navigation.suite)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.compose.material3.adaptive.navigation3)
    lintChecks(libs.androidx.compose.material3.lint)

    // ---- AndroidX（BOM 不管，显式版本）----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.window)
    implementation(libs.androidx.datastore.preferences)

    // ---- 序列化（Nav3 的 NavKey 需要 @Serializable）----
    implementation(libs.kotlinx.serialization.core)

    // ---- Shizuku：借 shell 身份执行命令 ----
    //
    // 用途只有一处：「点一下直接打开无障碍」（见 util/ShizukuShell.kt）——
    // 省掉"跳系统设置 → 找无障碍 → 找本应用 → 拨开关 → 返回"那一串。
    // ⚠️ 它**不是**必需依赖：没装 / 没运行 Shizuku 时，那条路径原样降级回系统设置页。
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // ---- UI 资源 ----
    implementation(libs.icons.symbols.rounded)
    implementation(libs.icons.symbols.rounded.filled)

    // ---- 测试 ----
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
