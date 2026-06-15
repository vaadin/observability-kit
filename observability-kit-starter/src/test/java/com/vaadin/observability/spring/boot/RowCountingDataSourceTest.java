/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.boot;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import com.vaadin.observability.micrometer.MeterNames;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link RowCountingDataSource} counts result-set rows and records the
 * {@code vaadin.db.fetch.rows} summary on close.
 */
class RowCountingDataSourceTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void iteratingResultSet_recordsRowCount() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, true, false);

        DataSource ds = new RowCountingDataSource(delegate,
                new DatabaseFetchMetrics(registry));
        try (Connection c = ds.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select 1")) {
            while (rs.next()) {
                // drain
            }
        }

        DistributionSummary summary = registry.find(MeterNames.DB_FETCH_ROWS)
                .summary();
        assertThat(summary).isNotNull();
        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.totalAmount()).isEqualTo(3.0);
        // No active UI in a unit test, so the fetch is attributed to _unknown.
        assertThat(summary.getId().getTag(MeterNames.TAG_ROUTE))
                .isEqualTo(MeterNames.ROUTE_UNKNOWN);
    }

    @Test
    void preparedStatementProxy_isCastable() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement prepared = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(prepared);
        when(prepared.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);

        DataSource ds = new RowCountingDataSource(delegate,
                new DatabaseFetchMetrics(registry));
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("select 1");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                // drain
            }
        }

        assertThat(
                registry.find(MeterNames.DB_FETCH_ROWS).summary().totalAmount())
                .isEqualTo(1.0);
    }
}
