import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
    jvmToolchain(25)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kweb-core"))
            api(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }

        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

val mv3CoreFixtureDirectory =
    rootProject.layout.projectDirectory.dir("kweb-cef-native/tests/fixtures/mv3-core")
val mv3LifecycleFixtureDirectory =
    rootProject.layout.projectDirectory.dir("kweb-cef-native/tests/fixtures/mv3-lifecycle")

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    inputs.dir(mv3CoreFixtureDirectory)
    inputs.dir(mv3LifecycleFixtureDirectory)
    systemProperty("kweb.mv3.core.fixture", mv3CoreFixtureDirectory.asFile.absolutePath)
    systemProperty("kweb.mv3.lifecycle.fixture", mv3LifecycleFixtureDirectory.asFile.absolutePath)
}
