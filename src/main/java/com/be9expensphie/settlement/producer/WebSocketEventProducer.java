package com.be9expensphie.settlement.producer;

import com.be9expensphie.common.event.WebSocketEvent;
import com.be9expensphie.settlement.entity.SettlementEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketEventProducer {

    private final KafkaTemplate<String, WebSocketEvent> webSocketKafkaTemplate;

    public void publishSettlementPaid(SettlementEntity settlement) {
        String payload = "{\"settlementId\":" + settlement.getId()
                + ",\"householdId\":" + settlement.getHouseholdId()
                + ",\"fromMemberId\":" + settlement.getFromMemberId()
                + ",\"toMemberId\":" + settlement.getToMemberId()
                + ",\"status\":\"COMPLETED\"}";

        WebSocketEvent event = WebSocketEvent.builder()
                .destination("/topic/households/" + settlement.getHouseholdId() + "/settlement")
                .payload(payload)
                .build();

        webSocketKafkaTemplate.send("websocket-events", String.valueOf(settlement.getHouseholdId()), event);
        log.info("Published WebSocketEvent: settlementId={} PAID", settlement.getId());
    }
}
