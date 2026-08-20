import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "KWebShell"

include(":kweb-core")
include(":kweb-bridge")
include(":kweb-bridge-codegen")
include(":kweb-extensions")
include(":kweb-desktop")
include(":kweb-interop-probe")
include(":kweb-runtime-pack")
include(":kweb-cef-native")
include(":kweb-example-html5-lab")
