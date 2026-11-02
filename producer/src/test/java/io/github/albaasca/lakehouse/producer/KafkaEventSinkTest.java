package io.github.albaasca.lakehouse.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class KafkaEventSinkTest {

    private final MockProducer<String, GenericRecord> producer =
            new MockProducer<>(false, null, new StringSerializer(), (topic, event) -> new byte[0]);
    private final KafkaEventSink sink = new KafkaEventSink(producer);

    @Test
    void theSessionIsTheKeyAndAnEventWithoutSessionHasNone() throws IOException {
        GenericRecord withSession = event("session-1");
        GenericRecord withoutSession = event(null);

        sink.accept(withSession);
        sink.accept(withoutSession);

        ProducerRecord<String, GenericRecord> first = producer.history().get(0);
        assertThat(first.topic()).isEqualTo("ecommerce.events");
        assertThat(first.key()).isEqualTo("session-1");
        assertThat(first.value()).isSameAs(withSession);
        assertThat(producer.history().get(1).key()).isNull();
    }

    @Test
    void eventsGoToTheTopicItWasOpenedWith() throws IOException {
        new KafkaEventSink(producer, "other.topic").accept(event("a"));

        assertThat(producer.history().get(0).topic()).isEqualTo("other.topic");
    }

    @Test
    void onlyConfirmedEventsCount() throws IOException {
        sink.accept(event("a"));
        sink.accept(event("b"));
        assertThat(sink.confirmed()).isZero();

        producer.completeNext();

        assertThat(sink.confirmed()).isEqualTo(1);
    }

    @Test
    void aRejectedEventStopsTheNextSend() throws IOException {
        sink.accept(event("a"));
        producer.errorNext(new RuntimeException("broker said no"));

        assertThatThrownBy(() -> sink.accept(event("b")))
                .isInstanceOf(IOException.class)
                .hasRootCauseMessage("broker said no");
        assertThat(producer.history()).hasSize(1);
    }

    @Test
    void aRejectionOfTheLastEventSurfacesOnClose() throws IOException {
        sink.accept(event("a"));
        producer.errorNext(new RuntimeException("broker said no"));

        assertThatThrownBy(sink::close).isInstanceOf(IOException.class).hasRootCauseMessage("broker said no");
    }

    private static GenericRecord event(String session) {
        GenericRecord event = new GenericData.Record(EventSchema.SCHEMA);
        event.put("user_session", session);
        return event;
    }
}
