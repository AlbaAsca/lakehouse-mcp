package io.github.albaasca.lakehouse.producer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaCompatibility.SchemaCompatibilityType;
import org.junit.jupiter.api.Test;

/**
 * Data contract (T-043), level 1: no containers, every build. The versions in {@code contracts/ecommerce.events-value}
 * must be compatible in both directions (a removed field without default breaks old readers, an added one without
 * default breaks new readers) and the producer's schema must be the last version.
 */
class ContractCompatibilityTest {

    private static final Path CONTRACTS = Path.of("../contracts/ecommerce.events-value").toAbsolutePath().normalize();
    private static final Path PRODUCER_SCHEMA = Path.of("src/main/avro/event.avsc");
    private static final Path FIXTURES = Path.of("src/test/resources/contracts");

    @Test
    void everyVersionIsCompatibleWithAllThePreviousOnes() throws IOException {
        List<Schema> versions = versions();
        for (int i = 1; i < versions.size(); i++) {
            for (int j = 0; j < i; j++) {
                assertThat(compatible(versions.get(i), versions.get(j))).as("v%d vs v%d", i + 1, j + 1).isTrue();
            }
        }
    }

    @Test
    void producerSchemaIsTheLastVersion() throws IOException {
        List<Schema> versions = versions();
        assertThat(new Schema.Parser().parse(Files.readString(PRODUCER_SCHEMA)))
                .as("producer event.avsc must equal contracts/ecommerce.events-value/v%d.avsc", versions.size())
                .isEqualTo(versions.get(versions.size() - 1));
    }

    @Test
    void addingANullableFieldWithDefaultPasses() throws IOException {
        assertThat(compatible(fixture("compatible-v2.avsc"), versions().get(0))).isTrue();
    }

    @Test
    void removingAFieldWithoutDefaultFails() throws IOException {
        assertThat(compatible(fixture("broken-removed-required.avsc"), versions().get(0))).isFalse();
    }

    @Test
    void addingAFieldWithoutDefaultFails() throws IOException {
        assertThat(compatible(fixture("broken-added-required.avsc"), versions().get(0))).isFalse();
    }

    static boolean compatible(Schema candidate, Schema previous) {
        return SchemaCompatibility.checkReaderWriterCompatibility(candidate, previous).getType()
                        == SchemaCompatibilityType.COMPATIBLE
                && SchemaCompatibility.checkReaderWriterCompatibility(previous, candidate).getType()
                        == SchemaCompatibilityType.COMPATIBLE;
    }

    private static Schema fixture(String name) throws IOException {
        return new Schema.Parser().parse(Files.readString(FIXTURES.resolve(name)));
    }

    /** The versions in order v1, v2, ... (numeric, so v10 comes after v9). */
    private static List<Schema> versions() throws IOException {
        Pattern name = Pattern.compile("v(\\d+)\\.avsc");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> all = Files.list(CONTRACTS)) {
            all.filter(p -> name.matcher(p.getFileName().toString()).matches()).forEach(files::add);
        }
        files.sort(Comparator.comparingInt(p -> {
            Matcher m = name.matcher(p.getFileName().toString());
            m.matches();
            return Integer.parseInt(m.group(1));
        }));
        assertThat(files).as("contract versions in " + CONTRACTS).isNotEmpty();
        List<Schema> schemas = new ArrayList<>();
        for (Path file : files) {
            schemas.add(new Schema.Parser().parse(Files.readString(file)));
        }
        return schemas;
    }
}
