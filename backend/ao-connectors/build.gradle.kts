description = "Auto-Ontology: source database connectors (H2, PostgreSQL, Oracle, SQL Server, DataSource bean)"
dependencies {
    api(project(":ao-common"))
    api(libs.spring.boot.starter.jdbc)
    api(libs.jsqlparser)
    implementation(libs.h2)
    implementation(libs.postgresql)
    implementation(libs.ojdbc)
    implementation(libs.mssql)
    testImplementation(platform(libs.embedded.postgres.binaries.bom))
    testImplementation(libs.embedded.postgres)
    testImplementation(libs.testcontainers.oracle)
    testImplementation(libs.testcontainers.mssql)
    testImplementation(libs.testcontainers.junit)
}
