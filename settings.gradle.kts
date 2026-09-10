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
include(":kweb-services-core")
include(":kweb-service-app-paths")
include(":kweb-service-window-controls")
include(":kweb-service-dialogs")
include(":kweb-electron-migration")
include(":kweb-bridge")
include(":kweb-bridge-codegen")
include(":kweb-extensions")
include(":kweb-desktop")
include(":kweb-compose")
include(":kweb-interop-probe")
include(":kweb-runtime-pack")
include(":kweb-cef-native")
include(":kweb-example-html5-lab")
include(":kweb-example-support")
include(":kweb-example-app-benchmark")
