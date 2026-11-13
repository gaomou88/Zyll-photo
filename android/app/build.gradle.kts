import java.util.Properties

import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 包名可在 gradle.properties 用 zyll.appId 覆盖（开源版用的是另一个包名）
val appId = providers.gradleProperty("zyll.appId").getOrElse("com.zyll.photo")

android {
    namespace = "app.zyll.gallery"
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.1.6"
        // 网盘登录（AppAuth）回调用的 scheme
        manifestPlaceholders["appAuthRedirectScheme"] = appId
    }

    // 开源版只有一个发行变体
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            versionNameSuffix = "-github"
        }
    }

    // 发布签名从 keystore.properties 或环境变量读取；都没有时 release 包不签名
    val keyProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }
    fun key(name: String, env: String): String? = keyProps.getProperty(name) ?: System.getenv(env)
    val storePath = key("storeFile", "ZYLL_STORE_FILE")
    signingConfigs {
        if (storePath != null) create("release") {
            storeFile = file(storePath)
            storePassword = key("storePassword", "ZYLL_STORE_PASSWORD")
            keyAlias = key("keyAlias", "ZYLL_KEY_ALIAS")
            keyPassword = key("keyPassword", "ZYLL_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (storePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }


    // 应用内语言：英文（默认）、简体中文、繁体中文、日文。Android 13+ 可以在系统设置里单独给 App 选语言
    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    lint {
        // 试用阶段的 release 包不因 lint 中断构建，正式发布前再打开
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/LICENSE*", "META-INF/NOTICE*")
        // GPUImage 自带的原生库只按 4 KB 对齐（16 KB 内存页的手机装不上），而且只给相机预览和它自己的离屏读图用；
        // 修图改用 GpuRender（纯 Java 读图），用不到它
        jniLibs.excludes += "**/libyuv-decoder.so"
        // 原生库不压缩、按 16 KB 对齐放进安装包（AGP 8.5.1+ 默认就是这样，这里写明）
        jniLibs.useLegacyPackaging = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:crypto"))
    implementation(project(":feature:gallery"))
    implementation(project(":core:cloud"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons)
    implementation(libs.coil.compose)

}
