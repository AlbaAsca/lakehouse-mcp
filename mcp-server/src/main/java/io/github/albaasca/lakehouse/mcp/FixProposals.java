package io.github.albaasca.lakehouse.mcp;

import io.github.albaasca.lakehouse.mcp.FixProposalStore.Proposal;
import io.github.albaasca.lakehouse.mcp.FixProposalStore.Status;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The life of a proposal: proposed by the model, approved by a person, applied once.
 *
 * <p>The only way to APPROVED is {@link #approve}, which the REST resource calls and the MCP tools do not. That is the
 * guarantee: the model can propose and ask to apply, but whether it is approved is decided on a path it cannot reach.
 */
final class FixProposals {

    private static final Logger LOG = LoggerFactory.getLogger(FixProposals.class);
    private static final TypeReference<Map<String, Object>> PARAMS = new TypeReference<>() {};

    /** What apply answers: the proposal as it is now, and whether this call is the one that did the work. */
    record Applied(Proposal proposal, boolean appliedNow) {}

    private final FixProposalStore store;
    private final Map<FixAction.Type, FixAction> actions;
    private final Clock clock;

    FixProposals(FixProposalStore store, List<FixAction> actions, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.actions = actions.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(FixAction::type, a -> a));
        this.clock = clock;
    }

    Proposal propose(FixAction.Type type, Map<String, Object> params, String reason) throws SQLException {
        if (type == null || !actions.containsKey(type)) {
            throw new IllegalArgumentException("Unknown action " + type + "; the options are " + actions.keySet());
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is needed: the person who approves reads it");
        }
        FixAction action = actions.get(type);
        Map<String, Object> canonical = action.validate(params);
        long id = store.create(type.name(), JsonMapper.shared().writeValueAsString(canonical), reason.strip(),
                action.expectedEffect(canonical), clock.instant());
        return get(id);
    }

    Proposal get(long id) throws SQLException {
        return store.find(id).orElseThrow(() -> new NoSuchElementException("No proposal " + id));
    }

    List<Proposal> list() throws SQLException {
        return store.list();
    }

    /** Only from PROPOSED. Approving twice, or something already applied, is refused. */
    Proposal approve(long id, String approvedBy) throws SQLException {
        if (approvedBy == null || approvedBy.isBlank()) {
            throw new IllegalArgumentException("approvedBy is needed");
        }
        Proposal before = get(id);
        if (!store.approve(id, approvedBy.strip(), clock.instant())) {
            throw new IllegalStateException("Proposal " + id + " is " + before.status() + ", not PROPOSED");
        }
        return get(id);
    }

    /**
     * Applies an approved proposal. Not approved: refused, nothing changes. Already applied: answers the same proposal
     * without touching anything. The claim is a conditional update, so concurrent calls apply it once.
     */
    Applied apply(long id) throws SQLException {
        Proposal proposal = get(id);
        switch (proposal.status()) {
            case APPLIED -> {
                return new Applied(proposal, false);
            }
            case PROPOSED -> throw new IllegalStateException(
                    "Proposal " + id + " has not been approved by a person; it cannot be applied");
            case APPLYING -> throw new IllegalStateException("Proposal " + id + " is being applied right now");
            case FAILED -> throw new IllegalStateException("Proposal " + id + " failed before (" + proposal.error()
                    + "); propose it again");
            case APPROVED -> { }
        }
        if (!store.claim(id)) {
            // Lost the race: whoever won is applying or has applied it.
            Proposal now = get(id);
            if (now.status() == Status.APPLIED) {
                return new Applied(now, false);
            }
            throw new IllegalStateException("Proposal " + id + " is " + now.status());
        }
        try {
            actions.get(FixAction.Type.valueOf(proposal.action()))
                    .apply(JsonMapper.shared().readValue(proposal.params(), PARAMS));
        } catch (SQLException | RuntimeException e) {
            LOG.error("Fix {} failed", id, e);
            store.finish(id, Status.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage(), clock.instant());
            throw e;
        }
        store.finish(id, Status.APPLIED, null, clock.instant());
        return new Applied(get(id), true);
    }
}
