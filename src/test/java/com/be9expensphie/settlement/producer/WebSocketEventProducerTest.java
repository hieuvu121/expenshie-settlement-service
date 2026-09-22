package com.be9expensphie.settlement.producer;

import com.be9expensphie.common.event.WebSocketEvent;
import com.be9expensphie.settlement.entity.SettlementEntity;
import com.be9expensphie.settlement.enums.SettlementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Nothing reaches Kafka until the transaction that decided it has committed.
 *
 * approveSettlement is @Transactional and used to send from inside it. A
 * rollback afterwards told every connected client a debt had been settled that
 * the database still holds as outstanding -- and a WebSocket push cannot be
 * retracted; the browser has already drawn it.
 *
 * KafkaTemplate is subclassed rather than mocked, since the inline mock maker
 * cannot instrument concrete classes on JDK 25.
 */
@ExtendWith(MockitoExtension.class)
class WebSocketEventProducerTest {

    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private RecordingTemplate template;
    private WebSocketEventProducer producer;

    private static class RecordingTemplate extends KafkaTemplate<String, WebSocketEvent> {
        final List<String> sent = new ArrayList<>();

        RecordingTemplate() {
            super(new DefaultKafkaProducerFactory<>(Map.of()));
        }

        @Override
        public CompletableFuture<SendResult<String, WebSocketEvent>> send(
                String topic, String key, WebSocketEvent data) {
            sent.add(topic + "|" + key + "|" + data.getDestination());
            return CompletableFuture.completedFuture(null);
        }
    }

    @BeforeEach
    void setUp() {
        template = new RecordingTemplate();
        producer = new WebSocketEventProducer(template, applicationEventPublisher);
    }

    private static SettlementEntity settlement() {
        return SettlementEntity.builder()
                .id(5L)
                .expenseId(42L)
                .householdId(3L)
                .fromMemberId(11L)
                .toMemberId(12L)
                .amount(new BigDecimal("6.25"))
                .status(SettlementStatus.COMPLETED)
                .build();
    }

    private WebSocketEventProducer.SettlementPaid staged() {
        ArgumentCaptor<WebSocketEventProducer.SettlementPaid> captor =
                ArgumentCaptor.forClass(WebSocketEventProducer.SettlementPaid.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    @Test
    void publishStagesThePushAndSendsNothingYet() {
        producer.publishSettlementPaid(settlement());

        assertThat(template.sent).isEmpty();

        WebSocketEventProducer.SettlementPaid paid = staged();
        assertThat(paid.key()).isEqualTo("3");
        assertThat(paid.event().getDestination()).isEqualTo("/topic/households/3/settlement");
        assertThat(paid.event().getPayload())
                .contains("\"settlementId\":5", "\"fromMemberId\":11", "\"status\":\"COMPLETED\"");
    }

    @Test
    void onlyTheAfterCommitCallbackReachesKafka() {
        producer.onCommitted(new WebSocketEventProducer.SettlementPaid(
                "3",
                WebSocketEvent.builder()
                        .destination("/topic/households/3/settlement")
                        .payload("{}")
                        .build()));

        assertThat(template.sent)
                .containsExactly("websocket-events|3|/topic/households/3/settlement");
    }
}
