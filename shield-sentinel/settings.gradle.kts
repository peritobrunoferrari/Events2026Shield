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

rootProject.name = "shield-sentinel"

include(":app")
include(":core-logic")
include(":core-security")
include(":secure-storage")
include(":audit")
include(":device-owner")
include(":usb-security")
include(":device-hardening")
include(":auto-reboot")
include(":watchdog")
include(":emergency")
include(":anti-theft")
include(":remote-wipe")
include(":duress")
