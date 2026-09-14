buildscript {
    repositories {
        System.getenv("CODEX_OFFLINE_M2")?.let { maven { url = uri(it) } }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.6.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.25")
    }
}
