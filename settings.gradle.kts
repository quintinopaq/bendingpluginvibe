pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
    }
    includeBuild("build-logic")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
rootProject.name = "bending"

setupSubproject("bending-api", "api")
setupSubproject("bending-common", "common")
setupSubproject("bending-nms", "nms")
setupSubproject("bending-fabric", "fabric")
setupSubproject("bending-paper", "paper")
//setupSubproject("bending-sponge", "sponge")
include("jmh")
//include("code-generator")

fun setupSubproject(name: String, dir: String) {
    include(name)
    project(":$name").projectDir = file(dir)
}
