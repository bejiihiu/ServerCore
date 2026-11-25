rootProject.name = "ServerCore"

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    // lets gradle auto-download a matching jdk (25) if none is installed locally.
    // the version stays inline on purpose: the plugins {} block of a settings
    // script runs before the version catalog is loaded, so libs.* cannot be
    // referenced here
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include("plugin")
