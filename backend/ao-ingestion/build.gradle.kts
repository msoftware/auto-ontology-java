description = "Auto-Ontology: catalog extraction, diff, query history, ingest scheduler, PII, rules"
dependencies {
    api(project(":ao-store"))
    api(project(":ao-vector"))
    api(project(":ao-llm"))
    api(project(":ao-connectors"))
    testImplementation(libs.h2)
}
