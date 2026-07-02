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
import java.util.logging.Logger;

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
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                new ConnectionHandler(connection));
    }

    private Statement wrapStatement(Statement statement, String sql) {
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
                new StatementHandler(statement, sql));
    }

    private ResultSet wrapResultSet(ResultSet resultSet,
            DatabaseQuerySpans.QuerySpan span) {
        if (resultSet == null) {
            return null;
        }
        return new CountingResultSet(resultSet, span, metrics);
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

        ConnectionHandler(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
                throws Throwable {
            Object result = RowCountingDataSource.invoke(connection, method,
                    args);
            if (result instanceof Statement statement) {
                // prepareStatement/prepareCall carry the SQL up front; plain
                // createStatement does not (its SQL arrives at executeQuery).
                String sql = method.getName().startsWith("prepare")
                        && args != null && args.length > 0
                        && args[0] instanceof String s ? s : null;
                return wrapStatement(statement, sql);
            }
            return result;
        }
    }

    private final class StatementHandler implements InvocationHandler {
        private final Statement statement;
        /** SQL from prepareStatement/prepareCall, null for plain statements. */
        private final String preparedSql;
        /**
         * Span for the in-flight query, not yet stopped. Stopped when its
         * result set closes, when the statement is re-executed (the driver
         * implicitly closes the prior result set), or by the close() leak
         * guard.
         */
        private DatabaseQuerySpans.QuerySpan pending;

        StatementHandler(Statement statement, String preparedSql) {
            this.statement = statement;
            this.preparedSql = preparedSql;
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
            if (producesQuery && pending != null) {
                pending.stop(-1);
                pending = null;
            }
            // Start the span before executing so it brackets the DB round trip.
            DatabaseQuerySpans.QuerySpan span = spans != null && producesQuery
                    ? spans.start(sqlFor(args))
                    : null;
            if (span != null) {
                pending = span;
            }
            Object result;
            try {
                result = RowCountingDataSource.invoke(statement, method, args);
            } catch (Throwable t) {
                if (span != null) {
                    span.stop(-1);
                    pending = null;
                }
                throw t;
            }
            switch (name) {
            case "executeQuery" -> {
                if (result instanceof ResultSet resultSet) {
                    return wrapResultSet(resultSet, pending);
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
                    return wrapResultSet(resultSet, pending);
                }
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

        private void stopPending() {
            if (pending != null) {
                pending.stop(-1);
                pending = null;
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
