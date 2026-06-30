/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.boot;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Map;

/**
 * A concrete {@link ResultSet} delegate that counts rows as they are read and
 * reports the total to {@link DatabaseFetchMetrics} (and, when present, the
 * query span) when the result set closes.
 * <p>
 * Unlike a JDK dynamic proxy, every method here forwards with a direct call, so
 * the hot path -- {@code next()} and the per-column getters invoked once per
 * row per column -- carries no per-call reflection overhead. Only
 * {@code next()} and {@code close()} add behaviour; everything else is a
 * straight pass-through.
 * <p>
 * Counting is best-effort: a result set whose {@code close()} is never called
 * is simply not recorded, and row scrolling via
 * {@code absolute()}/{@code relative()} is not counted. This keeps the hot path
 * to a single increment per row.
 */
final class CountingResultSet implements ResultSet {

    private final ResultSet delegate;
    private final DatabaseQuerySpans.QuerySpan span;
    private final DatabaseFetchMetrics metrics;
    private long rows;
    private boolean recorded;

    CountingResultSet(ResultSet delegate, DatabaseQuerySpans.QuerySpan span,
            DatabaseFetchMetrics metrics) {
        this.delegate = delegate;
        this.span = span;
        this.metrics = metrics;
    }

    @Override
    public boolean next() throws SQLException {
        boolean hasNext = delegate.next();
        if (hasNext) {
            rows++;
        }
        return hasNext;
    }

    @Override
    public void close() throws SQLException {
        try {
            delegate.close();
        } finally {
            record();
        }
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

    // --- Wrapper -----------------------------------------------------------

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return iface.isInstance(this) ? iface.cast(this)
                : delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }

    // --- Straight delegation for the rest of the ResultSet contract --------

    @Override
    public boolean wasNull() throws SQLException {
        return delegate.wasNull();
    }

    @Override
    public String getString(int a0) throws SQLException {
        return delegate.getString(a0);
    }

    @Override
    public boolean getBoolean(int a0) throws SQLException {
        return delegate.getBoolean(a0);
    }

    @Override
    public byte getByte(int a0) throws SQLException {
        return delegate.getByte(a0);
    }

    @Override
    public short getShort(int a0) throws SQLException {
        return delegate.getShort(a0);
    }

    @Override
    public int getInt(int a0) throws SQLException {
        return delegate.getInt(a0);
    }

    @Override
    public long getLong(int a0) throws SQLException {
        return delegate.getLong(a0);
    }

    @Override
    public float getFloat(int a0) throws SQLException {
        return delegate.getFloat(a0);
    }

    @Override
    public double getDouble(int a0) throws SQLException {
        return delegate.getDouble(a0);
    }

    @Override
    public BigDecimal getBigDecimal(int a0, int a1) throws SQLException {
        return delegate.getBigDecimal(a0, a1);
    }

    @Override
    public byte[] getBytes(int a0) throws SQLException {
        return delegate.getBytes(a0);
    }

    @Override
    public Date getDate(int a0) throws SQLException {
        return delegate.getDate(a0);
    }

    @Override
    public Time getTime(int a0) throws SQLException {
        return delegate.getTime(a0);
    }

    @Override
    public Timestamp getTimestamp(int a0) throws SQLException {
        return delegate.getTimestamp(a0);
    }

    @Override
    public InputStream getAsciiStream(int a0) throws SQLException {
        return delegate.getAsciiStream(a0);
    }

    @Override
    public InputStream getUnicodeStream(int a0) throws SQLException {
        return delegate.getUnicodeStream(a0);
    }

    @Override
    public InputStream getBinaryStream(int a0) throws SQLException {
        return delegate.getBinaryStream(a0);
    }

    @Override
    public String getString(String a0) throws SQLException {
        return delegate.getString(a0);
    }

    @Override
    public boolean getBoolean(String a0) throws SQLException {
        return delegate.getBoolean(a0);
    }

    @Override
    public byte getByte(String a0) throws SQLException {
        return delegate.getByte(a0);
    }

    @Override
    public short getShort(String a0) throws SQLException {
        return delegate.getShort(a0);
    }

    @Override
    public int getInt(String a0) throws SQLException {
        return delegate.getInt(a0);
    }

