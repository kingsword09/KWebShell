import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
    jvmToolchain(25)
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_25) } }
    sourceSets {
        commonMain.dependencies {
            api(project(":kweb-services-core"))
            api(project(":kweb-bridge"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies { implementation(libs.kotlinx.coroutines.core) }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            runtimeOnly(libs.junit.platform.launcher)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

val bridgeCodegen = configurations.create("bridgeCodegen")
dependencies { bridgeCodegen(project(":kweb-bridge-codegen")) }

val bridgeSchema = layout.projectDirectory.file("src/mainBridge/notifications-bridge.json")
val generatedBridgeDirectory = layout.buildDirectory.dir("generated/kwebBridge/notifications")
val generateNotificationsBridge = tasks.register<JavaExec>("generateNotificationsBridge") {
    group = "build"
    description = "Generates the KWebNotifications Kotlin and TypeScript bridge clients."
    classpath = bridgeCodegen
    mainClass.set("io.github.kingsword09.kwebshell.bridge.codegen.MainKt")
    inputs.file(bridgeSchema)
    outputs.dir(generatedBridgeDirectory)
    args(bridgeSchema.asFile.absolutePath, generatedBridgeDirectory.get().asFile.absolutePath)
}

kotlin.sourceSets.named("jvmMain") { kotlin.srcDir(generatedBridgeDirectory) }
tasks.named("compileKotlinJvm") { dependsOn(generateNotificationsBridge) }

val typescriptCompiler = rootProject.layout.projectDirectory.file("node_modules/typescript/bin/tsc")
val verifyNotificationsBridgeTypescript = tasks.register<Exec>("verifyNotificationsBridgeTypescript") {
    group = "verification"
    description = "Compiles the generated KWebNotifications TypeScript bridge."
    dependsOn(generateNotificationsBridge)
    commandLine(
        "node", typescriptCompiler.asFile.absolutePath, "--noEmit", "--strict",
        "--target", "ES2022", "--module", "ES2022", "--lib", "ES2022,DOM",
        generatedBridgeDirectory.get().file("NotificationsBridgeBridge.ts").asFile.absolutePath,
    )
}

tasks.named<Test>("jvmTest") { useJUnitPlatform() }
tasks.named("check") { dependsOn(verifyNotificationsBridgeTypescript) }
