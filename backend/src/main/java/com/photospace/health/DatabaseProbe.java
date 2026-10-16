package com.photospace.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Checks that the application can run a trivial query against PostgreSQL. */
@Component
public class DatabaseProbe {

    private static final Logger log = LoggerFactory.getLogger(DatabaseProbe.class);

    private final JdbcTemplate jdbc;

    public DatabaseProbe(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isUp() {
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            return one != null && one == 1;
        } catch (RuntimeException e) {
            // Log only the exception type; connection errors can include host and user details.
            log.warn("Database probe failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
