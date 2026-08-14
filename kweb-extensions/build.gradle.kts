import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
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

        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

val mv3CoreFixtureDirectory =
    rootProject.layout.projectDirectory.dir("kweb-cef-native/tests/fixtures/mv3-core")

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    inputs.dir(mv3CoreFixtureDirectory)
    systemProperty("kweb.mv3.core.fixture", mv3CoreFixtureDirectory.asFile.absolutePath)
}
