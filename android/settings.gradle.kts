pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.2.10"
        id("org.jetbrains.kotlin.android") version "2.2.10"
        id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"
    }
}

val jvmOnly = providers.gradleProperty("zyll.jvmOnly").isPresent

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!jvmOnly) google()
        mavenCentral()
    }
}

rootProject.name = "zyll-android"

// 纯 JVM 模块：不需要 Android SDK，可在任何机器上跑测试向量
include(":core:crypto")
include(":core:search")
include(":core:backup")

// `-Pzyll.jvmOnly` 时跳过 Android 模块（用于没有 Android SDK 的环境）
if (!jvmOnly) {
    include(":app")
    include(":core:media")
    include(":core:ai")
    include(":feature:gallery")
    // 网盘接口（Google Drive / OneDrive）与后台备份
    include(":core:cloud")
}
