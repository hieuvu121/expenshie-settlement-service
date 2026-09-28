package com.be9expensphie.settlement.entity;

import com.be9expensphie.settlement.enums.SettlementStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "settlements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SettlementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Guards the read-then-write race between a reversal and a payment.
     *
     * ReversalDecisionService reads an expense's settlements, checks that none
     * is COMPLETED, then voids them; @Transactional does not make that atomic.
     * Under InnoDB REPEATABLE READ the SELECT is a consistent non-locking read
     * while the UPDATE is a current read, so an approval committing in the gap
     * is simply overwritten -- a debt somebody actually paid becomes VOIDED and
     * the money disappears. The race is symmetric: approveSettlement reads with
     * findById, checks the status from that snapshot and writes, so it can
     * equally stamp COMPLETED over a freshly voided row with rejectIfVoided
     * reading stale data and waving it through.
     *
     * Optimistic rather than pessimistic on purpose. A SELECT ... FOR UPDATE
     * would work only if every mutator remembered to use it, and one added
     * later that forgot would silently reopen the hole; a version column is
     * enforced by the persistence layer on every update there will ever be.
     * Contention is effectively nil -- two people touching one settlement in
     * the same millisecond -- so nothing is paid for it in the normal case, and
     * no row locks are held across the multi-row read that also writes an
     * outbox row, which is where a deadlock would otherwise come from.
     *
     * The loser needs no handling. A conflict rolls back the decision and its
     * outbox reply together, which looks exactly like a lost reply, so
     * ReversalReRequestSweep re-requests with the same sagaId and the second
     * attempt reads the now-COMPLETED settlement and correctly REFUSES.
     *
     * SettlementConcurrencyTest pins both directions.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false)
    private Long expenseId;

    @Column(nullable = false)
    private Long householdId;

    @Column(nullable = false)
    private Long fromMemberId; // owes money

    @Column(nullable = false)
    private Long toMemberId;   // is owed money

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /*
     * varchar, not a native MySQL ENUM.
     *
     * Hibernate generates enum('...') for @Enumerated(EnumType.STRING) by
     * default, and ddl-auto=update never alters an existing column -- so adding
     * a value to the Java enum leaves the database rejecting it with "Data
     * truncated for column". This column was still enum('APPROVED','PENDING',
     * 'REJECTED') when REVERSING and REVERSED were added, and settlements.status
     * had been stuck on enum('PAID','PENDING') since long before that, silently
     * making COMPLETED and AWAITING_APPROVAL unwritable.
     *
     * An explicit varchar means the next enum value needs no DDL at all.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(32)")
    private SettlementStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime paidAt;

    /**
     * Which reversal voided this row. Null unless status == VOIDED.
     *
     * Not compensation bookkeeping -- nothing un-voids. It exists so a
     * re-requested reversal can be answered idempotently: seeing its own sagaId
     * here, ReversalDecisionService re-emits ACCEPTED instead of voiding twice
     * or refusing its own work.
     */
    @Column(name = "voiding_saga_id", length = 36)
    private String voidingSagaId;
}
