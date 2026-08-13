pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CallDelegate"

include(":app")
include(":core:common")
include(":core:audio")
include(":core:ai")
include(":domain")
include(":data:local")
include(":feature:main")
include(":benchmark")
