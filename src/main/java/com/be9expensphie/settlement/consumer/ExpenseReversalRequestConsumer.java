package com.be9expensphie.settlement.consumer;

import com.be9expensphie.common.event.ExpenseReversalDecided;
import com.be9expensphie.common.event.ExpenseReversalRequested;
import com.be9expensphie.settlement.outbox.OutboxWriter;
import com.be9expensphie.settlement.service.ReversalDecisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers reversal requests.
 *
 * The decision and the reply are written in one transaction: the outbox row
 * commits with the voiding, so it is impossible to void the debts and then fail
 * to announce it. That announcement is the pivot's only record -- expense-service
 * has no way to ask after the fact -- which is why this service needed an outbox
 * before the flow could exist at all.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExpenseReversalRequestConsumer {

    private final ReversalDecisionService decisionService;
    private final OutboxWriter outbox;

    @KafkaListener(topics = "expense-reversal-requests",
                   containerFactory = "expenseReversalRequestedKafkaListenerContainerFactory")
    @Transactional
    public void consume(ExpenseReversalRequested event) {
        if (event == null || event.getSagaId() == null || event.getExpenseId() == null) {
            log.warn("Ignoring malformed ExpenseReversalRequested");
            return;
        }

        ExpenseReversalDecided decision =
                decisionService.decide(event.getSagaId(), event.getExpenseId());

        /* Keyed on expenseId so one expense's replies cannot reorder. */
        outbox.write("expense-reversal-replies",
                String.valueOf(event.getExpenseId()), decision);
    }
}
