package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.albaasca.lakehouse.mcp.FixProposalStore.Proposal;
import io.github.albaasca.lakehouse.mcp.FixProposalStore.Status;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The state machine of a proposal, with a store in memory and an action that counts how many times it ran. */
class FixProposalsTest {

    private static final class MemoryStore implements FixProposalStore {
        private final List<Proposal> rows = new ArrayList<>();

        @Override
        public synchronized long create(String action, String params, String reason, String effect, Instant now) {
            rows.add(new Proposal(rows.size() + 1, action, params, reason, effect, Status.PROPOSED, null, now, null, null,
                    null));
            return rows.size();
        }

        @Override
        public synchronized Optional<Proposal> find(long id) {
            return rows.stream().filter(p -> p.id() == id).findFirst();
        }

        @Override
        public synchronized List<Proposal> list() {
            return List.copyOf(rows);
        }

        private synchronized boolean move(long id, Status from, Status to, String by, String error, Instant now) {
            Proposal p = rows.get((int) id - 1);
            if (p.status() != from) {
                return false;
            }
            rows.set((int) id - 1, new Proposal(p.id(), p.action(), p.params(), p.reason(), p.expectedEffect(), to,
                    by == null ? p.approvedBy() : by, p.createdAt(), p.approvedAt(), now, error));
            return true;
        }

        @Override
        public boolean approve(long id, String by, Instant now) {
            return move(id, Status.PROPOSED, Status.APPROVED, by, null, null);
        }

        @Override
        public boolean claim(long id) {
            return move(id, Status.APPROVED, Status.APPLYING, null, null, null);
        }

        @Override
        public void finish(long id, Status status, String error, Instant now) {
            move(id, Status.APPLYING, status, null, error, now);
        }
    }

    private static final class CountingAction implements FixAction {
        final AtomicInteger runs = new AtomicInteger();
        boolean fail;

        @Override
        public Type type() {
            return Type.ROLLBACK_SNAPSHOT;
        }

        @Override
        public Map<String, Object> validate(Map<String, Object> params) {
            return params;
        }

        @Override
        public String expectedEffect(Map<String, Object> params) {
            return "nothing";
        }

        @Override
        public void apply(Map<String, Object> params) throws SQLException {
            runs.incrementAndGet();
            if (fail) {
                throw new SQLException("boom");
            }
        }
    }

    private final CountingAction action = new CountingAction();
    private final FixProposals proposals = new FixProposals(new MemoryStore(), List.of(action), Clock.systemUTC());

    private long proposed() throws SQLException {
        return proposals.propose(FixAction.Type.ROLLBACK_SNAPSHOT, Map.of("snapshotId", 1), "because").id();
    }

    @Test
    void applyingWithoutApprovalFailsAndRunsNothing() throws Exception {
        long id = proposed();

        assertThatThrownBy(() -> proposals.apply(id)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not been approved");

        assertThat(action.runs).hasValue(0);
        assertThat(proposals.get(id).status()).isEqualTo(Status.PROPOSED);
    }

    @Test
    void afterApprovalItRunsOnceAndRepeatingDoesNotRunItAgain() throws Exception {
        long id = proposed();
        proposals.approve(id, "alba");

        assertThat(proposals.apply(id).appliedNow()).isTrue();
        assertThat(proposals.apply(id).appliedNow()).isFalse();
        assertThat(proposals.apply(id).proposal().status()).isEqualTo(Status.APPLIED);

        assertThat(action.runs).hasValue(1);
    }

    @Test
    void concurrentCallsRunItOnce() throws Exception {
        long id = proposed();
        proposals.approve(id, "alba");

        List<CompletableFuture<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return proposals.apply(id);
                } catch (SQLException | IllegalStateException e) {
                    return e;
                }
            }));
        }
        calls.forEach(CompletableFuture::join);

        assertThat(action.runs).hasValue(1);
    }

    @Test
    void approvalOnlyFromProposedAndNeedsAName() throws Exception {
        long id = proposed();

        assertThatThrownBy(() -> proposals.approve(id, " ")).isInstanceOf(IllegalArgumentException.class);
        proposals.approve(id, "alba");
        assertThatThrownBy(() -> proposals.approve(id, "alba")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> proposals.approve(99, "alba")).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void aFailedRepairIsRecordedAndNotRetried() throws Exception {
        long id = proposed();
        proposals.approve(id, "alba");
        action.fail = true;

        assertThatThrownBy(() -> proposals.apply(id)).isInstanceOf(SQLException.class);
        action.fail = false;

        assertThat(proposals.get(id).status()).isEqualTo(Status.FAILED);
        assertThat(proposals.get(id).error()).contains("boom");
        assertThatThrownBy(() -> proposals.apply(id)).isInstanceOf(IllegalStateException.class);
        assertThat(action.runs).hasValue(1);
    }

    @Test
    void anEmptyReasonIsRefused() {
        assertThatThrownBy(() -> proposals.propose(FixAction.Type.ROLLBACK_SNAPSHOT, Map.of(), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
