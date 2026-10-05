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

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.vaadin.observability.micrometer.DatabaseActivity;

/**
 * A {@link DataSource} wrapper that counts the rows read from every
 * {@link ResultSet} and reports each count to {@link DatabaseFetchMetrics}.
 * <p>
 * Connections and statements are wrapped with JDK dynamic proxies that delegate
 * every call straight through, intercepting only the methods that produce a
 * query {@link ResultSet} (to wrap it). The result set itself is wrapped with a
 * hand-written {@link CountingResultSet} delegate — not a proxy — so the hot
 * path ({@code next()} and per-column getters) makes direct calls without
 * reflection, counting rows and emitting the metric on {@code close()}. No
 * third-party JDBC-proxy library is involved.
 * <p>
 * Only the result sets of {@code executeQuery()} and {@code execute()} (fetched
 * via {@code getResultSet()}) are counted. Auxiliary result sets such as
 * {@code getGeneratedKeys()} are left untouched so their tiny row counts do not
 * skew the fetch distribution.
 * <p>
 * Counting is best-effort: a result set whose {@code close()} is never called
 * (an unusual driver/usage) is simply not recorded, and row scrolling via
 * {@code absolute()}/{@code relative()} is not counted. This keeps the hot path
 * to a single increment per row.
 */
final class RowCountingDataSource implements DataSource {

    private final DataSource delegate;
    private final DatabaseFetchMetrics metrics;
    private final DatabaseQuerySpans spans;

    RowCountingDataSource(DataSource delegate, DatabaseFetchMetrics metrics,
            DatabaseQuerySpans spans) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.spans = spans;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrapConnection(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password)
            throws SQLException {
        return wrapConnection(delegate.getConnection(username, password));
    }