    @Override
    public long getLong(String a0) throws SQLException {
        return delegate.getLong(a0);
    }

    @Override
    public float getFloat(String a0) throws SQLException {
        return delegate.getFloat(a0);
    }

    @Override
    public double getDouble(String a0) throws SQLException {
        return delegate.getDouble(a0);
    }

    @Override
    public BigDecimal getBigDecimal(String a0, int a1) throws SQLException {
        return delegate.getBigDecimal(a0, a1);
    }

    @Override
    public byte[] getBytes(String a0) throws SQLException {
        return delegate.getBytes(a0);
    }

    @Override
    public Date getDate(String a0) throws SQLException {
        return delegate.getDate(a0);
    }

    @Override
    public Time getTime(String a0) throws SQLException {
        return delegate.getTime(a0);
    }

    @Override
    public Timestamp getTimestamp(String a0) throws SQLException {
        return delegate.getTimestamp(a0);
    }

    @Override
    public InputStream getAsciiStream(String a0) throws SQLException {
        return delegate.getAsciiStream(a0);
    }

    @Override
    public InputStream getUnicodeStream(String a0) throws SQLException {
        return delegate.getUnicodeStream(a0);
    }

    @Override
    public InputStream getBinaryStream(String a0) throws SQLException {
        return delegate.getBinaryStream(a0);
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return delegate.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        delegate.clearWarnings();
    }

    @Override
    public String getCursorName() throws SQLException {
        return delegate.getCursorName();
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return delegate.getMetaData();
    }

    @Override
    public Object getObject(int a0) throws SQLException {
        return delegate.getObject(a0);
    }

    @Override
    public Object getObject(String a0) throws SQLException {
        return delegate.getObject(a0);
    }

    @Override
    public int findColumn(String a0) throws SQLException {
        return delegate.findColumn(a0);
    }

    @Override
    public Reader getCharacterStream(int a0) throws SQLException {
        return delegate.getCharacterStream(a0);
    }

    @Override
    public Reader getCharacterStream(String a0) throws SQLException {
        return delegate.getCharacterStream(a0);
    }

    @Override
    public BigDecimal getBigDecimal(int a0) throws SQLException {
        return delegate.getBigDecimal(a0);
    }

    @Override
    public BigDecimal getBigDecimal(String a0) throws SQLException {
        return delegate.getBigDecimal(a0);
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        return delegate.isBeforeFirst();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        return delegate.isAfterLast();
    }

    @Override
    public boolean isFirst() throws SQLException {
        return delegate.isFirst();
    }

    @Override
    public boolean isLast() throws SQLException {
        return delegate.isLast();
    }

    @Override
    public void beforeFirst() throws SQLException {
        delegate.beforeFirst();
    }

    @Override
    public void afterLast() throws SQLException {
        delegate.afterLast();
    }

    @Override
    public boolean first() throws SQLException {
        return delegate.first();
    }

    @Override
    public boolean last() throws SQLException {
        return delegate.last();
    }

    @Override
    public int getRow() throws SQLException {
        return delegate.getRow();
    }

    @Override
    public boolean absolute(int a0) throws SQLException {
        return delegate.absolute(a0);
    }

    @Override
    public boolean relative(int a0) throws SQLException {
        return delegate.relative(a0);
    }

    @Override
    public boolean previous() throws SQLException {
        return delegate.previous();
    }

    @Override
    public void setFetchDirection(int a0) throws SQLException {
        delegate.setFetchDirection(a0);
    }

    @Override
    public int getFetchDirection() throws SQLException {
        return delegate.getFetchDirection();
    }

    @Override
    public void setFetchSize(int a0) throws SQLException {
        delegate.setFetchSize(a0);
    }

    @Override
    public int getFetchSize() throws SQLException {
        return delegate.getFetchSize();
    }

    @Override
    public int getType() throws SQLException {
        return delegate.getType();
    }

    @Override
    public int getConcurrency() throws SQLException {
        return delegate.getConcurrency();
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        return delegate.rowUpdated();
    }

    @Override
    public boolean rowInserted() throws SQLException {
        return delegate.rowInserted();
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        return delegate.rowDeleted();
    }

