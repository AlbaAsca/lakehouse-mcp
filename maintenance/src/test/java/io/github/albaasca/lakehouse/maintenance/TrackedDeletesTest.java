package io.github.albaasca.lakehouse.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.albaasca.lakehouse.maintenance.SnapshotExpirer.TrackedDeletes;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TrackedDeletesTest {

    @Test
    void aFailureIsRecordedAndReachesIceberg() {
        TrackedDeletes deletes = new TrackedDeletes(path -> {
            throw new IllegalStateException("store down");
        });

        assertThatThrownBy(() -> deletes.accept("a")).hasMessage("store down");

        assertThat(deletes.failed()).containsExactly("a");
        assertThat(deletes.deleted()).isEmpty();
    }

    @Test
    void aRetryThatSucceedsIsNotAFailure() {
        AtomicInteger calls = new AtomicInteger();
        TrackedDeletes deletes = new TrackedDeletes(path -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("blip");
            }
        });

        assertThatThrownBy(() -> deletes.accept("a")).hasMessage("blip");
        deletes.accept("a");

        assertThat(deletes.failed()).isEmpty();
        assertThat(deletes.deleted()).containsExactly("a");
    }
}
