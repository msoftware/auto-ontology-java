description = "Auto-Ontology: vector store abstraction (Qdrant, in-memory)"
dependencies {
    api(project(":ao-store"))
    implementation(libs.qdrant.client)
    testImplementation(libs.h2)
    testImplementation(libs.testcontainers.qdrant)
    testImplementation(libs.testcontainers.junit)
}
