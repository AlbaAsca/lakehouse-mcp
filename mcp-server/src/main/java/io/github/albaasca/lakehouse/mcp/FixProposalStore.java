package io.github.albaasca.lakehouse.mcp;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where proposals live (table fix_proposals of the ops database, decision 10). Every change of state is one conditional
 * update, so two callers racing for the same proposal cannot both win.
 */
interface FixProposalStore {

    enum Status {
        /** Waiting for a person. */
        PROPOSED,
        /** A person approved it; not applied yet. */
        APPROVED,
        /** Someone is applying it right now. */
        APPLYING,
        APPLIED,
        /** Applying threw; it is not tried again: a new proposal is needed. */
        FAILED
    }

    /**
     * @param params canonical parameters as JSON text
     * @param approvedBy null until approved
     * @param error null unless {@link Status#FAILED}
     */
    record Proposal(long id, String action, String params, String reason, String expectedEffect, Status status,
            String approvedBy, Instant createdAt, Instant approvedAt, Instant appliedAt, String error) {}

    long create(String action, String params, String reason, String expectedEffect, Instant now) throws SQLException;

    Optional<Proposal> find(long id) throws SQLException;

    /** Newest first. */
    List<Proposal> list() throws SQLException;

    /** PROPOSED to APPROVED; false if it was not PROPOSED (or does not exist). */
    boolean approve(long id, String approvedBy, Instant now) throws SQLException;

    /** APPROVED to APPLYING; false if it was not APPROVED, which includes "already applied". */
    boolean claim(long id) throws SQLException;

    /** APPLYING to APPLIED or FAILED. */
    void finish(long id, Status status, String error, Instant now) throws SQLException;
}
