description = "Auto-Ontology: LLM gateway (LangChain4j), embedding and rerank clients"
// LangChain4j defaults to Jackson 2; langchain4j-jackson3 switches it to Jackson 3 (NFR-QD-01).
fun ExternalModuleDependency.noJackson2() {
    exclude(group = "com.fasterxml.jackson.core", module = "jackson-databind")
    exclude(group = "com.fasterxml.jackson.core", module = "jackson-core")
}
dependencies {
    api(project(":ao-common"))
    api(libs.langchain4j) { noJackson2() }
    implementation(libs.langchain4j.open.ai) { noJackson2() }
    implementation(libs.langchain4j.jackson3) { noJackson2() }
    annotationProcessor(libs.spring.boot.configuration.processor)
}
