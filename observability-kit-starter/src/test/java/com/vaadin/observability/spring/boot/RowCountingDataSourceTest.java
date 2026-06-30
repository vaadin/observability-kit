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
import java.util.ArrayList;
import java.util.List;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import com.vaadin.observability.micrometer.MeterNames;
import com.vaadin.observability.micrometer.trace.ObservationNames;

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
                new DatabaseFetchMetrics(registry), null);
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
                new DatabaseFetchMetrics(registry), null);
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

    @Test
    void query_emitsSpanWithRowCountAndStatement() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement prepared = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(prepared);
        when(prepared.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);

        ObservationRegistry observationRegistry = ObservationRegistry.create();
        List<Observation.Context> stopped = new ArrayList<>();
        observationRegistry.observationConfig()
                .observationHandler(new ObservationHandler<>() {
                    @Override
                    public boolean supportsContext(Observation.Context c) {
                        return true;
                    }

                    @Override
                    public void onStop(Observation.Context c) {
                        stopped.add(c);
                    }
                });

        DataSource ds = new RowCountingDataSource(delegate,
                new DatabaseFetchMetrics(registry),
                new DatabaseQuerySpans(observationRegistry, true));
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT * FROM x");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                // drain
            }
        }

        assertThat(stopped).singleElement().satisfies(context -> {
            assertThat(context.getName()).isEqualTo(ObservationNames.DB_QUERY);
            assertThat(context
                    .getHighCardinalityKeyValue(ObservationNames.KEY_DB_ROWS)
                    .getValue()).isEqualTo("2");
            assertThat(context.getHighCardinalityKeyValue(
                    ObservationNames.KEY_DB_STATEMENT).getValue())
                    .isEqualTo("SELECT * FROM x");
            assertThat(context
                    .getLowCardinalityKeyValue(ObservationNames.KEY_ROUTE)
                    .getValue()).isEqualTo(MeterNames.ROUTE_UNKNOWN);
        });
    }

    @Test
    void generatedKeys_areNotCounted() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet query = mock(ResultSet.class);
        ResultSet keys = mock(ResultSet.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(query);
        when(statement.getGeneratedKeys()).thenReturn(keys);
        when(query.next()).thenReturn(true, true, true, false);
        when(keys.next()).thenReturn(true, false);

        DataSource ds = new RowCountingDataSource(delegate,
                new DatabaseFetchMetrics(registry), null);
        try (Connection c = ds.getConnection();
                Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("select 1")) {
                while (rs.next()) {
                    // drain
                }
            }
            // Auto-generated keys are an auxiliary result set, not a query
            // result, so iterating them must not affect the fetch distribution.
            try (ResultSet rs = s.getGeneratedKeys()) {
                while (rs.next()) {
                    // drain
                }
            }
        }

        DistributionSummary summary = registry.find(MeterNames.DB_FETCH_ROWS)
                .summary();
        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.totalAmount()).isEqualTo(3.0);
    }

    @Test
    void executeThenGetResultSet_recordsRowsAndEmitsSpan() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(true);
        when(statement.getResultSet()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);

        ObservationRegistry observationRegistry = ObservationRegistry.create();
        List<Observation.Context> stopped = new ArrayList<>();
        observationRegistry.observationConfig()
                .observationHandler(new ObservationHandler<>() {
                    @Override
                    public boolean supportsContext(Observation.Context c) {
                        return true;
                    }

                    @Override
                    public void onStop(Observation.Context c) {
                        stopped.add(c);
                    }
                });

        DataSource ds = new RowCountingDataSource(delegate,
                new DatabaseFetchMetrics(registry),
                new DatabaseQuerySpans(observationRegistry, true));
        try (Connection c = ds.getConnection();
                Statement s = c.createStatement()) {
            s.execute("SELECT * FROM x");
            try (ResultSet rs = s.getResultSet()) {
                while (rs.next()) {
                    // drain
                }
            }
        }

        assertThat(
                registry.find(MeterNames.DB_FETCH_ROWS).summary().totalAmount())
                .isEqualTo(2.0);
        assertThat(stopped).singleElement().satisfies(context -> assertThat(
                context.getHighCardinalityKeyValue(ObservationNames.KEY_DB_ROWS)
                        .getValue())
                .isEqualTo("2"));
    }

    @Test
    void reExecuting_doesNotOrphanPreviousSpan() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement prepared = mock(PreparedStatement.class);
        ResultSet first = mock(ResultSet.class);
        ResultSet second = mock(ResultSet.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(prepared);
        when(prepared.executeQuery()).thenReturn(first, second);
        when(second.next()).thenReturn(true, false);

        ObservationRegistry observationRegistry = ObservationRegistry.create();
        List<Observation.Context> stopped = new ArrayList<>();
        observationRegistry.observationConfig()
                .observationHandler(new ObservationHandler<>() {
                    @Override
                    public boolean supportsContext(Observation.Context c) {
                        return true;
                    }

                    @Override
                    public void onStop(Observation.Context c) {
                        stopped.add(c);
                    }
                });

        DataSource ds = new RowCountingDataSource(delegate,
                new DatabaseFetchMetrics(registry),
                new DatabaseQuerySpans(observationRegistry, true));
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("select 1")) {
            // Re-execute before the first result set is closed; the driver
            // implicitly closes it, so the first span must still be stopped.
            ps.executeQuery();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // drain
                }
            }
        }

        assertThat(stopped).hasSize(2);
    }
}