    @Override
    public void updateNull(int a0) throws SQLException {
        delegate.updateNull(a0);
    }

    @Override
    public void updateBoolean(int a0, boolean a1) throws SQLException {
        delegate.updateBoolean(a0, a1);
    }

    @Override
    public void updateByte(int a0, byte a1) throws SQLException {
        delegate.updateByte(a0, a1);
    }

    @Override
    public void updateShort(int a0, short a1) throws SQLException {
        delegate.updateShort(a0, a1);
    }

    @Override
    public void updateInt(int a0, int a1) throws SQLException {
        delegate.updateInt(a0, a1);
    }

    @Override
    public void updateLong(int a0, long a1) throws SQLException {
        delegate.updateLong(a0, a1);
    }

    @Override
    public void updateFloat(int a0, float a1) throws SQLException {
        delegate.updateFloat(a0, a1);
    }

    @Override
    public void updateDouble(int a0, double a1) throws SQLException {
        delegate.updateDouble(a0, a1);
    }

    @Override
    public void updateBigDecimal(int a0, BigDecimal a1) throws SQLException {
        delegate.updateBigDecimal(a0, a1);
    }

    @Override
    public void updateString(int a0, String a1) throws SQLException {
        delegate.updateString(a0, a1);
    }

    @Override
    public void updateBytes(int a0, byte[] a1) throws SQLException {
        delegate.updateBytes(a0, a1);
    }

    @Override
    public void updateDate(int a0, Date a1) throws SQLException {
        delegate.updateDate(a0, a1);
    }

    @Override
    public void updateTime(int a0, Time a1) throws SQLException {
        delegate.updateTime(a0, a1);
    }

    @Override
    public void updateTimestamp(int a0, Timestamp a1) throws SQLException {
        delegate.updateTimestamp(a0, a1);
    }

    @Override
    public void updateAsciiStream(int a0, InputStream a1, int a2)
            throws SQLException {
        delegate.updateAsciiStream(a0, a1, a2);
    }

    @Override
    public void updateBinaryStream(int a0, InputStream a1, int a2)
            throws SQLException {
        delegate.updateBinaryStream(a0, a1, a2);
    }

    @Override
    public void updateCharacterStream(int a0, Reader a1, int a2)
            throws SQLException {
        delegate.updateCharacterStream(a0, a1, a2);
    }

    @Override
    public void updateObject(int a0, Object a1, int a2) throws SQLException {
        delegate.updateObject(a0, a1, a2);
    }

    @Override
    public void updateObject(int a0, Object a1) throws SQLException {
        delegate.updateObject(a0, a1);
    }

    @Override
    public void updateNull(String a0) throws SQLException {
        delegate.updateNull(a0);
    }

    @Override
    public void updateBoolean(String a0, boolean a1) throws SQLException {
        delegate.updateBoolean(a0, a1);
    }

    @Override
    public void updateByte(String a0, byte a1) throws SQLException {
        delegate.updateByte(a0, a1);
    }

    @Override
    public void updateShort(String a0, short a1) throws SQLException {
        delegate.updateShort(a0, a1);
    }

    @Override
    public void updateInt(String a0, int a1) throws SQLException {
        delegate.updateInt(a0, a1);
    }

    @Override
    public void updateLong(String a0, long a1) throws SQLException {
        delegate.updateLong(a0, a1);
    }

    @Override
    public void updateFloat(String a0, float a1) throws SQLException {
        delegate.updateFloat(a0, a1);
    }

    @Override
    public void updateDouble(String a0, double a1) throws SQLException {
        delegate.updateDouble(a0, a1);
    }

    @Override
    public void updateBigDecimal(String a0, BigDecimal a1) throws SQLException {
        delegate.updateBigDecimal(a0, a1);
    }

    @Override
    public void updateString(String a0, String a1) throws SQLException {
        delegate.updateString(a0, a1);
    }

    @Override
    public void updateBytes(String a0, byte[] a1) throws SQLException {
        delegate.updateBytes(a0, a1);
    }

    @Override
    public void updateDate(String a0, Date a1) throws SQLException {
        delegate.updateDate(a0, a1);
    }

