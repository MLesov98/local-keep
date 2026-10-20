pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "LocalKeep"

include(":app")
include(":core:common")
include(":core:model")
include(":core:crypto")
include(":core:vault")
include(":core:generator")
include(":core:totp")
include(":core:import")
include(":core:ui")
include(":core:health")
include(":core:location")
include(":core:billing")
include(":core:billing-api")
include(":feature:onboarding")
include(":feature:unlock")
include(":feature:vaultlist")
include(":feature:itemedit")
include(":feature:generator")
include(":feature:settings")
include(":feature:files")
include(":feature:health")
include(":feature:backup")
include(":feature:import")
include(":autofill")
include(":credential")
include(":tools:autofill-testapp")
include(":tools:passkey-testapp")
include(":test:fuzz")
include(":test:device")
