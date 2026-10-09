pluginManagement {
    repositories {
        val mirror = providers.gradleProperty("localtvMavenProxy").orNull
        if (mirror != null) {
            maven("$mirror/google") { isAllowInsecureProtocol = true; content { includeGroupByRegex("androidx.*"); includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google\\.android.*") } }
            maven("$mirror/central") { isAllowInsecureProtocol = true }
            maven("$mirror/plugins") { isAllowInsecureProtocol = true }
        } else { google(); mavenCentral(); gradlePluginPortal() }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val mirror = providers.gradleProperty("localtvMavenProxy").orNull
        if (mirror != null) {
            maven("$mirror/google") { isAllowInsecureProtocol = true; content { includeGroupByRegex("androidx.*"); includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google\\.android.*") } }
            maven("$mirror/central") { isAllowInsecureProtocol = true }
        } else { google(); mavenCentral() }
    }
}
rootProject.name = "LocalTV"
include(":app")
