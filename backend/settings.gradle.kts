// SPDX-License-Identifier: Apache-2.0
rootProject.name = "auto-ontology-java"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// quasar-dashboard backend (section 23) from the sibling repository, as a composite build
includeBuild("../../quasar-dashboard/backend")

include(
    "ao-common",
    "ao-store",
    "ao-vector",
    "ao-llm",
    "ao-connectors",
    "ao-ingestion",
    "ao-semantic",
    "ao-agent",
    "ao-api",
    "ao-mcp",
    "ao-nl-chart",
    "ao-spring-boot-starter",
    "ao-app",
    "ao-dashboard-demo",
)
