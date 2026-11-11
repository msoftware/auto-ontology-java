description = "Auto-Ontology: natural-language charts for quasar-dashboard (section 23)"
dependencies {
    api(project(":ao-api"))
    api("de.jentsch.quasardashboard:quasar-dashboard-backend")
    testImplementation(libs.h2)
}
