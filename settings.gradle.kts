pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.extendedclip.com/releases/")
    }
}

rootProject.name = "MiaHub"

include("miahub")
include("miahub-self-updater")
include("miaforge")
include("mialimitation")
include("miapickaxe")
include("miasmartgiftroll")
include("miaskillpool")
include("miaeco")
