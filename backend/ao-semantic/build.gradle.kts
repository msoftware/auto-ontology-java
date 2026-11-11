description = "Auto-Ontology: semantic compilation (terms, attributes, semantic FKs, SQL attributes, bridges)"
dependencies {
    api(project(":ao-ingestion"))
    testImplementation(libs.h2)
}
