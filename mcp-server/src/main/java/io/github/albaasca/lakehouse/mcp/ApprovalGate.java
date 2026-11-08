package io.github.albaasca.lakehouse.mcp;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The cut point for approval. {@link FixProposalController} asks it before approving anything; throwing
 * {@link SecurityException} refuses the approval (HTTP 403).
 *
 * <p>Today {@link #OPEN} lets everyone through, because the server has no credentials yet and listens on loopback. The
 * card of roles and API keys (T-050) replaces this bean with one that checks an admin credential the model never holds;
 * nothing else has to change. Until then, whoever can reach the REST port can approve.
 */
@FunctionalInterface
interface ApprovalGate {

    ApprovalGate OPEN = (request, approvedBy) -> { };

    /** @throws SecurityException if this request may not approve */
    void check(HttpServletRequest request, String approvedBy);
}
