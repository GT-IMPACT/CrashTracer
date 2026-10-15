pluginManagement {
    includeBuild("remote-build-logic")
    repositories {
        maven("https://nexus.gtnewhorizons.com/repository/public/") {
            mavenContent {
                includeGroupByRegex("com\\.gtnewhorizons(\\..+)?")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("settings-setup")
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("remote-build-logic/libs.versions.toml"))
            version("forgelin", "2.1.3")
        }
    }
}
