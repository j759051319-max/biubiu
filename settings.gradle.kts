pluginManagement {
    repositories {
        System.getenv("CODEX_OFFLINE_M2")?.let { maven { url = uri(it) } }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        System.getenv("CODEX_OFFLINE_M2")?.let { maven { url = uri(it) } }
        google()
        mavenCentral()
    }
}
rootProject.name = "DailyWorkCheckin"
include(":app")
