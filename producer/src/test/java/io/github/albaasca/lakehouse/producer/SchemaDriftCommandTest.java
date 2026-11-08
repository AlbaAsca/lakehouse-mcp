package io.github.albaasca.lakehouse.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.EncoderFactory;
import org.junit.jupiter.api.Test;

/** The v2 events of the schema-drift scenario: a compatible schema, and events that really carry the new field. */
class SchemaDriftCommandTest {

    @Test
    void v2IsBackwardCompatibleWithV1() {
        Schema v2 = SchemaDriftCommand.schemaV2("coupon_code");

        // A reader on v2 can read data written with v1 (the new field takes its default).
        assertThat(SchemaCompatibility.checkReaderWriterCompatibility(v2, EventSchema.SCHEMA).getType())
                .isEqualTo(SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE);
        assertThat(v2.getFields()).hasSize(EventSchema.SCHEMA.getFields().size() + 1);
        assertThat(v2.getField("coupon_code").hasDefaultValue()).isTrue();
    }

    @Test
    void sendsTheRequestedNumberOfEventsWithTheFieldFilledAndSerializable() throws Exception {
        List<GenericRecord> sent = new ArrayList<>();
        Clock clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC);

        SchemaDriftCommand.send(sent::add, "coupon_code", 7, clock);

        assertThat(sent).hasSize(7).allSatisfy(event -> assertThat(event.get("coupon_code")).isEqualTo("DRIFT10"));
        for (GenericRecord event : sent) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            var encoder = EncoderFactory.get().binaryEncoder(out, null);
            new GenericDatumWriter<GenericRecord>(event.getSchema()).write(event, encoder);
            encoder.flush();
            assertThat(out.size()).isPositive();
        }
    }

    @Test
    void refusesAFieldThatAlreadyExistsOrIsNotAName() {
        assertThatThrownBy(() -> SchemaDriftCommand.schemaV2("brand")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SchemaDriftCommand.schemaV2("bad name")).isInstanceOf(IllegalArgumentException.class);
    }
}
