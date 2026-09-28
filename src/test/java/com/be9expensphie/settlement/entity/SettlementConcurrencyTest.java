package com.be9expensphie.settlement.entity;

import com.be9expensphie.settlement.enums.SettlementStatus;
import com.be9expensphie.settlement.repository.SettlementRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The read-then-write race between a reversal and a payment.
 *
 * ReversalDecisionService reads the settlements for an expense, checks that
 * none is COMPLETED, and voids them. @Transactional alone does not make that
 * atomic: under InnoDB REPEATABLE READ the SELECT is a consistent non-locking
 * read while the UPDATE is a current read, so an approval committing in the gap
 * is simply overwritten -- a debt somebody actually paid becomes VOIDED and the
 * money disappears.
 *
 * It is symmetric. SettlementService.approveSettlement reads with findById,
 * checks the status from that snapshot, and writes -- so it can equally stamp
 * COMPLETED over a row a reversal has just voided, with rejectIfVoided reading
 * stale data and waving it through.
 *
 * @Version closes both directions at the persistence layer, which is why it is
 * preferable to pessimistic locks here: it needs no annotation on any
 * individual mutator, so a path added later cannot forget it.
 *
 * Runs two genuine transactions rather than detaching a copy, because the
 * failure being reproduced is about commit ordering.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        /* See OutboxWriterTest: without this the transactions below self-commit. */
        "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false"
})
class SettlementConcurrencyTest {

    @Autowired private SettlementRepository settlementRepo;
    @Autowired private PlatformTransactionManager txManager;

    /*
     * NOT_SUPPORTED on every test. @DataJpaTest wraps each method in its own
     * transaction, and a TransactionTemplate inside that simply joins it -- one
     * persistence context, so the "two reads" below would hand back the same
     * managed instance and no conflict could ever occur. The tests would pass
     * or fail for reasons unrelated to the version column.
     */

    private <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    @org.junit.jupiter.api.BeforeEach
    void clear() {
        /* These commit for real, so rows outlive the test without this. */
        settlementRepo.deleteAll();
    }

    private Long aPendingSettlement() {
        return inTx(() -> settlementRepo.save(SettlementEntity.builder()
                .expenseId(42L)
                .householdId(3L)
                .fromMemberId(11L)
                .toMemberId(12L)
                .amount(new BigDecimal("25.00"))
                .status(SettlementStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build()).getId());
    }

    /*
     * The one that matters. The reversal read the row while it was still
     * PENDING; by the time it writes, the debt has been settled. Losing that
     * write is the whole point -- the alternative is voiding paid money.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aReversalCannotVoidASettlementThatWasPaidAfterItLooked() {
        Long id = aPendingSettlement();

        SettlementEntity seenByReversal = inTx(() -> settlementRepo.findById(id).orElseThrow());
        SettlementEntity seenByApproval = inTx(() -> settlementRepo.findById(id).orElseThrow());

        /* The creditor approves first and commits. */
        inTx(() -> {
            seenByApproval.setStatus(SettlementStatus.COMPLETED);
            return settlementRepo.save(seenByApproval);
        });

        /* The reversal now tries to void what it read before that. */
        seenByReversal.setStatus(SettlementStatus.VOIDED);
        seenByReversal.setVoidingSagaId("saga-1");

        assertThatThrownBy(() -> inTx(() -> settlementRepo.save(seenByReversal)))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(inTx(() -> settlementRepo.findById(id).orElseThrow().getStatus()))
                .isEqualTo(SettlementStatus.COMPLETED);
    }

    /* The mirror image: a payment must not land on an already-voided debt. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anApprovalCannotLandOnASettlementThatWasVoidedAfterItLooked() {
        Long id = aPendingSettlement();

        SettlementEntity seenByApproval = inTx(() -> settlementRepo.findById(id).orElseThrow());
        SettlementEntity seenByReversal = inTx(() -> settlementRepo.findById(id).orElseThrow());

        inTx(() -> {
            seenByReversal.setStatus(SettlementStatus.VOIDED);
            seenByReversal.setVoidingSagaId("saga-1");
            return settlementRepo.save(seenByReversal);
        });

        seenByApproval.setStatus(SettlementStatus.COMPLETED);

        assertThatThrownBy(() -> inTx(() -> settlementRepo.save(seenByApproval)))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(inTx(() -> settlementRepo.findById(id).orElseThrow().getStatus()))
                .isEqualTo(SettlementStatus.VOIDED);
    }

    /** Uncontended writes must stay ordinary -- the version is not a lock. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sequentialWritesAreUnaffected() {
        Long id = aPendingSettlement();

        inTx(() -> {
            SettlementEntity s = settlementRepo.findById(id).orElseThrow();
            s.setStatus(SettlementStatus.AWAITING_APPROVAL);
            return settlementRepo.save(s);
        });
        inTx(() -> {
            SettlementEntity s = settlementRepo.findById(id).orElseThrow();
            s.setStatus(SettlementStatus.COMPLETED);
            return settlementRepo.save(s);
        });

        assertThat(inTx(() -> settlementRepo.findById(id).orElseThrow().getStatus()))
                .isEqualTo(SettlementStatus.COMPLETED);
    }
}
