// Standalone build — deliberately NOT included in the root settings.gradle.kts.
// It consumes :socketio the way a real user does: as a resolved Maven artifact.
rootProject.name = "consumer-smoke"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal() // where CI stages the artifact under test
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}
