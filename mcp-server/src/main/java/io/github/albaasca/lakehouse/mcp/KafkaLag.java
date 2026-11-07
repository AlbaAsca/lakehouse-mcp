package io.github.albaasca.lakehouse.mcp;

import io.github.albaasca.lakehouse.mcp.PipelineHealth.PartitionLag;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

/**
 * The lag of a consumer group on one topic, partition by partition. A partition the group never committed counts from
 * the start of the log: everything in it is still waiting.
 *
 * <p>A client per call: it costs little next to the call itself, and a client kept open would retry a dead broker in the
 * background forever.
 */
final class KafkaLag implements PipelineHealth.LagSource {

    private final String bootstrapServers;
    private final String group;
    private final String topic;
    private final Duration timeout;

    KafkaLag(String bootstrapServers, String group, String topic, Duration timeout) {
        this.bootstrapServers = bootstrapServers;
        this.group = group;
        this.topic = topic;
        this.timeout = timeout;
    }

    @Override
    public List<PartitionLag> read() throws Exception {
        Properties config = new Properties();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(AdminClientConfig.CLIENT_ID_CONFIG, "mcp-server-pipeline-health");
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) timeout.toMillis());
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) timeout.toMillis());
        long ms = timeout.toMillis();
        Admin admin = Admin.create(config);
        try {
            TopicDescription description;
            try {
                description = admin.describeTopics(List.of(topic)).allTopicNames().get(ms, TimeUnit.MILLISECONDS)
                        .get(topic);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                    throw new NoSuchElementException("Topic " + topic + " does not exist in Kafka.");
                }
                throw e;
            }
            List<TopicPartition> partitions = description.partitions().stream()
                    .map(info -> new TopicPartition(topic, info.partition())).toList();
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata().get(ms, TimeUnit.MILLISECONDS);
            Map<TopicPartition, ListOffsetsResultInfo> ends = admin.listOffsets(spec(partitions, OffsetSpec.latest()))
                    .all().get(ms, TimeUnit.MILLISECONDS);
            Map<TopicPartition, ListOffsetsResultInfo> starts = admin.listOffsets(spec(partitions, OffsetSpec.earliest()))
                    .all().get(ms, TimeUnit.MILLISECONDS);
            List<PartitionLag> out = new ArrayList<>();
            for (TopicPartition partition : partitions) {
                long end = ends.get(partition).offset();
                OffsetAndMetadata offset = committed.get(partition);
                long at = offset == null ? starts.get(partition).offset() : offset.offset();
                out.add(new PartitionLag(partition.partition(), at, end, Math.max(0, end - at)));
            }
            out.sort(Comparator.comparingInt(PartitionLag::partition));
            return out;
        } finally {
            admin.close(Duration.ofSeconds(1));
        }
    }

    private static Map<TopicPartition, OffsetSpec> spec(List<TopicPartition> partitions, OffsetSpec spec) {
        return partitions.stream().collect(Collectors.toMap(Function.identity(), partition -> spec));
    }
}
