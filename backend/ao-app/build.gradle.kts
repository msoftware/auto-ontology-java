plugins {
    alias(libs.plugins.spring.boot)
}
description = "Auto-Ontology standalone application (profiles default, demo, worker, mcp)"
dependencies {
    implementation(project(":ao-spring-boot-starter"))
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.h2)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(platform(libs.embedded.postgres.binaries.bom))
    testImplementation(libs.embedded.postgres)
}
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir.parentFile
}
