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
    dependsOn(":kweb-services-core:check")
    dependsOn(":kweb-service-app-paths:check")
    dependsOn(":kweb-service-window-controls:check")
    dependsOn(":kweb-service-dialogs:check")
    dependsOn(":kweb-electron-migration:check")
    dependsOn(":kweb-rfc-governance:check")
    dependsOn(":kweb-bridge:check")
    dependsOn(":kweb-bridge-codegen:check")
    dependsOn(":kweb-extensions:check")
    dependsOn(":kweb-desktop:check")
    dependsOn(":kweb-compose:check")
    dependsOn(":kweb-interop-probe:check")
    dependsOn(":kweb-cef-native:check")
    dependsOn(":kweb-runtime-pack:check")
    dependsOn(":kweb-runtime-pack:verifyCefRuntimeManifest")
    dependsOn(":kweb-runtime-pack:verifyCefSourcePatchManifest")
    dependsOn(":kweb-runtime-pack:verifyHostRuntimePayload")
    dependsOn(":kweb-example-html5-lab:check")
    dependsOn(":kweb-example-support:check")
    dependsOn(":kweb-example-app-benchmark:check")
}
