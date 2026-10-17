package org.apache.ibatis.logging.jdbc;

import java.sql.Connection;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;

public class DefaultJdbcLogFactory implements JdbcLogFactory {
  @Override
  public boolean isLogEnabled(MappedStatement stmt) {
    Log statementLog = stmt.getStatementLog();
    return statementLog.isDebugEnabled();
  }

  @Override
  @Deprecated
  public Connection enableSqlLog(Connection connection, MappedStatement stmt, int queryStack) {
    return ConnectionLogger.newInstance(connection, new JdbcLog(stmt.getStatementLog(), queryStack));
  }

  @Override
  public Connection enableSqlLog(Connection connection, MappedStatement stmt, BoundSql boundSql, int queryStack) {
    return enableSqlLog(connection, stmt, queryStack);
  }
}
