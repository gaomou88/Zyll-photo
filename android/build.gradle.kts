// AGP 只在需要 Android 模块时才放进 classpath，这样没有 Android SDK 的机器
// 也能用 `-Pzyll.jvmOnly` 构建并测试纯 JVM 的 core:crypto。
buildscript {
    val jvmOnly = gradle.startParameter.projectProperties.containsKey("zyll.jvmOnly")
    repositories {
        if (!jvmOnly) google()
        mavenCentral()
    }
    dependencies {
        if (!jvmOnly) classpath("com.android.tools.build:gradle:8.12.0")
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.android") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("org.jetbrains.kotlin.plugin.serialization") apply false
}
