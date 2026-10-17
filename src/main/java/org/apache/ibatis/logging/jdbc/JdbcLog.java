package org.apache.ibatis.logging.jdbc;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

import org.apache.ibatis.internal.util.ObjectUtils;
import org.apache.ibatis.jdbc.RuntimeSqlException;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.ParameterMapping;

public class JdbcLog implements Log {

  protected final Log statementLog;
  protected final List<ParameterMapping> parameterMappings;
  protected final int queryStack;

  public JdbcLog(Log log, int queryStack) {
    this(log, Collections.emptyList(), queryStack);
  }

  public JdbcLog(Log log, List<ParameterMapping> parameterMappings, int queryStack) {
    this.statementLog = log;
    this.parameterMappings = parameterMappings;
    if (queryStack == 0) {
      this.queryStack = 1;
    } else {
      this.queryStack = queryStack;
    }
  }

  @Override
  public boolean isDebugEnabled() {
    return statementLog.isDebugEnabled();
  }

  @Override
  public boolean isTraceEnabled() {
    return statementLog.isTraceEnabled();
  }

  @Override
  public void error(String s, Throwable e) {
    statementLog.error(s, e);
  }

  @Override
  public void error(String s) {
    statementLog.error(s);
  }

  @Override
  public void debug(String s) {
    statementLog.debug(s);
  }

  @Override
  public void trace(String s) {
    statementLog.trace(s);
  }

  @Override
  public void warn(String s) {
    statementLog.warn(s);
  }

  protected void debug(String text, boolean input) {
    if (statementLog.isDebugEnabled()) {
      statementLog.debug(prefix(input) + text);
    }
  }

  protected void trace(String text, boolean input) {
    if (statementLog.isTraceEnabled()) {
      statementLog.trace(prefix(input) + text);
    }
  }

  private String prefix(boolean isInput) {
    char[] buffer = new char[queryStack * 2 + 2];
    Arrays.fill(buffer, '=');
    buffer[queryStack * 2 + 1] = ' ';
    if (isInput) {
      buffer[queryStack * 2] = '>';
    } else {
      buffer[0] = '<';
    }
    return new String(buffer);
  }

  protected String getParameterValueAsString(List<Object> columnValues) {
    List<Object> typeList = new ArrayList<>(columnValues.size());
    for (Object value : columnValues) {
      if (value == null) {
        typeList.add("null");
      } else {
        typeList.add(objectValueString(value) + "(" + value.getClass().getSimpleName() + ")");
      }
    }
    final String parameters = typeList.toString();
    return parameters.substring(1, parameters.length() - 1);
  }

  protected String getParameterValueAsString(List<Object> columnNames, List<Object> columnValues) {
    return getParameterValueAsString(columnValues);
  }

  protected String objectValueString(Object value) {
    if (value instanceof Array) {
      try {
        return ObjectUtils.toString(((Array) value).getArray());
      } catch (SQLException e) {
        // Intentionally fall through to return value.toString()
      }
    }
    return value.toString();
  }

  protected void printColumnValues(ResultSet rs, List<Object> columnNames, List<Object> columnValues,
      Set<Integer> blobColumns, int columnCount) {
    StringJoiner row = new StringJoiner(", ", "       Row: ", "");
    for (int i = 1; i <= columnCount; i++) {
      try {
        row.add(getColumnValue(rs, i, blobColumns));
      } catch (SQLException e) {
        // generally can't call getString() on a BLOB column
        row.add("<<Cannot Display>>");
      }
    }
    this.trace(row.toString(), false);
  }

  protected String getColumnValue(ResultSet rs, int columnIndex, Set<Integer> blobColumns) throws SQLException {
    if (blobColumns.contains(columnIndex)) {
      return "<<BLOB>>";
    }
    return rs.getString(columnIndex);
  }

  protected void printColumnHeaders(ResultSetMetaData rsmd, int columnCount) {
    StringJoiner row = new StringJoiner(", ", "   Columns: ", "");
    for (int i = 1; i <= columnCount; i++) {
      try {
        row.add(rsmd.getColumnLabel(i));
      } catch (SQLException e) {
        throw new RuntimeSqlException(e);
      }
    }
    this.trace(row.toString(), false);
  }
}
