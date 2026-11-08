package io.github.albaasca.lakehouse.agent;

import java.util.List;

/**
 * What only the program does in the repair cycle: look at the proposals, approve one and apply it. The model has no way
 * here: it proposes through its own tools (analyst key), and the admin key lives only in the implementation.
 */
interface FixDesk {

    /** @param params the action parameters as JSON text, for the person to read */
    record Proposal(long id, String status, String action, String params, String reason, String expectedEffect) {}

    /** Proposals still waiting for a person (status PROPOSED). */
    List<Proposal> pending();

    /** @throws RuntimeException if the server refuses */
    void approve(long id, String approvedBy);

    /** Applies an approved proposal. @throws RuntimeException if the server refuses or the repair fails */
    void apply(long id);
}
