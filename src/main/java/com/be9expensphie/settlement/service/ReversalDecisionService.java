package com.be9expensphie.settlement.service;

import com.be9expensphie.common.event.ExpenseReversalDecided;
import com.be9expensphie.common.event.ReversalOutcome;
import com.be9expensphie.settlement.entity.SettlementEntity;
import com.be9expensphie.settlement.enums.SettlementStatus;
import com.be9expensphie.settlement.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Predicate;

/**
 * Decides whether an expense may be reversed, and carries it out if so.
 *
 * This is the saga's pivot: before it nothing has changed and compensation is
 * free; after it the debts are gone and only forward recovery is possible.
 *
 * MUST stay a single transaction. The check for a COMPLETED settlement and the
 * voiding have to be atomic, or a creditor approving a payment between the two
 * slips through and a settled debt gets voided.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReversalDecisionService {

    private static final List<SettlementStatus> OPEN =
            List.of(SettlementStatus.PENDING, SettlementStatus.AWAITING_APPROVAL);

    private final SettlementRepository settlementRepo;

    @Transactional
    public ExpenseReversalDecided decide(String sagaId, Long expenseId) {
        List<SettlementEntity> settlements = settlementRepo.findByExpenseId(expenseId);

        /*
         * (1) Replay. A lost reply leaves expense-service re-requesting the same
         * sagaId; it must get the same answer rather than a refusal for work
         * this saga itself did. This branch is what makes the sweep safe.
         */
        List<Long> alreadyOurs = idsWhere(settlements, s -> sagaId.equals(s.getVoidingSagaId()));
        if (!alreadyOurs.isEmpty()) {
            log.info("Reversal {} already applied to expenseId={}; re-confirming", sagaId, expenseId);
            return accepted(sagaId, expenseId, alreadyOurs);
        }

        /*
         * (2) The veto, and the reason this flow is a saga at all. Somebody has
         * paid; that cannot be unwound from here. Nothing is written.
         */
        List<Long> completed = idsWhere(settlements, s -> s.getStatus() == SettlementStatus.COMPLETED);
        if (!completed.isEmpty()) {
            log.info("Refusing reversal {} for expenseId={}: {} settlement(s) already completed",
                    sagaId, expenseId, completed.size());
            return refused(sagaId, expenseId,
                    "Cannot reverse: this expense has already been settled", completed);
        }

        /* (3) A different reversal got here first. Not ours to redo. */
        List<Long> voidedByOthers = idsWhere(settlements,
                s -> s.getVoidingSagaId() != null && !sagaId.equals(s.getVoidingSagaId()));
        if (!voidedByOthers.isEmpty()) {
            return refused(sagaId, expenseId,
                    "Cannot reverse: this expense has already been reversed", voidedByOthers);
        }

        /* (4) All clear. Void everything open, in this one transaction. */
        List<SettlementEntity> open = settlements.stream()
                .filter(s -> OPEN.contains(s.getStatus()))
                .toList();

        for (SettlementEntity s : open) {
            s.setStatus(SettlementStatus.VOIDED);
            s.setVoidingSagaId(sagaId);
        }
        if (!open.isEmpty()) {
            settlementRepo.saveAll(open);
        }

        List<Long> voided = open.stream().map(SettlementEntity::getId).toList();
        log.info("Reversal {} voided {} settlement(s) for expenseId={}", sagaId, voided.size(), expenseId);
        return accepted(sagaId, expenseId, voided);
    }

    private static List<Long> idsWhere(List<SettlementEntity> rows, Predicate<SettlementEntity> p) {
        return rows.stream().filter(p).map(SettlementEntity::getId).toList();
    }

    private static ExpenseReversalDecided accepted(String sagaId, Long expenseId, List<Long> ids) {
        return ExpenseReversalDecided.builder()
                .sagaId(sagaId).expenseId(expenseId)
                .outcome(ReversalOutcome.ACCEPTED)
                .settlementIds(ids)
                .build();
    }

    private static ExpenseReversalDecided refused(String sagaId, Long expenseId,
                                                  String reason, List<Long> ids) {
        return ExpenseReversalDecided.builder()
                .sagaId(sagaId).expenseId(expenseId)
                .outcome(ReversalOutcome.REFUSED)
                .reason(reason)
                .settlementIds(ids)
                .build();
    }
}
