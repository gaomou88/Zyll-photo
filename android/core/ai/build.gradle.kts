plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// 开源版不含端侧 AI：这个模块只保留空的接口，让界面代码照常编译。
android {
    namespace = "app.zyll.core.ai"
    compileSdk = 36
    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:search"))
    implementation(project(":core:media"))
    implementation(libs.kotlinx.coroutines.android)
}
