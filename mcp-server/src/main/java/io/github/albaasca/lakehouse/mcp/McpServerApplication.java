package io.github.albaasca.lakehouse.mcp;

import io.github.albaasca.lakehouse.mcp.LakehouseSession.Catalog;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Settings;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Storage;
import java.nio.file.Path;
import java.sql.SQLException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** The MCP server. It does not start without the catalog: every tool would fail anyway. */
@SpringBootApplication
@EnableConfigurationProperties(McpServerApplication.LakehouseProperties.class)
public class McpServerApplication {

    @ConfigurationProperties("lakehouse")
    record LakehouseProperties(Catalog catalog, Storage storage, String extensionDirectory) {

        Settings settings() {
            Settings settings = Settings.of(catalog, storage);
            return extensionDirectory == null || extensionDirectory.isBlank()
                    ? settings
                    : settings.withExtensions(Path.of(extensionDirectory), false);
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    @Bean
    LakehouseSession lakehouseSession(LakehouseProperties properties) throws SQLException {
        return new LakehouseSession(properties.settings());
    }

    @Bean
    CatalogReader catalogReader(LakehouseSession session) {
        return new CatalogReader(session);
    }

    /**
     * No authentication until the API keys of T-050, which is why the server only listens on loopback by default.
     * Without this chain the security starter answers 401 to everything, and CSRF rejects every POST to the endpoint.
     */
    @Bean
    SecurityFilterChain openUntilApiKeys(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }
}
