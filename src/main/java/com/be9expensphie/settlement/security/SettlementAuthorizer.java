package com.be9expensphie.settlement.security;

import com.be9expensphie.settlement.exception.ForbiddenException;
import com.be9expensphie.settlement.repository.HouseholdMemberSummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Checks that the caller owns the memberId they are acting as.
 *
 * Nothing did this before. Every endpoint took memberId from the URL, and
 * SettlementService's guards compared that path parameter against itself --
 * "only the paying member can toggle this" was satisfied simply by passing the
 * paying member's id. Any authenticated user could therefore mark another
 * member's debt as paid, approve their own debts away, or read anyone's
 * balances.
 *
 * The caller's identity comes from X-User-Id, which api-gateway derives from a
 * verified JWT and injects downstream. Controllers must never accept it from a
 * request body or path.
 *
 * ROLLOUT: the projection this reads is built by consuming
 * household-member-events from the earliest retained offset. Members whose
 * MEMBER_JOINED has aged out of Kafka retention will be absent and refused
 * until household-service backfills them. Failing closed is the right
 * direction, but it is a real deployment step, not a detail.
 */
@Component
@RequiredArgsConstructor
public class SettlementAuthorizer {

    private final HouseholdMemberSummaryRepository membershipRepo;

    public void requireOwnMember(Long userId, Long memberId) {
        if (userId == null || memberId == null) {
            throw new ForbiddenException("Caller identity is missing");
        }
        /*
         * Not filtered on removedAt. A member who has left keeps their
         * outstanding settlements and has to stay able to pay them, so the
         * question is "is this memberId yours?", never "are you still a
         * member?".
         */
        membershipRepo.findByMemberIdAndUserId(memberId, userId)
                .orElseThrow(() -> new ForbiddenException(
                        "Caller is not the member they are acting as"));
    }

    /**
     * For reads that span a whole household rather than one member.
     *
     * Requires current membership, unlike requireOwnMember: a member who has
     * left keeps access to their own debts but not to everyone else's.
     */
    public void requireHouseholdMember(Long userId, Long householdId) {
        if (userId == null || householdId == null) {
            throw new ForbiddenException("Caller identity is missing");
        }
        if (!membershipRepo.existsByUserIdAndHouseholdIdAndRemovedAtIsNull(userId, householdId)) {
            throw new ForbiddenException("Caller is not a member of this household");
        }
    }
}
