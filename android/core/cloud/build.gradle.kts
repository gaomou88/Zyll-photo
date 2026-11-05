plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// 网盘客户端 ID 写在 android/gradle.properties（也可以写在 ~/.gradle/gradle.properties 覆盖）：
//   zyll.googleClientId=xxxx.apps.googleusercontent.com
//   zyll.onedriveClientId=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
// 属性为空时再看环境变量 ZYLL_GOOGLE_CLIENT_ID / ZYLL_ONEDRIVE_CLIENT_ID。都为空时界面上显示“未配置”。
fun secret(prop: String, env: String): String =
    providers.gradleProperty(prop).getOrElse("").trim().ifEmpty { providers.environmentVariable(env).getOrElse("").trim() }

// 应用包名（也是 Google 登录回调的 scheme、OneDrive 回调的 host），见 gradle.properties 的 zyll.appId
val appId = providers.gradleProperty("zyll.appId").getOrElse("com.zyll.photo")
// OneDrive（Azure “Android”平台）登记的签名哈希，已做 URL 编码；为空时 OneDrive 显示“未配置”
val onedriveSigHash = secret("zyll.onedriveSignatureHash", "ZYLL_ONEDRIVE_SIGNATURE_HASH")

android {
    namespace = "app.zyll.core.cloud"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${secret("zyll.googleClientId", "ZYLL_GOOGLE_CLIENT_ID")}\"")
        buildConfigField("String", "ONEDRIVE_CLIENT_ID", "\"${secret("zyll.onedriveClientId", "ZYLL_ONEDRIVE_CLIENT_ID")}\"")
        buildConfigField("String", "APP_ID", "\"$appId\"")
        buildConfigField("String", "ONEDRIVE_SIGNATURE_HASH", "\"$onedriveSigHash\"")
        // AppAuth 接收登录回调的 scheme，等于应用包名
        manifestPlaceholders["appAuthRedirectScheme"] = appId
        manifestPlaceholders["msauthHost"] = appId
    }
    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:backup"))
    // 端到端加密（ZYLC 容器 + 密钥包），与网页端 / 其他设备共用同一格式
    implementation(project(":core:crypto"))
    implementation(project(":core:media"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.appauth)
    implementation(libs.androidx.work)
}
