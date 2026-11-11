plugins {
    alias(libs.plugins.spring.boot)
}
description = "Combined demo host: quasar-dashboard backend + sample data + Auto-Ontology + NL charts (FR-NLC-23)"
dependencies {
    implementation(project(":ao-spring-boot-starter"))
    implementation(project(":ao-nl-chart"))
    implementation("de.jentsch.quasardashboard:quasar-dashboard-backend-sample-data")
    runtimeOnly(libs.h2)
    testImplementation(libs.spring.boot.starter.webmvc.test)
}
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir.parentFile
}
