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

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
