package com.autocve.agent.mcp.gateway;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import java.net.http.HttpClient;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Transport settings required by the Python ASGI MCP server. */
@Configuration(proxyBeanMethods = false)
public class McpHttpTransportConfiguration {

    @Bean
    McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> http11McpClientCustomizer() {
        return (serverName, builder) ->
                builder.customizeClient(client -> client.version(HttpClient.Version.HTTP_1_1));
    }
}
