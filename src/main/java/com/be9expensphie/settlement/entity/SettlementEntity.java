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
