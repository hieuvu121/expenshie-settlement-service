package com.be9expensphie.settlement.producer;

import com.be9expensphie.common.event.WebSocketEvent;
import com.be9expensphie.settlement.entity.SettlementEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketEventProducer {

    /** In-process carrier. Built inside the transaction, sent once it commits. */
    public record SettlementPaid(String key, WebSocketEvent event) {}

    private final KafkaTemplate<String, WebSocketEvent> webSocketKafkaTemplate;
    private final ApplicationEventPublisher applicationEventPublisher;

    /** Builds the push and stages it. Called from inside approveSettlement's transaction. */
    public void publishSettlementPaid(SettlementEntity settlement) {
        String payload = "{\"settlementId\":" + settlement.getId()
                + ",\"householdId\":" + settlement.getHouseholdId()
                + ",\"fromMemberId\":" + settlement.getFromMemberId()
                + ",\"toMemberId\":" + settlement.getToMemberId()
                + ",\"status\":\"COMPLETED\"}";

        applicationEventPublisher.publishEvent(new SettlementPaid(
                String.valueOf(settlement.getHouseholdId()),
                WebSocketEvent.builder()
                        .destination("/topic/households/" + settlement.getHouseholdId() + "/settlement")
                        .payload(payload)
                        .build()));
    }

    /**
     * The only path to Kafka, and it runs after the commit.
     *
     * approveSettlement is @Transactional and this used to send from inside it.
     * A rollback afterwards told every connected client a debt had been settled
     * that the database still holds as outstanding — and unlike a projection, a
     * WebSocket push cannot be corrected later: the browser has already drawn
     * it and nothing re-sends the truth until the user reloads.
     *
     * After-commit rather than an outbox on purpose. A push lost to a crash in
     * this gap costs a stale panel until the next refresh, which is not worth a
     * table and a poller.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(SettlementPaid paid) {
        webSocketKafkaTemplate.send("websocket-events", paid.key(), paid.event());
        log.info("Published WebSocketEvent: destination={}", paid.event().getDestination());
    }
}
