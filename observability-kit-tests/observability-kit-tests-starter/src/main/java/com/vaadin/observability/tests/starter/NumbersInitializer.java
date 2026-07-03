/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.tests.starter;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Seeds the embedded H2 database with {@value #TOTAL} rows so
 * {@link DatabaseView} can issue both a small and a large fetch against real
 * JDBC, driving the {@code vaadin.db.fetch.rows} summary. Part of the
 * {@code db-demo} profile (see {@link DbDemoConfig}).
 */
@Component
@Profile("db-demo")
public class NumbersInitializer implements CommandLineRunner {

    static final int TOTAL = 1000;

    private final JdbcTemplate jdbc;

    public NumbersInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS numbers (id INT PRIMARY KEY)");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM numbers",
                Integer.class);
        if (count != null && count == 0) {
            List<Object[]> rows = new ArrayList<>(TOTAL);
            for (int i = 1; i <= TOTAL; i++) {
                rows.add(new Object[] { i });
            }
            jdbc.batchUpdate("INSERT INTO numbers (id) VALUES (?)", rows);
        }
    }
}