    @Override
    public void updateTime(String a0, Time a1) throws SQLException {
        delegate.updateTime(a0, a1);
    }

    @Override
    public void updateTimestamp(String a0, Timestamp a1) throws SQLException {
        delegate.updateTimestamp(a0, a1);
    }

    @Override
    public void updateAsciiStream(String a0, InputStream a1, int a2)
            throws SQLException {
        delegate.updateAsciiStream(a0, a1, a2);
    }

    @Override
    public void updateBinaryStream(String a0, InputStream a1, int a2)
            throws SQLException {
        delegate.updateBinaryStream(a0, a1, a2);
    }

    @Override
    public void updateCharacterStream(String a0, Reader a1, int a2)
            throws SQLException {
        delegate.updateCharacterStream(a0, a1, a2);
    }

    @Override
    public void updateObject(String a0, Object a1, int a2) throws SQLException {
        delegate.updateObject(a0, a1, a2);
    }

    @Override
    public void updateObject(String a0, Object a1) throws SQLException {
        delegate.updateObject(a0, a1);
    }

    @Override
    public void insertRow() throws SQLException {
        delegate.insertRow();
    }

    @Override
    public void updateRow() throws SQLException {
        delegate.updateRow();
    }

    @Override
    public void deleteRow() throws SQLException {
        delegate.deleteRow();
    }

    @Override
    public void refreshRow() throws SQLException {
        delegate.refreshRow();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        delegate.cancelRowUpdates();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        delegate.moveToInsertRow();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        delegate.moveToCurrentRow();
    }

    @Override
    public Statement getStatement() throws SQLException {
        return delegate.getStatement();
    }

    @Override
    public Object getObject(int a0, Map<String, Class<?>> a1)
            throws SQLException {
        return delegate.getObject(a0, a1);
    }

    @Override
    public Ref getRef(int a0) throws SQLException {
        return delegate.getRef(a0);
    }

    @Override
    public Blob getBlob(int a0) throws SQLException {
        return delegate.getBlob(a0);
    }

    @Override
    public Clob getClob(int a0) throws SQLException {
        return delegate.getClob(a0);
    }

    @Override
    public Array getArray(int a0) throws SQLException {
        return delegate.getArray(a0);
    }

    @Override
    public Object getObject(String a0, Map<String, Class<?>> a1)
            throws SQLException {
        return delegate.getObject(a0, a1);
    }

    @Override
    public Ref getRef(String a0) throws SQLException {
        return delegate.getRef(a0);
    }

    @Override
    public Blob getBlob(String a0) throws SQLException {
        return delegate.getBlob(a0);
    }

    @Override
    public Clob getClob(String a0) throws SQLException {
        return delegate.getClob(a0);
    }

    @Override
    public Array getArray(String a0) throws SQLException {
        return delegate.getArray(a0);
    }

    @Override
    public Date getDate(int a0, Calendar a1) throws SQLException {
        return delegate.getDate(a0, a1);
    }

    @Override
    public Date getDate(String a0, Calendar a1) throws SQLException {
        return delegate.getDate(a0, a1);
    }

    @Override
    public Time getTime(int a0, Calendar a1) throws SQLException {
        return delegate.getTime(a0, a1);
    }

    @Override
    public Time getTime(String a0, Calendar a1) throws SQLException {
        return delegate.getTime(a0, a1);
    }

    @Override
    public Timestamp getTimestamp(int a0, Calendar a1) throws SQLException {
        return delegate.getTimestamp(a0, a1);
    }

    @Override
    public Timestamp getTimestamp(String a0, Calendar a1) throws SQLException {
        return delegate.getTimestamp(a0, a1);
    }

    @Override
    public URL getURL(int a0) throws SQLException {
        return delegate.getURL(a0);
    }

    @Override
    public URL getURL(String a0) throws SQLException {
        return delegate.getURL(a0);
    }

    @Override
    public void updateRef(int a0, Ref a1) throws SQLException {
        delegate.updateRef(a0, a1);
    }

    @Override
    public void updateRef(String a0, Ref a1) throws SQLException {
        delegate.updateRef(a0, a1);
    }

    @Override
    public void updateBlob(int a0, Blob a1) throws SQLException {
        delegate.updateBlob(a0, a1);
    }

