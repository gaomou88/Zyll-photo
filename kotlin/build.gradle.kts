plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 重新生成 ../spec/test-vectors：./gradlew run
application { mainClass.set("app.zyll.crypto.vectors.GenerateVectorsKt") }
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    args(rootProject.file("../spec/test-vectors").absolutePath)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("zyll.vectorsDir", rootProject.file("../spec/test-vectors").absolutePath)
}
