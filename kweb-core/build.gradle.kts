import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
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
