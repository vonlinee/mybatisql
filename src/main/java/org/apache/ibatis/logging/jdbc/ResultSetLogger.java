/*
 *    Copyright 2009-2026 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.logging.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.HashSet;
import java.util.Set;

import org.apache.ibatis.reflection.ExceptionUtils;

/**
 * ResultSet proxy to add logging.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public final class ResultSetLogger extends BaseJdbcLogger implements InvocationHandler {

  private static final Set<Integer> BLOB_TYPES = new HashSet<>();
  private boolean first = true;
  private int rows;
  private final ResultSet rs;
  private final Set<Integer> blobColumns = new HashSet<>();

  static {
    BLOB_TYPES.add(Types.BINARY);
    BLOB_TYPES.add(Types.BLOB);
    BLOB_TYPES.add(Types.CLOB);
    BLOB_TYPES.add(Types.LONGNVARCHAR);
    BLOB_TYPES.add(Types.LONGVARBINARY);
    BLOB_TYPES.add(Types.LONGVARCHAR);
    BLOB_TYPES.add(Types.NCLOB);
    BLOB_TYPES.add(Types.VARBINARY);
  }

  private ResultSetLogger(ResultSet rs, JdbcLog log) {
    super(log);
    this.rs = rs;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] params) throws Throwable {
    try {
      if (Object.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, params);
      }
      Object o = method.invoke(rs, params);
      if ("next".equals(method.getName())) {
        if ((Boolean) o) {
          rows++;
          if (isTraceEnabled()) {
            ResultSetMetaData rsmd = rs.getMetaData();
            final int columnCount = rsmd.getColumnCount();
            if (first) {
              first = false;
              for (int i = 1; i <= columnCount; i++) {
                if (BLOB_TYPES.contains(rsmd.getColumnType(i))) {
                  blobColumns.add(i);
                }
              }
              log.printColumnHeaders(rsmd, columnCount);
            }
            log.printColumnValues(rs, columnNames, columnValues, blobColumns, columnCount);
          }
        } else {
          log.debug("     Total: " + rows, false);
        }
      }
      clearColumnInfo();
      return o;
    } catch (Throwable t) {
      throw ExceptionUtils.unwrapThrowable(t);
    }
  }

  /**
   * Creates a logging version of a ResultSet.
   *
   * @param rs
   *          the ResultSet to proxy
   * @param log
   *          the statement log
   *
   * @return the ResultSet with logging
   */
  public static ResultSet newInstance(ResultSet rs, JdbcLog log) {
    InvocationHandler handler = new ResultSetLogger(rs, log);
    ClassLoader cl = ResultSet.class.getClassLoader();
    return (ResultSet) Proxy.newProxyInstance(cl, new Class[] { ResultSet.class }, handler);
  }

  /**
   * Get the wrapped result set.
   *
   * @return the resultSet
   */
  public ResultSet getRs() {
    return rs;
  }

}
