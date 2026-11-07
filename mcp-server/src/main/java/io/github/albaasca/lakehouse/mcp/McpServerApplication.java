package io.github.albaasca.lakehouse.mcp;

import io.github.albaasca.lakehouse.mcp.LakehouseSession.Catalog;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Settings;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Storage;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** The MCP server. It does not start without the catalog: every tool would fail anyway. */
@SpringBootApplication
@EnableConfigurationProperties({McpServerApplication.LakehouseProperties.class,
        McpServerApplication.PipelineProperties.class, McpServerApplication.QualityProperties.class})
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

    /**
     * Where pipeline_health looks and when it complains. Nothing connects at start: the server answers about the
     * catalog even with Kafka or Connect down, and says so when asked.
     */
    @ConfigurationProperties("lakehouse.pipeline")
    record PipelineProperties(String bootstrapServers, String connectUrl, String connector, String topic,
            String namespace, String table, long lagWarn, long lagFail, Duration freshnessWarn, Duration freshnessFail,
            Duration timeout) {}

    /**
     * What data_quality_check calls a problem. An empty schemaRegistryUrl turns the schema drift check off.
     *
     * @param subject the subject whose schema feeds the pipeline table of {@link PipelineProperties}
     */
    @ConfigurationProperties("lakehouse.quality")
    record QualityProperties(double minVolumeRatio, int historyDays, int minHistoryDays, List<String> knownNulls,
            String schemaRegistryUrl, String subject) {}

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

    @Bean
    PipelineHealth pipelineHealth(PipelineProperties pipeline, CatalogReader reader) {
        PipelineHealth.Thresholds thresholds = new PipelineHealth.Thresholds(pipeline.lagWarn(), pipeline.lagFail(),
                pipeline.freshnessWarn(), pipeline.freshnessFail());
        return new PipelineHealth(pipeline.connector(), pipeline.topic(), pipeline.namespace() + "." + pipeline.table(),
                thresholds,
                new KafkaLag(pipeline.bootstrapServers(), PipelineHealth.groupOf(pipeline.connector()), pipeline.topic(),
                        pipeline.timeout()),
                new ConnectStatus(pipeline.connectUrl(), pipeline.connector(), pipeline.timeout()),
                () -> reader.snapshots(pipeline.namespace(), pipeline.table()).stream()
                        .map(CatalogReader.Snapshot::committedAt).max(Comparator.naturalOrder()),
                Clock.systemUTC());
    }

    @Bean
    QualityTools qualityTools(LakehouseSession session, CatalogReader reader, QualityProperties quality,
            PipelineProperties pipeline) {
        DataQuality.Settings settings = new DataQuality.Settings(quality.minVolumeRatio(), quality.historyDays(),
                quality.minHistoryDays(), Set.copyOf(quality.knownNulls() == null ? List.of() : quality.knownNulls()));
        String url = quality.schemaRegistryUrl();
        SchemaRegistryFields.Source registry = url == null || url.isBlank() ? null
                : SchemaRegistryFields.of(url, quality.subject(), pipeline.timeout());
        return new QualityTools(session, reader, settings,
                new QualityTools.Drift(pipeline.namespace(), pipeline.table(), quality.subject(), registry));
    }

    @Bean
    MetricTools metricTools(LakehouseSession session, PipelineProperties pipeline) {
        return new MetricTools(session, io.github.albaasca.lakehouse.semantic.MetricCatalog.load(),
                LakehouseSession.CATALOG_ALIAS + "." + pipeline.namespace() + "." + pipeline.table());
    }

    /** Postgres database {@code ops}, where proposals live. An empty url turns propose_fix and apply_fix off. */
    @ConfigurationProperties("lakehouse.ops")
    record OpsProperties(String url, String user, String password) {}

    /** propose_fix, apply_fix and the approval resource: only with an ops database, or there is nowhere to keep proposals. */
    @Configuration
    @ConditionalOnExpression("!'${lakehouse.ops.url:}'.isEmpty()")
    @EnableConfigurationProperties(OpsProperties.class)
    static class OpsConfiguration {

        @Bean
        FixProposalStore fixProposalStore(OpsProperties ops) throws SQLException {
            return new PostgresFixProposalStore(ops.url(), ops.user(), ops.password()).initialize();
        }

        @Bean
        FixProposals fixProposals(FixProposalStore store, CatalogReader reader, LakehouseProperties lakehouse) {
            RepairSession repair = RepairSession.alongside(lakehouse.settings());
            return new FixProposals(store, List.of(new RollbackSnapshot(reader, repair)), Clock.systemUTC());
        }

        @Bean
        FixTools fixTools(FixProposals proposals) {
            return new FixTools(proposals);
        }

        @Bean
        @ConditionalOnMissingBean
        ApprovalGate approvalGate() {
            return ApprovalGate.OPEN;
        }

        @Bean
        FixProposalController fixProposalController(FixProposals proposals, ApprovalGate gate) {
            return new FixProposalController(proposals, gate);
        }
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
