package com.photospace.sessions;

import static com.photospace.sessions.SessionStatus.BOOKED;
import static com.photospace.sessions.SessionStatus.CANCELLED;
import static com.photospace.sessions.SessionStatus.COMPLETED;
import static com.photospace.sessions.SessionStatus.DELIVERED;
import static com.photospace.sessions.SessionStatus.INQUIRY;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SessionStatusTest {

    @Test
    void followsTheBookingLifecycle() {
        assertThat(INQUIRY.next()).containsExactly(BOOKED, CANCELLED);
        assertThat(BOOKED.next()).containsExactly(COMPLETED, CANCELLED);
        assertThat(COMPLETED.next()).containsExactly(DELIVERED);
        assertThat(DELIVERED.next()).isEmpty();
        assertThat(CANCELLED.next()).containsExactly(INQUIRY);
    }

    @Test
    void cannotSkipStepsOrGoBack() {
        assertThat(INQUIRY.canChangeTo(COMPLETED)).isFalse();
        assertThat(INQUIRY.canChangeTo(DELIVERED)).isFalse();
        assertThat(BOOKED.canChangeTo(DELIVERED)).isFalse();
        assertThat(BOOKED.canChangeTo(INQUIRY)).isFalse();
        assertThat(COMPLETED.canChangeTo(CANCELLED)).isFalse();
        assertThat(DELIVERED.canChangeTo(COMPLETED)).isFalse();
        assertThat(CANCELLED.canChangeTo(BOOKED)).isFalse();
    }

    @Test
    void noStatusChangesToItself() {
        for (SessionStatus status : SessionStatus.values()) {
            assertThat(status.canChangeTo(status)).as(status.name()).isFalse();
        }
    }

    @Test
    void everyStatusFromBookedOnNeedsAStart() {
        assertThat(INQUIRY.requiresStart()).isFalse();
        assertThat(CANCELLED.requiresStart()).isFalse();
        assertThat(BOOKED.requiresStart()).isTrue();
        assertThat(COMPLETED.requiresStart()).isTrue();
        assertThat(DELIVERED.requiresStart()).isTrue();
    }
}
