package com.be9expensphie.settlement.outbox;

import com.be9expensphie.common.event.ExpenseReversalDecided;
import com.be9expensphie.common.event.ReversalOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The behaviour that makes the outbox worth having: the event row lives or dies
 * with the transaction that produced it.
 *
 * Runs against a real transaction on H2 rather than mocks, because a mocked
 * repository cannot roll anything back and would pass no matter what the
 * writer does.
 */
@DataJpaTest
@Import({OutboxWriter.class, OutboxWriterTest.TestConfig.class})
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        /*
         * Pinned rather than inherited. expense-service sets this true (its
         * Hikari runs with auto-commit=false) which tells Hibernate not to
         * disable autocommit itself; the datasource @DataJpaTest substitutes
         * has it ON, so a true value here would leave every statement
         * self-committing and the rollback test below would prove nothing.
         */
        "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false"
})
class OutboxWriterTest {

    static class TestConfig {
        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }
    }

    @Autowired private OutboxWriter writer;
    @Autowired private OutboxRepository repository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    /*
     * These tests commit for real (NOT_SUPPORTED opts out of @DataJpaTest's
     * automatic rollback, which would otherwise hide the very thing under
     * test), so rows survive into the next test unless cleared here.
     */
    @BeforeEach
    void clearOutbox() {
        repository.deleteAll();
    }

    private static ExpenseReversalDecided event() {
        return ExpenseReversalDecided.builder()
                .sagaId("saga-1")
                .expenseId(42L)
                .outcome(ReversalOutcome.ACCEPTED)
                .settlementIds(List.of(7L, 9L))
                .build();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aCommittedTransactionLeavesTheEventBehind() {
        new TransactionTemplate(txManager).executeWithoutResult(
                status -> writer.write("expense-reversal-replies", "42", event()));

        List<OutboxEvent> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        OutboxEvent row = rows.get(0);
        assertThat(row.getTopic()).isEqualTo("expense-reversal-replies");
        assertThat(row.getAggregateId()).isEqualTo("42");
        assertThat(row.getEventId()).isNotBlank();
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getPayload()).contains("\"expenseId\":42", "ACCEPTED");
    }

    /*
     * The failure the outbox exists to prevent, and the reason the reversal
     * saga needs one here: voiding the debts and then failing to announce it
     * would strand the expense in REVERSING with nothing able to resolve it.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aRolledBackTransactionLeavesNoEvent() {
        assertThatThrownBy(() ->
                new TransactionTemplate(txManager).executeWithoutResult(status -> {
                    writer.write("expense-reversal-replies", "42", event());
                    throw new IllegalStateException("something failed after the write");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(repository.findAll()).isEmpty();
    }

    /*
     * The bug this whole mechanism turned on: eventId used to be written to the
     * row and nowhere else, so the payload that reached Kafka carried no id and
     * consumers could not tell a republish from a new event.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theIdOnTheRowIsAlsoInsideThePayload() {
        new TransactionTemplate(txManager).executeWithoutResult(
                status -> writer.write("expense-reversal-replies", "42", event()));

        OutboxEvent row = repository.findAll().get(0);
        assertThat(row.getPayload()).contains("\"eventId\":\"" + row.getEventId() + "\"");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void eachEventGetsItsOwnId() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            writer.write("expense-reversal-replies", "42", event());
            writer.write("websocket-events", "42", event());
        });

        assertThat(repository.findAll())
                .extracting(OutboxEvent::getEventId)
                .doesNotHaveDuplicates()
                .hasSize(2);
    }
}
