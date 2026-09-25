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

import com.vaadin.observability.micrometer.DatabaseActivity;
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
                new DatabaseQuerySpans(observationRegistry, null, true, 100));
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
                new DatabaseQuerySpans(observationRegistry, null, true, 100));
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
                new DatabaseQuerySpans(observationRegistry, null, true, 100));
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

    @Test
    void everyQuery_countsTowardsTheThreadTally() throws Exception {
        // What the data query insights read: the metric says how big each
        // result set was, the tally says how many result sets a single data
        // load needed. An N+1 only shows in the second.
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement prepared = mock(PreparedStatement.class);
        ResultSet page = mock(ResultSet.class);
        ResultSet perRow = mock(ResultSet.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(prepared);
        when(prepared.executeQuery()).thenReturn(page, perRow);
        when(page.next()).thenReturn(true, true, false);
        when(perRow.next()).thenReturn(true, false);

        DatabaseActivity.instrumented();
        DatabaseActivity.Work start = DatabaseActivity.current();
        DataSource ds = new RowCountingDataSource(delegate,
                new DatabaseFetchMetrics(registry), null);
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("select 1")) {
            for (int query = 0; query < 2; query++) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        // drain
                    }
                }
            }
        }
        DatabaseActivity.Work work = DatabaseActivity.since(start);

        assertThat(work.queries()).isEqualTo(2);
        assertThat(work.rows()).isEqualTo(3);
    }

    @Test
    void aQueryWhoseRowsAreNeverRead_stillCounts() throws Exception {
        // The row count is best-effort -- an unclosed result set reports none
        // -- but the query itself happened, and the count of queries is the
        // number the N+1 finding rests on.
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);

        DatabaseActivity.instrumented();
        DatabaseActivity.Work start = DatabaseActivity.current();
        DataSource ds = new RowCountingDataSource(delegate,
                new DatabaseFetchMetrics(registry), null);
        try (Connection c = ds.getConnection();
                Statement s = c.createStatement()) {
            s.executeQuery("select 1");
        }

        assertThat(DatabaseActivity.since(start).queries()).isEqualTo(1);
    }

    @Test
    void anExecuteThatProducesNoResultSet_isNotCounted() throws Exception {
        // execute() returns false for a write; counting it would inflate the
        // queries a fetch reports and could suggest an N+1 that is not there.
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute("UPDATE x SET y = 1")).thenReturn(false);
        when(statement.execute("SELECT * FROM x")).thenReturn(true);

        DatabaseActivity.instrumented();
        DatabaseActivity.Work start = DatabaseActivity.current();
        DataSource ds = new RowCountingDataSource(delegate,
                new DatabaseFetchMetrics(registry), null);
        try (Connection c = ds.getConnection();
                Statement s = c.createStatement()) {
            s.execute("UPDATE x SET y = 1");
            s.execute("SELECT * FROM x");
        }

        assertThat(DatabaseActivity.since(start).queries()).isEqualTo(1);
    }
}
