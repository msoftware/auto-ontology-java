// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.mcp;

import de.jentsch.autoontology.common.AoProperties;
import de.jentsch.autoontology.common.CommonAutoConfiguration;
import de.jentsch.autoontology.common.Json;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP server at the fixed path {@code /mcp} (Streamable HTTP, FR-MCP-01). No client
 * authentication (FR-MCP-02); tools run as the fixed user through the public API. Disable with
 * {@code ao.mcp.enabled=false}.
 */
@AutoConfiguration(after = CommonAutoConfiguration.class)
@ConditionalOnProperty(name = "ao.mcp.enabled", havingValue = "true", matchIfMissing = true)
public class McpAutoConfiguration {

    @Bean
    public HttpServletStreamableServerTransportProvider aoMcpTransport() {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build()))
                .mcpEndpoint("/mcp")
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> aoMcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> reg =
                new ServletRegistrationBean<>(transport, "/mcp", "/mcp/*");
        reg.setAsyncSupported(true);
        reg.setName("aoMcp");
        return reg;
    }

    @Bean
    public McpTools aoMcpTools(AoProperties props, org.springframework.core.env.Environment env) {
        String configured = props.mcp().apiBaseUrl();
        if (configured != null && !configured.isBlank()) {
            return new McpTools(configured);
        }
        // same process: the configured prefix, never a hard-coded /api (NFR-ARC-05)
        return new McpTools(() -> "http://localhost:" + env.getProperty("local.server.port",
                env.getProperty("server.port", "8080")) + props.apiPrefix());
    }

    @Bean(destroyMethod = "close")
    public McpSyncServer aoMcpServer(HttpServletStreamableServerTransportProvider transport, McpTools tools) {
        List<McpServerFeatures.SyncToolSpecification> specs = McpTools.TOOLS.stream().map(t ->
                McpServerFeatures.SyncToolSpecification.builder()
                        .tool(McpSchema.Tool.builder(t.name(), t.inputSchema()).description(t.description()).build())
                        .callHandler((exchange, request) -> {
                            try {
                                Object result = tools.call(t.name(), request.arguments());
                                return McpSchema.CallToolResult.builder().addTextContent(Json.write(result))
                                        .isError(false).build();
                            } catch (RuntimeException e) {
                                return McpSchema.CallToolResult.builder().addTextContent(String.valueOf(e.getMessage()))
                                        .isError(true).build();
                            }
                        })
                        .build()).toList();
        return McpServer.sync(transport)
                .serverInfo("auto-ontology", "0.1.0")
                .instructions(McpTools.INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(specs)
                .build();
    }
}
