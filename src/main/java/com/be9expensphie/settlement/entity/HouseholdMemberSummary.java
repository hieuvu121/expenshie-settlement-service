package com.be9expensphie.settlement.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Local projection of household membership, fed by household-member-events.
 *
 * Exists because settlement-service could not authorize anything without it.
 * Settlements key on memberId, the gateway identifies callers by userId, and
 * this service held no mapping between the two -- so every endpoint trusted the
 * memberId in the URL and the ownership checks in SettlementService were
 * comparing a path parameter against itself.
 *
 * Same shape and same source as expense-service's projection of the same name.
 * Deliberately not shared: each service owns its own copy of the read model.
 */
@Entity
@Table(name = "household_member_summary",
        indexes = {
                @Index(name = "idx_hms_user_household", columnList = "user_id,household_id"),
                @Index(name = "idx_hms_household", columnList = "household_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HouseholdMemberSummary {

    @Id
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "household_id", nullable = false)
    private Long householdId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * Set when MEMBER_LEFT arrives; null means an active member.
     *
     * A tombstone rather than a delete, for the same reason the debt itself
     * survives removal: settlements key on memberId and stay settleable after
     * someone leaves. Deleting the row would make an outstanding debt
     * unauthorizable and therefore uncollectable in-app.
     */
    @Column(name = "removed_at")
    private Instant removedAt;
}
