/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.tests.starter;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.Route;

/**
 * View that issues real JDBC queries through the (proxied) {@code DataSource}
 * so the {@code vaadin.db.fetch.rows} summary is recorded under route
 * {@code db}. A small fetch returns {@value #SMALL} rows and a large fetch
 * returns every seeded row. Part of the {@code db-demo} profile (see
 * {@link DbDemoConfig}); it is not instantiated outside that profile because
 * its {@link JdbcTemplate} dependency only exists there.
 */
@Route("db")
@Profile("db-demo")
public class DatabaseView extends Div {

    static final int SMALL = 3;

    private final transient JdbcTemplate jdbc;
    private final Span result = new Span();

    public DatabaseView(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        result.setId("fetch-result");

        NativeButton small = new NativeButton("Small fetch", e -> fetch(
                "SELECT id FROM numbers ORDER BY id LIMIT " + SMALL));
        small.setId("small-fetch");

        NativeButton large = new NativeButton("Large fetch",
                e -> fetch("SELECT id FROM numbers"));
        large.setId("large-fetch");

        add(small, large, result);
    }

    private void fetch(String sql) {
        int rows = jdbc.queryForList(sql, Integer.class).size();
        result.setText("rows: " + rows);
    }
}
