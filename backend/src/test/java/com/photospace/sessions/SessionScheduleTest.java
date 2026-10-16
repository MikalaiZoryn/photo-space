package com.photospace.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.photospace.shared.web.InvalidFieldsException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class SessionScheduleTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    @Test
    void convertsWallClockTimeInTheWorkspaceZoneToUtc() {
        SessionSchedule summer = SessionSchedule.resolve(at("2026-07-04T10:00"), at("2026-07-04T12:30"), WARSAW);
        assertThat(summer.startsAt()).isEqualTo(Instant.parse("2026-07-04T08:00:00Z")); // CEST, UTC+2
        assertThat(summer.endsAt()).isEqualTo(Instant.parse("2026-07-04T10:30:00Z"));

        SessionSchedule winter = SessionSchedule.resolve(at("2026-12-04T10:00"), null, WARSAW);
        assertThat(winter.startsAt()).isEqualTo(Instant.parse("2026-12-04T09:00:00Z")); // CET, UTC+1
        assertThat(winter.endsAt()).isNull();
    }

    @Test
    void sameWallClockTimeIsADifferentInstantInAnotherZone() {
        SessionSchedule newYork =
                SessionSchedule.resolve(at("2026-07-04T10:00"), null, ZoneId.of("America/New_York"));
        assertThat(newYork.startsAt()).isEqualTo(Instant.parse("2026-07-04T14:00:00Z"));
    }

    @Test
    void rejectsATimeSkippedByTheSpringForwardChange() {
        // On 2027-03-28 Warsaw clocks jump from 02:00 to 03:00.
        assertThatThrownBy(() -> SessionSchedule.resolve(at("2027-03-28T02:30"), null, WARSAW))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .containsEntry("startLocal",
                                "does not exist in Europe/Warsaw because the clocks change at that time"));

        SessionSchedule justAfter = SessionSchedule.resolve(at("2027-03-28T03:00"), null, WARSAW);
        assertThat(justAfter.startsAt()).isEqualTo(Instant.parse("2027-03-28T01:00:00Z"));
    }

    @Test
    void resolvesATimeRepeatedByTheFallBackChangeToTheEarlierOne() {
        // On 2026-10-25 Warsaw clocks go back from 03:00 to 02:00, so 02:30 happens twice.
        SessionSchedule schedule = SessionSchedule.resolve(at("2026-10-25T02:30"), at("2026-10-25T03:30"), WARSAW);
        assertThat(schedule.startsAt()).isEqualTo(Instant.parse("2026-10-25T00:30:00Z")); // still CEST
        assertThat(schedule.endsAt()).isEqualTo(Instant.parse("2026-10-25T02:30:00Z"));
    }

    @Test
    void endNeedsAStartAndMustFollowIt() {
        assertThatThrownBy(() -> SessionSchedule.resolve(null, at("2026-07-04T12:00"), WARSAW))
                .isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors()).containsOnlyKeys("startLocal"));
        assertThatThrownBy(() -> SessionSchedule.resolve(at("2026-07-04T12:00"), at("2026-07-04T12:00"), WARSAW))
                .isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors()).containsEntry("endLocal", "must be after the start"));
        assertThatThrownBy(() -> SessionSchedule.resolve(at("2026-07-04T12:00"), at("2026-07-04T11:00"), WARSAW))
                .isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void endIsComparedAsInstantsAcrossAClockChange() {
        // 02:50 (first, CEST) to 02:10 (read as the earlier, CEST) is backwards even though both exist.
        assertThatThrownBy(() -> SessionSchedule.resolve(at("2026-10-25T02:50"), at("2026-10-25T02:10"), WARSAW))
                .isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void rejectsImplausibleYears() {
        assertThatThrownBy(() -> SessionSchedule.resolve(at("1999-12-31T10:00"), null, WARSAW))
                .isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors()).containsOnlyKeys("startLocal"));
        assertThatThrownBy(() -> SessionSchedule.resolve(at("2026-01-01T10:00"), at("2101-01-01T10:00"), WARSAW))
                .isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors()).containsOnlyKeys("endLocal"));
    }

    @Test
    void noTimesIsAnUnscheduledSession() {
        SessionSchedule schedule = SessionSchedule.resolve(null, null, WARSAW);
        assertThat(schedule.startsAt()).isNull();
        assertThat(schedule.endsAt()).isNull();
    }

    private static LocalDateTime at(String local) {
        return LocalDateTime.parse(local);
    }
}
