description = "Auto-Ontology: REST controllers, SSE chat, OpenAPI"
dependencies {
    api(project(":ao-agent"))
    api(libs.jackson.yaml)
    api(libs.springdoc.webmvc.api)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.h2)
}
