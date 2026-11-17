// 强制把 KGP / KSP 拉到本项目声明的版本。
// AGP 9.x 会内置自己的 KGP，若不强制对齐，release 构建阶段
// produceReleaseComposeMapping 会去解析不存在的 compose-group-mapping:<内置版本>。
// ⚠️ 这段不要删。
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:${libs.versions.ksp.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