    private Connection wrapConnection(Connection connection) {
        if (connection == null) {
            return null;
        }
        ConnectionHandler handler = new ConnectionHandler(connection);
        Connection proxy = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class }, handler);
        handler.proxy = proxy;
        return proxy;
    }

    private Statement wrapStatement(Statement statement, String sql,
            ConnectionHandler owner) {
        if (statement == null) {
            return null;
        }
        Class<?> iface = statement instanceof CallableStatement
                ? CallableStatement.class
                : statement instanceof PreparedStatement
                        ? PreparedStatement.class
                        : Statement.class;
        return (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(), new Class<?>[] { iface },
                new StatementHandler(statement, sql, owner));
    }

    /**
     * Invokes {@code method} on {@code target}, unwrapping reflection errors.
     */
    private static Object invoke(Object target, Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private final class ConnectionHandler implements InvocationHandler {
        private final Connection connection;
        /**
         * The proxy wrapping this handler, handed back by
         * {@code Statement.getConnection()} so that closing the connection
         * reached through a statement still stops its spans.
         */
        private Connection proxy;
        /**
         * Statements of this connection with an in-flight span. Closing a
         * connection implicitly closes its statements and result sets inside
         * the driver (or pool), where our proxies never see it, so their spans
         * are stopped from here. A statement leaves the set as soon as its span
         * stops, so the set is bounded by the queries in flight rather than by
         * every statement the connection ever created.
         */
        private final Set<StatementHandler> pendingStatements = ConcurrentHashMap
                .newKeySet();

        ConnectionHandler(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
                throws Throwable {
            String name = method.getName();
            if (name.equals("close") || name.equals("abort")) {
                try {
                    return RowCountingDataSource.invoke(connection, method,
                            args);
                } finally {
                    stopOpenStatements();
                }
            }
            Object result = RowCountingDataSource.invoke(connection, method,
                    args);
            if (result instanceof Statement statement) {
                // prepareStatement/prepareCall carry the SQL up front; plain
                // createStatement does not (its SQL arrives at executeQuery).
                String sql = name.startsWith("prepare") && args != null
                        && args.length > 0 && args[0] instanceof String s ? s
                                : null;
                return wrapStatement(statement, sql, this);
            }
            return result;
        }

        private void stopOpenStatements() {
            for (StatementHandler statement : pendingStatements) {
                statement.stopPending();
            }
            pendingStatements.clear();
        }
    }

    private final class StatementHandler implements InvocationHandler {
        private final Statement statement;
        /** SQL from prepareStatement/prepareCall, null for plain statements. */
        private final String preparedSql;
        private final ConnectionHandler owner;
        /**
         * Span for the in-flight query, not yet stopped. Stopped when its
         * result set closes, when the statement is re-executed (the driver
         * implicitly closes the prior result set), or by the close() leak
         * guards of this statement and of its connection.
         */
        private DatabaseQuerySpans.QuerySpan pending;

        StatementHandler(Statement statement, String preparedSql,
                ConnectionHandler owner) {
            this.statement = statement;
            this.preparedSql = preparedSql;
            this.owner = owner;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
                throws Throwable {
            String name = method.getName();
            // executeQuery returns the result set directly; execute returns a
            // boolean and the result set is fetched later via getResultSet.
            boolean producesQuery = name.equals("executeQuery")
                    || name.equals("execute");
            // A new execution implicitly closes any result set still open on
            // this statement, so stop the previous span first — its result-set
            // close never reaches our proxy, and it would otherwise be
            // orphaned.
            if (producesQuery) {
                stopPending();
            }
            // Start the span before executing so it brackets the DB round trip.
            DatabaseQuerySpans.QuerySpan span = spans != null && producesQuery
                    ? spans.start(sqlFor(args))
                    : null;
            if (span != null) {
                pending = span;
                owner.pendingStatements.add(this);
            }
            Object result;
            try {
                result = RowCountingDataSource.invoke(statement, method, args);
                if (name.equals("executeQuery")
                        || Boolean.TRUE.equals(result)) {
                    // Counted here rather than in the result set, so that the
                    // tally holds every query a data load issued even when its
                    // rows are never read or its result set never closed. Only
                    // executions that produce a result set count: an update,
                    // whether via executeUpdate or a false-returning execute,
                    // is not what a data load is answered from.
                    DatabaseActivity.queryExecuted();
                }
            } catch (Throwable t) {
                stopPending();
                throw t;
            }
            switch (name) {
            case "executeQuery" -> {
                if (result instanceof ResultSet resultSet) {
                    return wrapResultSet(resultSet, (Statement) proxy);
                }
                // No result set (unusual) — don't leak the span.
                stopPending();
            }
            case "execute" -> {
                // A false return means an update, not a query: close the span
                // now, since no result set will be fetched.
                if (Boolean.FALSE.equals(result)) {
                    stopPending();
                }
            }
            case "getResultSet" -> {
                if (result instanceof ResultSet resultSet) {
                    return wrapResultSet(resultSet, (Statement) proxy);
                }
            }
            case "getConnection" -> {
                // Hand back the proxy, so that closing the connection reached
                // through this statement still stops the pending spans.
                return owner.proxy;
            }
            case "close" -> {
                // Result set never closed: stop the span so it isn't orphaned.
                stopPending();
            }
            default -> {
                // getGeneratedKeys() and other ResultSet-returning methods are
                // intentionally not wrapped — their rows are not query results
                // and would skew the fetch distribution.
            }
            }
            return result;
        }

        private ResultSet wrapResultSet(ResultSet resultSet, Statement proxy) {
            DatabaseQuerySpans.QuerySpan span = pending;
            return new CountingResultSet(resultSet, span, metrics, proxy,
                    () -> released(span));
        }

        private void stopPending() {
            if (pending != null) {
                pending.stop(-1);
                pending = null;
                owner.pendingStatements.remove(this);
            }
        }

        /**
         * Called when the result set of {@code span} closes, which stops the
         * span itself: the connection no longer needs to track this statement.
         */
        private void released(DatabaseQuerySpans.QuerySpan span) {
            if (span != null && pending == span) {
                pending = null;
                owner.pendingStatements.remove(this);
            }
        }

        private String sqlFor(Object[] args) {
            if (args != null && args.length > 0
                    && args[0] instanceof String s) {
                return s;
            }
            return preparedSql;
        }
    }

    // --- Plain delegation for the rest of the DataSource contract -----------

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
