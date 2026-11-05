plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.bouncycastle)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// 生成 spec/test-vectors：./gradlew -Pzyll.jvmOnly :core:crypto:run
application { mainClass.set("app.zyll.crypto.vectors.GenerateVectorsKt") }
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    args(rootProject.file("../spec/test-vectors").absolutePath)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("zyll.vectorsDir", rootProject.file("../spec/test-vectors").absolutePath)
}
