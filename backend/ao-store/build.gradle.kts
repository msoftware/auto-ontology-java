description = "Auto-Ontology: metadata store (Liquibase schema, JdbcClient repositories)"
dependencies {
    api(project(":ao-common"))
    api(libs.spring.boot.starter.jdbc)
    api(libs.spring.boot.starter.liquibase)
    testImplementation(platform(libs.embedded.postgres.binaries.bom))
    testImplementation(libs.embedded.postgres)
    testImplementation(libs.h2)
    testRuntimeOnly(libs.postgresql)
}
