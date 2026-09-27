package com.be9expensphie.settlement.consumer;

import com.be9expensphie.common.event.HouseholdMemberEvent;
import com.be9expensphie.settlement.entity.HouseholdMemberSummary;
import com.be9expensphie.settlement.repository.HouseholdMemberSummaryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The projection SettlementAuthorizer reads. Without it this service has no
 * mapping from the caller's userId to the memberId its rows are keyed by, which
 * is why every endpoint used to trust the memberId in the URL.
 */
@ExtendWith(MockitoExtension.class)
class HouseholdMemberEventConsumerTest {

    private static final long MEMBER_ID = 11L;
    private static final long HOUSEHOLD_ID = 3L;
    private static final long USER_ID = 7L;

    @Mock private HouseholdMemberSummaryRepository membershipRepo;

    @InjectMocks private HouseholdMemberEventConsumer consumer;

    private static HouseholdMemberEvent event(String type) {
        return HouseholdMemberEvent.builder()
                .memberId(MEMBER_ID)
                .householdId(HOUSEHOLD_ID)
                .userId(USER_ID)
                .role("ROLE_MEMBER")
                .eventType(type)
                .build();
    }

    private HouseholdMemberSummary saved() {
        ArgumentCaptor<HouseholdMemberSummary> captor =
                ArgumentCaptor.forClass(HouseholdMemberSummary.class);
        verify(membershipRepo).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void memberJoinedRecordsTheUserToMemberMapping() {
        consumer.consume(event("MEMBER_JOINED"));

        HouseholdMemberSummary row = saved();
        assertThat(row.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(row.getUserId()).isEqualTo(USER_ID);
        assertThat(row.getHouseholdId()).isEqualTo(HOUSEHOLD_ID);
        assertThat(row.getRemovedAt()).isNull();
    }

    /*
     * Tombstoned, never deleted. A departed member keeps their outstanding
     * debts and must stay able to settle them, so deleting the row would make
     * the debt unauthorizable and therefore uncollectable in the app.
     */
    @Test
    void memberLeftStampsRemovedAtInsteadOfDeleting() {
        when(membershipRepo.findById(MEMBER_ID)).thenReturn(Optional.of(
                HouseholdMemberSummary.builder()
                        .memberId(MEMBER_ID).householdId(HOUSEHOLD_ID).userId(USER_ID)
                        .build()));

        consumer.consume(event("MEMBER_LEFT"));

        assertThat(saved().getRemovedAt()).isNotNull();
        verify(membershipRepo, never()).deleteById(any());
    }

    /** A redelivery must not move an existing tombstone. */
    @Test
    void memberLeftIsIdempotent() {
        Instant original = Instant.parse("2026-01-01T00:00:00Z");
        HouseholdMemberSummary retired = HouseholdMemberSummary.builder()
                .memberId(MEMBER_ID).householdId(HOUSEHOLD_ID).userId(USER_ID)
                .removedAt(original)
                .build();
        when(membershipRepo.findById(MEMBER_ID)).thenReturn(Optional.of(retired));

        consumer.consume(event("MEMBER_LEFT"));

        assertThat(retired.getRemovedAt()).isEqualTo(original);
        verify(membershipRepo, never()).save(any());
    }

    @Test
    void unhandledAndMalformedEventsTouchNothing() {
        assertThatCode(() -> {
            consumer.consume(event("ROLE_CHANGED"));
            consumer.consume(event(null));
            consumer.consume(null);
        }).doesNotThrowAnyException();

        verifyNoInteractions(membershipRepo);
    }
}