    @Override
    public void updateBlob(String a0, Blob a1) throws SQLException {
        delegate.updateBlob(a0, a1);
    }

    @Override
    public void updateClob(int a0, Clob a1) throws SQLException {
        delegate.updateClob(a0, a1);
    }

    @Override
    public void updateClob(String a0, Clob a1) throws SQLException {
        delegate.updateClob(a0, a1);
    }

    @Override
    public void updateArray(int a0, Array a1) throws SQLException {
        delegate.updateArray(a0, a1);
    }

    @Override
    public void updateArray(String a0, Array a1) throws SQLException {
        delegate.updateArray(a0, a1);
    }

    @Override
    public RowId getRowId(int a0) throws SQLException {
        return delegate.getRowId(a0);
    }

    @Override
    public RowId getRowId(String a0) throws SQLException {
        return delegate.getRowId(a0);
    }

    @Override
    public void updateRowId(int a0, RowId a1) throws SQLException {
        delegate.updateRowId(a0, a1);
    }

    @Override
    public void updateRowId(String a0, RowId a1) throws SQLException {
        delegate.updateRowId(a0, a1);
    }

    @Override
    public int getHoldability() throws SQLException {
        return delegate.getHoldability();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return delegate.isClosed();
    }

    @Override
    public void updateNString(int a0, String a1) throws SQLException {
        delegate.updateNString(a0, a1);
    }

    @Override
    public void updateNString(String a0, String a1) throws SQLException {
        delegate.updateNString(a0, a1);
    }

    @Override
    public void updateNClob(int a0, NClob a1) throws SQLException {
        delegate.updateNClob(a0, a1);
    }

    @Override
    public void updateNClob(String a0, NClob a1) throws SQLException {
        delegate.updateNClob(a0, a1);
    }

    @Override
    public NClob getNClob(int a0) throws SQLException {
        return delegate.getNClob(a0);
    }

    @Override
    public NClob getNClob(String a0) throws SQLException {
        return delegate.getNClob(a0);
    }

    @Override
    public SQLXML getSQLXML(int a0) throws SQLException {
        return delegate.getSQLXML(a0);
    }

    @Override
    public SQLXML getSQLXML(String a0) throws SQLException {
        return delegate.getSQLXML(a0);
    }

    @Override
    public void updateSQLXML(int a0, SQLXML a1) throws SQLException {
        delegate.updateSQLXML(a0, a1);
    }

    @Override
    public void updateSQLXML(String a0, SQLXML a1) throws SQLException {
        delegate.updateSQLXML(a0, a1);
    }

    @Override
    public String getNString(int a0) throws SQLException {
        return delegate.getNString(a0);
    }

    @Override
    public String getNString(String a0) throws SQLException {
        return delegate.getNString(a0);
    }

    @Override
    public Reader getNCharacterStream(int a0) throws SQLException {
        return delegate.getNCharacterStream(a0);
    }

    @Override
    public Reader getNCharacterStream(String a0) throws SQLException {
        return delegate.getNCharacterStream(a0);
    }

    @Override
    public void updateNCharacterStream(int a0, Reader a1, long a2)
            throws SQLException {
        delegate.updateNCharacterStream(a0, a1, a2);
    }

    @Override
    public void updateNCharacterStream(String a0, Reader a1, long a2)
            throws SQLException {
        delegate.updateNCharacterStream(a0, a1, a2);
    }

    @Override
    public void updateAsciiStream(int a0, InputStream a1, long a2)
            throws SQLException {
        delegate.updateAsciiStream(a0, a1, a2);
    }

    @Override
    public void updateBinaryStream(int a0, InputStream a1, long a2)
            throws SQLException {
        delegate.updateBinaryStream(a0, a1, a2);
    }

    @Override
    public void updateCharacterStream(int a0, Reader a1, long a2)
            throws SQLException {
        delegate.updateCharacterStream(a0, a1, a2);
    }

    @Override
    public void updateAsciiStream(String a0, InputStream a1, long a2)
            throws SQLException {
        delegate.updateAsciiStream(a0, a1, a2);
    }

