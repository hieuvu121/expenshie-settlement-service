package com.be9expensphie.settlement.security;

import com.be9expensphie.settlement.entity.HouseholdMemberSummary;
import com.be9expensphie.settlement.exception.ForbiddenException;
import com.be9expensphie.settlement.repository.HouseholdMemberSummaryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Every settlement endpoint took memberId from the URL and nothing checked it
 * against the caller.
 *
 * SettlementService's guards compared that path parameter against itself --
 * "only the paying member can toggle" was satisfied by passing the paying
 * member's id -- so any authenticated user could mark someone else's debt paid
 * or read their balances. This is the check that was missing.
 */
@ExtendWith(MockitoExtension.class)
class SettlementAuthorizerTest {

    private static final long USER_ID = 7L;
    private static final long MEMBER_ID = 11L;
    private static final long HOUSEHOLD_ID = 3L;

    @Mock private HouseholdMemberSummaryRepository membershipRepo;

    @InjectMocks private SettlementAuthorizer authorizer;

    private static HouseholdMemberSummary membership(Instant removedAt) {
        return HouseholdMemberSummary.builder()
                .memberId(MEMBER_ID)
                .householdId(HOUSEHOLD_ID)
                .userId(USER_ID)
                .removedAt(removedAt)
                .build();
    }

    @Test
    void aCallerActingAsTheirOwnMemberIsAllowed() {
        when(membershipRepo.findByMemberIdAndUserId(MEMBER_ID, USER_ID))
                .thenReturn(Optional.of(membership(null)));

        assertThatCode(() -> authorizer.requireOwnMember(USER_ID, MEMBER_ID))
                .doesNotThrowAnyException();
    }

    /** The actual vulnerability: acting as a member id that is not yours. */
    @Test
    void aCallerActingAsSomeoneElsesMemberIsRefused() {
        when(membershipRepo.findByMemberIdAndUserId(MEMBER_ID, 99L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authorizer.requireOwnMember(99L, MEMBER_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    /*
     * A member who has left keeps their outstanding debts and has to stay able
     * to settle them, so the tombstone must not lock them out. This is why the
     * lookup does not filter on removedAt.
     */
    @Test
    void aRemovedMemberCanStillActOnTheirOwnSettlements() {
        when(membershipRepo.findByMemberIdAndUserId(MEMBER_ID, USER_ID))
                .thenReturn(Optional.of(membership(Instant.now())));

        assertThatCode(() -> authorizer.requireOwnMember(USER_ID, MEMBER_ID))
                .doesNotThrowAnyException();
    }

    /*
     * A missing projection row is refused rather than waved through. On a fresh
     * deployment the replay may not reach far enough back, which locks older
     * members out until a backfill runs -- the safe direction to fail, but it
     * has to be a deliberate choice rather than an accident.
     */
    @Test
    void anUnknownMembershipIsRefusedRatherThanAssumedValid() {
        when(membershipRepo.findByMemberIdAndUserId(MEMBER_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authorizer.requireOwnMember(USER_ID, MEMBER_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aMissingCallerIdentityIsRefused() {
        assertThatThrownBy(() -> authorizer.requireOwnMember(null, MEMBER_ID))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> authorizer.requireHouseholdMember(null, HOUSEHOLD_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aCurrentMemberMayReadTheWholeHousehold() {
        when(membershipRepo.existsByUserIdAndHouseholdIdAndRemovedAtIsNull(USER_ID, HOUSEHOLD_ID))
                .thenReturn(true);

        assertThatCode(() -> authorizer.requireHouseholdMember(USER_ID, HOUSEHOLD_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void aNonMemberMayNotReadTheWholeHousehold() {
        when(membershipRepo.existsByUserIdAndHouseholdIdAndRemovedAtIsNull(99L, HOUSEHOLD_ID))
                .thenReturn(false);

        assertThatThrownBy(() -> authorizer.requireHouseholdMember(99L, HOUSEHOLD_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    /*
     * The asymmetry that matters: a departed member keeps access to their own
     * debts (test above) but loses the household-wide view.
     */
    @Test
    void aRemovedMemberMayNotReadTheWholeHousehold() {
        when(membershipRepo.existsByUserIdAndHouseholdIdAndRemovedAtIsNull(USER_ID, HOUSEHOLD_ID))
                .thenReturn(false);

        assertThatThrownBy(() -> authorizer.requireHouseholdMember(USER_ID, HOUSEHOLD_ID))
                .isInstanceOf(ForbiddenException.class);
    }
}
