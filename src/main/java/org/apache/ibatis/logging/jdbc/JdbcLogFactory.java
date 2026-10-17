package org.apache.ibatis.logging.jdbc;

import java.sql.Connection;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;

public interface JdbcLogFactory {

  boolean isLogEnabled(MappedStatement stmt);

  /**
   * Enables SQL logging using the legacy factory contract.
   *
   * @param connection
   *          the JDBC connection
   * @param stmt
   *          the mapped statement
   * @param queryStack
   *          the current query stack depth
   *
   * @return the logging connection
   */
  @Deprecated
  default Connection enableSqlLog(Connection connection, MappedStatement stmt, int queryStack) {
    throw new UnsupportedOperationException("The JDBC log factory does not implement the legacy contract");
  }

  /**
   * Enables SQL logging with the bound SQL metadata.
   *
   * @param connection
   *          the JDBC connection
   * @param stmt
   *          the mapped statement
   * @param boundSql
   *          the bound SQL and parameter mappings
   * @param queryStack
   *          the current query stack depth
   *
   * @return the logging connection
   */
  default Connection enableSqlLog(Connection connection, MappedStatement stmt, BoundSql boundSql, int queryStack) {
    return enableSqlLog(connection, stmt, queryStack);
  }
}