    @Override
    public void updateBinaryStream(String a0, InputStream a1, long a2)
            throws SQLException {
        delegate.updateBinaryStream(a0, a1, a2);
    }

    @Override
    public void updateCharacterStream(String a0, Reader a1, long a2)
            throws SQLException {
        delegate.updateCharacterStream(a0, a1, a2);
    }

    @Override
    public void updateBlob(int a0, InputStream a1, long a2)
            throws SQLException {
        delegate.updateBlob(a0, a1, a2);
    }

    @Override
    public void updateBlob(String a0, InputStream a1, long a2)
            throws SQLException {
        delegate.updateBlob(a0, a1, a2);
    }

    @Override
    public void updateClob(int a0, Reader a1, long a2) throws SQLException {
        delegate.updateClob(a0, a1, a2);
    }

    @Override
    public void updateClob(String a0, Reader a1, long a2) throws SQLException {
        delegate.updateClob(a0, a1, a2);
    }

    @Override
    public void updateNClob(int a0, Reader a1, long a2) throws SQLException {
        delegate.updateNClob(a0, a1, a2);
    }

    @Override
    public void updateNClob(String a0, Reader a1, long a2) throws SQLException {
        delegate.updateNClob(a0, a1, a2);
    }

    @Override
    public void updateNCharacterStream(int a0, Reader a1) throws SQLException {
        delegate.updateNCharacterStream(a0, a1);
    }

    @Override
    public void updateNCharacterStream(String a0, Reader a1)
            throws SQLException {
        delegate.updateNCharacterStream(a0, a1);
    }

    @Override
    public void updateAsciiStream(int a0, InputStream a1) throws SQLException {
        delegate.updateAsciiStream(a0, a1);
    }

    @Override
    public void updateBinaryStream(int a0, InputStream a1) throws SQLException {
        delegate.updateBinaryStream(a0, a1);
    }

    @Override
    public void updateCharacterStream(int a0, Reader a1) throws SQLException {
        delegate.updateCharacterStream(a0, a1);
    }

    @Override
    public void updateAsciiStream(String a0, InputStream a1)
            throws SQLException {
        delegate.updateAsciiStream(a0, a1);
    }

    @Override
    public void updateBinaryStream(String a0, InputStream a1)
            throws SQLException {
        delegate.updateBinaryStream(a0, a1);
    }

    @Override
    public void updateCharacterStream(String a0, Reader a1)
            throws SQLException {
        delegate.updateCharacterStream(a0, a1);
    }

    @Override
    public void updateBlob(int a0, InputStream a1) throws SQLException {
        delegate.updateBlob(a0, a1);
    }

    @Override
    public void updateBlob(String a0, InputStream a1) throws SQLException {
        delegate.updateBlob(a0, a1);
    }

    @Override
    public void updateClob(int a0, Reader a1) throws SQLException {
        delegate.updateClob(a0, a1);
    }

    @Override
    public void updateClob(String a0, Reader a1) throws SQLException {
        delegate.updateClob(a0, a1);
    }

    @Override
    public void updateNClob(int a0, Reader a1) throws SQLException {
        delegate.updateNClob(a0, a1);
    }

    @Override
    public void updateNClob(String a0, Reader a1) throws SQLException {
        delegate.updateNClob(a0, a1);
    }

    @Override
    public <T> T getObject(int a0, Class<T> a1) throws SQLException {
        return delegate.getObject(a0, a1);
    }

    @Override
    public <T> T getObject(String a0, Class<T> a1) throws SQLException {
        return delegate.getObject(a0, a1);
    }

    @Override
    public void updateObject(int a0, Object a1, SQLType a2, int a3)
            throws SQLException {
        delegate.updateObject(a0, a1, a2, a3);
    }

    @Override
    public void updateObject(String a0, Object a1, SQLType a2, int a3)
            throws SQLException {
        delegate.updateObject(a0, a1, a2, a3);
    }

    @Override
    public void updateObject(int a0, Object a1, SQLType a2)
            throws SQLException {
        delegate.updateObject(a0, a1, a2);
    }

    @Override
    public void updateObject(String a0, Object a1, SQLType a2)
            throws SQLException {
        delegate.updateObject(a0, a1, a2);
    }
}
