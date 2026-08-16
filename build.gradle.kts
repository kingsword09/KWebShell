plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

group = "io.github.kingsword09.kwebshell"
version = "0.1.0-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.named("check") {
    dependsOn(":kweb-core:check")
    dependsOn(":kweb-bridge:check")
    dependsOn(":kweb-bridge-codegen:check")
    dependsOn(":kweb-extensions:check")
    dependsOn(":kweb-desktop:check")
    dependsOn(":kweb-cef-native:check")
    dependsOn(":kweb-runtime-pack:check")
    dependsOn(":kweb-runtime-pack:verifyCefRuntimeManifest")
    dependsOn(":kweb-runtime-pack:verifyCefSourcePatchManifest")
}
