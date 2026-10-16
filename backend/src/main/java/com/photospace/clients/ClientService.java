package com.photospace.clients;

import com.photospace.shared.Text;
import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.shared.web.ConflictException;
import com.photospace.shared.web.NotFoundException;
import com.photospace.workspaces.WorkspaceContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A workspace's clients. Every method takes the verified {@link WorkspaceContext} and scopes its SQL by that
 * workspace, so a client ID from another workspace behaves exactly like an unknown one. Clients are archived,
 * never deleted, so sessions and later records keep their links.
 */
@Service
public class ClientService {

    private static final String COLUMNS =
            "id, name, email, phone, notes, archived_at, version, created_at, updated_at";

    private final JdbcClient jdbc;

    public ClientService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    ClientView create(WorkspaceContext workspace, ClientInput input) {
        UUID id = jdbc.sql("""
                        INSERT INTO clients (workspace_id, name, email, phone, notes)
                        VALUES (:workspaceId, :name, :email, :phone, :notes)
                        RETURNING id
                        """)
                .param("workspaceId", workspace.workspaceId())
                .params(fields(input))
                .query(UUID.class)
                .single();
        return get(workspace, id);
    }

    @Transactional(readOnly = true)
    ClientView get(WorkspaceContext workspace, UUID clientId) {
        return find(workspace, clientId).orElseThrow(NotFoundException::new);
    }

    /**
     * Active or archived clients by name. {@code search} matches part of the name or email, ignoring case.
     */
    @Transactional(readOnly = true)
    PageView<ClientView> list(WorkspaceContext workspace, String search, boolean archived, PageQuery page) {
        String where = " WHERE workspace_id = :workspaceId AND archived_at IS " + (archived ? "NOT NULL" : "NULL")
                + (search == null ? "" : " AND (name ILIKE :pattern OR email ILIKE :pattern)");
        String pattern = search == null ? null : Text.containsPattern(search);

        long total = jdbc.sql("SELECT count(*) FROM clients" + where)
                .param("workspaceId", workspace.workspaceId())
                .param("pattern", pattern)
                .query(Long.class)
                .single();
        var items = jdbc.sql("SELECT " + COLUMNS + " FROM clients" + where
                        + " ORDER BY lower(name), id LIMIT :limit OFFSET :offset")
                .param("workspaceId", workspace.workspaceId())
                .param("pattern", pattern)
                .param("limit", page.size())
                .param("offset", page.offset())
                .query(ClientService::clientView)
                .list();
        return PageView.of(items, page, total);
    }

    /**
     * Saves new field values if {@code version} is still current. Archived clients must be restored first.
     */
    @Transactional
    ClientView update(WorkspaceContext workspace, UUID clientId, int version, ClientInput input) {
        int updated = jdbc.sql("""
                        UPDATE clients
                           SET name = :name, email = :email, phone = :phone, notes = :notes,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                           AND version = :version AND archived_at IS NULL
                        """)
                .params(fields(input))
                .param("id", clientId)
                .param("workspaceId", workspace.workspaceId())
                .param("version", version)
                .update();
        if (updated == 0) {
            ClientView current = get(workspace, clientId);
            throw new ConflictException(current.archivedAt() != null
                    ? "This client is archived. Restore it before editing."
                    : "This client was changed by someone else. Reload it and try again.");
        }
        return get(workspace, clientId);
    }

    /** Hides the client from default lists. Archiving an archived client changes nothing. */
    @Transactional
    ClientView archive(WorkspaceContext workspace, UUID clientId) {
        return setArchived(workspace, clientId, true);
    }

    @Transactional
    ClientView restore(WorkspaceContext workspace, UUID clientId) {
        return setArchived(workspace, clientId, false);
    }

    /** A client of this workspace, archived or not. Empty for unknown IDs and other workspaces' clients alike. */
    @Transactional(readOnly = true)
    public Optional<ClientSummary> findSummary(WorkspaceContext workspace, UUID clientId) {
        return find(workspace, clientId).map(ClientView::summary);
    }

    /** Summaries of the given clients of this workspace, by ID. IDs from other workspaces are left out. */
    @Transactional(readOnly = true)
    public Map<UUID, ClientSummary> summaries(WorkspaceContext workspace, Collection<UUID> clientIds) {
        if (clientIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM clients WHERE workspace_id = :workspaceId AND id IN (:ids)")
                .param("workspaceId", workspace.workspaceId())
                .param("ids", clientIds)
                .query(ClientService::clientView)
                .stream()
                .map(ClientView::summary)
                .collect(Collectors.toMap(ClientSummary::id, Function.identity()));
    }

    private Optional<ClientView> find(WorkspaceContext workspace, UUID clientId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM clients WHERE id = :id AND workspace_id = :workspaceId")
                .param("id", clientId)
                .param("workspaceId", workspace.workspaceId())
                .query(ClientService::clientView)
                .optional();
    }

    private ClientView setArchived(WorkspaceContext workspace, UUID clientId, boolean archived) {
        jdbc.sql("""
                        UPDATE clients
                           SET archived_at = CASE WHEN :archived THEN now() END,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId AND (archived_at IS NOT NULL) <> :archived
                        """)
                .param("archived", archived)
                .param("id", clientId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        return get(workspace, clientId);
    }

    private static Map<String, Object> fields(ClientInput input) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", input.name());
        fields.put("email", input.email());
        fields.put("phone", input.phone());
        fields.put("notes", input.notes());
        return fields;
    }

    private static ClientView clientView(ResultSet rs, int rowNum) throws SQLException {
        return new ClientView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("email"),
                rs.getString("phone"),
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

    public record ClientView(
            UUID id,
            String name,
            String email,
            String phone,
            String notes,
            Instant archivedAt,
            int version,
            Instant createdAt,
            Instant updatedAt) {

        ClientSummary summary() {
            return new ClientSummary(id, name, archivedAt != null);
        }
    }

    /** What other modules show about a linked client. */
    public record ClientSummary(UUID id, String name, boolean archived) {}
}
