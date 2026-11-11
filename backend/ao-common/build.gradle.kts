description = "Auto-Ontology: shared configuration, DTO helpers, errors and the fixed user"
dependencies {
    api(libs.spring.boot.starter.webmvc)
    api(libs.spring.boot.starter.validation)
    api(libs.jackson.databind)
    annotationProcessor(libs.spring.boot.configuration.processor)
}
