pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    buildscript {
        repositories { google(); mavenCentral() }
        dependencies { classpath("com.android.tools:r8:9.1.31") }
    }
}
require(providers.gradleProperty("DROIDE_FDROID_BUILD").orNull == "true") {
    "This source distribution requires -PDROIDE_FDROID_BUILD=true"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Droide"
include(":app")
