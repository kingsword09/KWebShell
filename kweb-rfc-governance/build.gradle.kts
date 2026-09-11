import org.gradle.api.tasks.JavaExec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

dependencies {
    api(project(":kweb-services-core"))
    implementation(project(":kweb-core"))
    implementation(project(":kweb-electron-migration"))
    implementation(project(":kweb-service-app-paths"))
    implementation(project(":kweb-service-window-controls"))
    implementation(project(":kweb-service-dialogs"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("kweb.rfc.repository.root", rootProject.layout.projectDirectory.asFile.absolutePath)
}

val rfcCatalogDirectory = rootProject.layout.projectDirectory.dir("docs/rfcs")
val rfcEvidenceManifest = rootProject.layout.projectDirectory.file("docs/rfcs/evidence/manifest.json")
val rfcRuntimeIdentity = rootProject.layout.projectDirectory.file("runtime/cef-runtime.json")
val rfcGovernanceReport = layout.buildDirectory.file("reports/rfc-governance/status.json")

val rfcGovernanceCheck = tasks.register<JavaExec>("rfcGovernanceCheck") {
    group = "verification"
    description = "Validates RFC metadata, capability evidence, service descriptors, and matrix backing."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.kingsword09.kwebshell.rfc.KWebRfcGovernanceCli")
    args(
        "check",
        rfcCatalogDirectory.asFile.absolutePath,
        rfcEvidenceManifest.asFile.absolutePath,
        rfcRuntimeIdentity.asFile.absolutePath,
        "--report",
        rfcGovernanceReport.get().asFile.absolutePath,
    )
    inputs.dir(rfcCatalogDirectory)
    inputs.file(rfcEvidenceManifest)
    inputs.file(rfcRuntimeIdentity)
    outputs.file(rfcGovernanceReport)
}

tasks.named("check") {
    dependsOn(rfcGovernanceCheck)
}

val rfcEvidenceRecord = tasks.register<JavaExec>("rfcEvidenceRecord") {
    group = "governance"
    description = "Upserts one RFC capability evidence record; pass arguments with --args (see docs/rfcs/EVIDENCE.md)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.kingsword09.kwebshell.rfc.KWebRfcGovernanceCli")
}
