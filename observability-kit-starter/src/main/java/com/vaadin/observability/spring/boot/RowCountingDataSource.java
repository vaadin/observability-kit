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
 * Connections, statements and result sets are wrapped with JDK dynamic proxies
 * that delegate every call straight through, intercepting only the few methods
 * that produce a {@link ResultSet} (to wrap it) and the result set's own
 * {@code next()}/{@code close()} (to count rows and emit the metric). No
 * third-party JDBC-proxy library is involved.
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
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                new ResultSetHandler(resultSet, span));
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
        /** Span for the most recent query, kept for the close() leak guard. */
        private DatabaseQuerySpans.QuerySpan pending;

        StatementHandler(Statement statement, String preparedSql) {
            this.statement = statement;
            this.preparedSql = preparedSql;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
                throws Throwable {
            String name = method.getName();
            // Start the span before executing so it brackets the DB round trip;
            // it is stopped when the returned result set closes.
            DatabaseQuerySpans.QuerySpan span = spans != null
                    && name.equals("executeQuery") ? spans.start(sqlFor(args))
                            : null;
            Object result;
            try {
                result = RowCountingDataSource.invoke(statement, method, args);
            } catch (Throwable t) {
                if (span != null) {
                    span.stop(-1);
                }
                throw t;
            }
            if (result instanceof ResultSet resultSet) {
                if (span != null) {
                    pending = span;
                }
                return wrapResultSet(resultSet, span);
            }
            if (span != null) {
                // executeQuery returned no result set (unusual) — don't leak.
                span.stop(-1);
            }
            if (name.equals("close") && pending != null) {
                // Result set never closed: close the span so it isn't orphaned.
                pending.stop(-1);
                pending = null;
            }
            return result;
        }

        private String sqlFor(Object[] args) {
            if (args != null && args.length > 0
                    && args[0] instanceof String s) {
                return s;
            }
            return preparedSql;
        }
    }

    private final class ResultSetHandler implements InvocationHandler {
        private final ResultSet resultSet;
        private final DatabaseQuerySpans.QuerySpan span;
        private long rows;
        private boolean recorded;

        ResultSetHandler(ResultSet resultSet,
                DatabaseQuerySpans.QuerySpan span) {
            this.resultSet = resultSet;
            this.span = span;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
                throws Throwable {
            Object result = RowCountingDataSource.invoke(resultSet, method,
                    args);
            switch (method.getName()) {
            case "next" -> {
                if (Boolean.TRUE.equals(result)) {
                    rows++;
                }
            }
            case "close" -> record();
            default -> {
                // pass-through
            }
            }
            return result;
        }

        private void record() {
            if (!recorded) {
                recorded = true;
                metrics.recordFetch(rows);
                if (span != null) {
                    span.stop(rows);
                }
            }
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
