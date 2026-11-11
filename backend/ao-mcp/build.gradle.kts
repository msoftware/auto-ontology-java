description = "Auto-Ontology: read-only MCP server (Streamable HTTP) over the public API (section 14)"
dependencies {
    api(project(":ao-common"))
    api(libs.spring.boot.starter.webmvc)
    implementation("io.modelcontextprotocol.sdk:mcp:2.1.0")
}
