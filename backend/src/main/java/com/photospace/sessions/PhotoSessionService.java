package com.photospace.sessions;

import com.photospace.audit.AuditAction;
import com.photospace.audit.AuditService;
import com.photospace.audit.AuditTarget;
import com.photospace.clients.ClientService;
import com.photospace.clients.ClientService.ClientSummary;
import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.shared.web.ConflictException;
import com.photospace.shared.web.InvalidFieldsException;
import com.photospace.shared.web.NotFoundException;
import com.photospace.workspaces.WorkspaceContext;
import com.photospace.workspaces.WorkspaceService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A workspace's photography sessions. Every query is scoped by the verified workspace. Times are stored as UTC
 * instants and converted from and to the workspace time zone at this boundary. Status changes go only through
 * {@link #changeStatus}, which enforces {@link SessionStatus}'s transitions, audits the change, and publishes
 * {@link SessionStatusChanged} in the same transaction.
 */
@Service
public class PhotoSessionService {

    private static final String COLUMNS = """
            id, client_id, title, session_type, status, starts_at, ends_at, location, notes,
            archived_at, version, created_at, updated_at""";

    private final JdbcClient jdbc;
    private final WorkspaceService workspaces;
    private final ClientService clients;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public PhotoSessionService(JdbcClient jdbc, WorkspaceService workspaces, ClientService clients, AuditService audit,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.clients = clients;
        this.audit = audit;
        this.events = events;
    }

    /** Creates an inquiry. Booking it is a separate status change. */
    @Transactional
    SessionView create(WorkspaceContext workspace, SessionInput input) {
        ZoneId zone = workspaces.zone(workspace);
        SessionSchedule schedule = SessionSchedule.resolve(input.startLocal(), input.endLocal(), zone);
        requireLinkableClient(workspace, input.clientId(), null);
        UUID id = jdbc.sql("""
                        INSERT INTO photo_sessions
                               (workspace_id, client_id, title, session_type, status, starts_at, ends_at, location, notes)
                        VALUES (:workspaceId, :clientId, :title, :sessionType, :status, :startsAt, :endsAt, :location, :notes)
                        RETURNING id
                        """)
                .params(fields(input))
                .param("workspaceId", workspace.workspaceId())
                .param("status", SessionStatus.INQUIRY.name())
                .param("startsAt", timestamp(schedule.startsAt()))
                .param("endsAt", timestamp(schedule.endsAt()))
                .query(UUID.class)
                .single();
        return get(workspace, id);
    }

    @Transactional(readOnly = true)
    SessionView get(WorkspaceContext workspace, UUID sessionId) {
        SessionRow row = find(workspace, sessionId, false).orElseThrow(NotFoundException::new);
        return view(row, workspaces.zone(workspace), clientSummaries(workspace, List.of(row)));
    }

    /**
     * Active or archived sessions, optionally of one status or one client. Newest first; unscheduled sessions
     * come first because they still need a date.
     */
    @Transactional(readOnly = true)
    PageView<SessionView> list(
            WorkspaceContext workspace, SessionStatus status, UUID clientId, boolean archived, PageQuery page) {
        String where = " WHERE workspace_id = :workspaceId AND archived_at IS " + (archived ? "NOT NULL" : "NULL")
                + (status == null ? "" : " AND status = :status")
                + (clientId == null ? "" : " AND client_id = :clientId");
        String statusName = status == null ? null : status.name();

        long total = jdbc.sql("SELECT count(*) FROM photo_sessions" + where)
                .param("workspaceId", workspace.workspaceId())
                .param("status", statusName)
                .param("clientId", clientId)
                .query(Long.class)
                .single();
        List<SessionRow> rows = jdbc.sql("SELECT " + COLUMNS + " FROM photo_sessions" + where
                        + " ORDER BY starts_at DESC NULLS FIRST, created_at DESC, id LIMIT :limit OFFSET :offset")
                .param("workspaceId", workspace.workspaceId())
                .param("status", statusName)
                .param("clientId", clientId)
                .param("limit", page.size())
                .param("offset", page.offset())
                .query(PhotoSessionService::sessionRow)
                .list();

        ZoneId zone = workspaces.zone(workspace);
        Map<UUID, ClientSummary> linked = clientSummaries(workspace, rows);
        return PageView.of(rows, page, total).map(row -> view(row, zone, linked));
    }

    /**
     * Saves new field values if {@code version} is still current. Archived sessions must be restored first. A
     * session can keep its link to a client that was archived later, but cannot be newly linked to one.
     */
    @Transactional
    SessionView update(WorkspaceContext workspace, UUID sessionId, int version, SessionInput input) {
        SessionRow current = find(workspace, sessionId, true).orElseThrow(NotFoundException::new);
        requireEditable(current);
        if (current.version() != version) {
            throw new ConflictException("This session was changed by someone else. Reload it and try again.");
        }
        SessionSchedule schedule =
                SessionSchedule.resolve(input.startLocal(), input.endLocal(), workspaces.zone(workspace));
        if (schedule.startsAt() == null && current.status().requiresStart()) {
            throw InvalidFieldsException.of("startLocal", "is required once a session is booked");
        }
        requireLinkableClient(workspace, input.clientId(), current.clientId());

        jdbc.sql("""
                        UPDATE photo_sessions
                           SET client_id = :clientId, title = :title, session_type = :sessionType,
                               starts_at = :startsAt, ends_at = :endsAt, location = :location, notes = :notes,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .params(fields(input))
                .param("startsAt", timestamp(schedule.startsAt()))
                .param("endsAt", timestamp(schedule.endsAt()))
                .param("id", sessionId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        return get(workspace, sessionId);
    }

    /**
     * Moves the session to {@code target} if {@link SessionStatus} allows it. Asking for the current status
     * changes nothing, so a retried request is harmless.
     */
    @Transactional
    SessionView changeStatus(WorkspaceContext workspace, UUID sessionId, SessionStatus target) {
        SessionRow current = find(workspace, sessionId, true).orElseThrow(NotFoundException::new);
        requireEditable(current);
        if (current.status() == target) {
            return get(workspace, sessionId);
        }
        if (!current.status().canChangeTo(target)) {
            throw new ConflictException(
                    "A session cannot change from " + current.status() + " to " + target + ".");
        }
        if (target.requiresStart() && current.startsAt() == null) {
            throw new ConflictException("Set a start time before changing the session to " + target + ".");
        }
        jdbc.sql("""
                        UPDATE photo_sessions SET status = :status, version = version + 1, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("status", target.name())
                .param("id", sessionId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        audit.record(workspace, AuditAction.SESSION_STATUS_CHANGED, AuditTarget.SESSION, sessionId,
                current.status() + " to " + target);
        events.publishEvent(new SessionStatusChanged(workspace.workspaceId(), sessionId, current.clientId(),
                current.status(), target, workspace.userId(), Instant.now()));
        return get(workspace, sessionId);
    }

    /** Hides the session from default lists. Archiving an archived session changes nothing. */
    @Transactional
    SessionView archive(WorkspaceContext workspace, UUID sessionId) {
        return setArchived(workspace, sessionId, true);
    }

    @Transactional
    SessionView restore(WorkspaceContext workspace, UUID sessionId) {
        return setArchived(workspace, sessionId, false);
    }

    private SessionView setArchived(WorkspaceContext workspace, UUID sessionId, boolean archived) {
        jdbc.sql("""
                        UPDATE photo_sessions
                           SET archived_at = CASE WHEN :archived THEN now() END,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId AND (archived_at IS NOT NULL) <> :archived
                        """)
                .param("archived", archived)
                .param("id", sessionId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        return get(workspace, sessionId);
    }

    /** A session of this workspace, archived or not. Empty for unknown IDs and other workspaces' sessions alike. */
    @Transactional(readOnly = true)
    public Optional<SessionSummary> findSummary(WorkspaceContext workspace, UUID sessionId) {
        return find(workspace, sessionId, false).map(PhotoSessionService::summary);
    }

    private static SessionSummary summary(SessionRow row) {
        return new SessionSummary(row.id(), row.title(), row.sessionType(), row.archivedAt() != null);
    }

    /** Summaries of this workspace's sessions among {@code sessionIds}; other IDs are left out. */
    @Transactional(readOnly = true)
    public Map<UUID, SessionSummary> summaries(WorkspaceContext workspace, Collection<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM photo_sessions WHERE workspace_id = :workspaceId AND id IN (:ids)")
                .param("workspaceId", workspace.workspaceId())
                .param("ids", List.copyOf(sessionIds))
                .query(PhotoSessionService::sessionRow)
                .stream()
                .map(PhotoSessionService::summary)
                .collect(Collectors.toMap(SessionSummary::id, Function.identity()));
    }

    /**
     * The client must belong to this workspace (a foreign ID gets the same answer as an unknown one) and must
     * not be archived, unless the session already links to it.
     */
    private void requireLinkableClient(WorkspaceContext workspace, UUID clientId, UUID currentClientId) {
        if (clientId == null) {
            return;
        }
        ClientSummary client = clients.findSummary(workspace, clientId)
                .orElseThrow(() -> InvalidFieldsException.of("clientId", "does not match a client in this workspace"));
        if (client.archived() && !clientId.equals(currentClientId)) {
            throw InvalidFieldsException.of("clientId", "is archived; restore the client first");
        }
    }

    private static void requireEditable(SessionRow session) {
        if (session.archivedAt() != null) {
            throw new ConflictException("This session is archived. Restore it before changing it.");
        }
    }

    private Optional<SessionRow> find(WorkspaceContext workspace, UUID sessionId, boolean forUpdate) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM photo_sessions WHERE id = :id AND workspace_id = :workspaceId"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("id", sessionId)
                .param("workspaceId", workspace.workspaceId())
                .query(PhotoSessionService::sessionRow)
                .optional();
    }

    private Map<UUID, ClientSummary> clientSummaries(WorkspaceContext workspace, List<SessionRow> rows) {
        return clients.summaries(workspace, rows.stream()
                .map(SessionRow::clientId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
    }

    /** The input's columns other than the schedule, which needs the workspace time zone. */
    private static Map<String, Object> fields(SessionInput input) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("clientId", input.clientId());
        fields.put("title", input.title());
        fields.put("sessionType", input.sessionType());
        fields.put("location", input.location());
        fields.put("notes", input.notes());
        return fields;
    }

    private static SessionView view(SessionRow row, ZoneId zone, Map<UUID, ClientSummary> clientsById) {
        return new SessionView(
                row.id(),
                row.title(),
                row.sessionType(),
                row.status(),
                row.archivedAt() == null ? row.status().next() : List.of(),
                row.clientId() == null ? null : clientsById.get(row.clientId()),
                row.startsAt(),
                row.endsAt(),
                local(row.startsAt(), zone),
                local(row.endsAt(), zone),
                zone.getId(),
                row.location(),
                row.notes(),
                row.archivedAt(),
                row.version(),
                row.createdAt(),
                row.updatedAt());
    }

    private static LocalDateTime local(Instant instant, ZoneId zone) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, zone);
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static SessionRow sessionRow(ResultSet rs, int rowNum) throws SQLException {
        return new SessionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("client_id", UUID.class),
                rs.getString("title"),
                rs.getString("session_type"),
                SessionStatus.valueOf(rs.getString("status")),
                instant(rs, "starts_at"),
                instant(rs, "ends_at"),
                rs.getString("location"),
                rs.getString("notes"),
                instant(rs, "archived_at"),
                rs.getInt("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record SessionRow(
            UUID id,
            UUID clientId,
            String title,
            String sessionType,
            SessionStatus status,
            Instant startsAt,
            Instant endsAt,
            String location,
            String notes,
            Instant archivedAt,
            int version,
            Instant createdAt,
            Instant updatedAt) {}

    /** What other modules need to know about a session. */
    public record SessionSummary(UUID id, String title, String sessionType, boolean archived) {}

    /**
     * A session as the API returns it. {@code startsAt}/{@code endsAt} are UTC instants; {@code startLocal}/
     * {@code endLocal} are the same moments as wall-clock times in {@code timezone}, the workspace's current
     * time zone. {@code allowedStatuses} lists the statuses it can change to now.
     */
    public record SessionView(
            UUID id,
            String title,
            String sessionType,
            SessionStatus status,
            List<SessionStatus> allowedStatuses,
            ClientSummary client,
            Instant startsAt,
            Instant endsAt,
            LocalDateTime startLocal,
            LocalDateTime endLocal,
            String timezone,
            String location,
            String notes,
            Instant archivedAt,
            int version,
            Instant createdAt,
            Instant updatedAt) {}
}
