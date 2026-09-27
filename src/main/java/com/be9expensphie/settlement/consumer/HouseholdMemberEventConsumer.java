package com.be9expensphie.settlement.consumer;

import com.be9expensphie.common.event.HouseholdMemberEvent;
import com.be9expensphie.settlement.entity.HouseholdMemberSummary;
import com.be9expensphie.settlement.repository.HouseholdMemberSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Maintains the local membership projection that authorization depends on.
 *
 * Runs on its own consumer group so it is independent of the expense-events
 * group, and with auto-offset-reset=earliest (set service-wide in
 * application.properties) so a fresh deployment rebuilds the projection from
 * whatever household-member-events still holds.
 *
 * ROLLOUT: that replay only reaches back as far as Kafka's retention. Members
 * who joined before that window will be absent, and every settlement endpoint
 * will refuse them -- see the note in SettlementAuthorizer. A backfill of
 * MEMBER_JOINED from household-service is needed before this is relied on in
 * an environment with real history.
 *
 * Idempotent by primary key: memberId is the business key, so a redelivery
 * re-writes the same row.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HouseholdMemberEventConsumer {

    private final HouseholdMemberSummaryRepository membershipRepo;

    @KafkaListener(topics = "household-member-events",
                   containerFactory = "householdMemberEventKafkaListenerContainerFactory")
    public void consume(HouseholdMemberEvent event) {
        if (event == null || event.getEventType() == null || event.getMemberId() == null) {
            log.warn("Ignoring HouseholdMemberEvent with no eventType or memberId");
            return;
        }
        switch (event.getEventType()) {
            case "MEMBER_JOINED" -> onMemberJoined(event);
            case "MEMBER_LEFT" -> onMemberLeft(event);
            default -> log.debug("Ignoring unhandled eventType={}", event.getEventType());
        }
    }

    private void onMemberJoined(HouseholdMemberEvent event) {
        membershipRepo.save(HouseholdMemberSummary.builder()
                .memberId(event.getMemberId())
                .householdId(event.getHouseholdId())
                .userId(event.getUserId())
                .build());
        log.info("Recorded membership memberId={}, userId={}, householdId={}",
                event.getMemberId(), event.getUserId(), event.getHouseholdId());
    }

    private void onMemberLeft(HouseholdMemberEvent event) {
        /*
         * Stamped, not deleted. The member keeps whatever they owe and has to
         * stay able to settle it, so the row that authorizes them must outlive
         * their membership.
         */
        membershipRepo.findById(event.getMemberId()).ifPresentOrElse(
                existing -> {
                    if (existing.getRemovedAt() == null) {
                        existing.setRemovedAt(Instant.now());
                        membershipRepo.save(existing);
                    }
                },
                () -> log.warn("MEMBER_LEFT for unknown memberId={}", event.getMemberId()));
    }
}
