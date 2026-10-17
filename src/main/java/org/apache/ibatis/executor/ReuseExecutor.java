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
package org.apache.ibatis.executor;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * @author Clinton Begin
 */
public class ReuseExecutor extends BaseExecutor {

  private final Map<MappedStatement, Map<String, Statement>> statementMap = new HashMap<>();

  public ReuseExecutor(Configuration configuration, Transaction transaction) {
    super(configuration, transaction);
  }

  @Override
  public int doUpdate(MappedStatement ms, Object parameter) throws SQLException {
    StatementHandler handler = super.newStatementHandlerForUpdate(this, ms, parameter);
    Statement stmt = prepareStatement(handler, ms);
    return handler.update(stmt);
  }

  @Override
  public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler<E> resultHandler,
      BoundSql boundSql) throws SQLException {
    StatementHandler handler = super.newStatementHandlerForQuery(wrapper, ms, parameter, rowBounds, resultHandler,
        boundSql);
    Statement stmt = prepareStatement(handler, ms);
    return handler.query(stmt, resultHandler);
  }

  @Override
  protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql)
      throws SQLException {
    StatementHandler handler = super.newStatementHandlerForQuery(wrapper, ms, parameter, rowBounds, null, boundSql);
    Statement stmt = prepareStatement(handler, ms);
    return handler.queryCursor(stmt);
  }

  @Override
  public List<BatchResult> doFlushStatements(boolean isRollback) {
    for (Map<String, Statement> statements : statementMap.values()) {
      for (Statement stmt : statements.values()) {
        closeStatement(stmt);
      }
    }
    statementMap.clear();
    return Collections.emptyList();
  }

  @Override
  protected Statement prepareStatement(StatementHandler handler, MappedStatement ms) throws SQLException {
    Statement stmt;
    BoundSql boundSql = handler.getBoundSql();
    String sql = boundSql.getSql();
    if (hasStatementFor(ms, sql)) {
      stmt = getStatement(ms, sql);
      applyTransactionTimeout(stmt);
    } else {
      Connection connection = getConnection(ms, boundSql);
      stmt = handler.prepare(connection, transaction.getTimeout());
      putStatement(ms, sql, stmt);
    }
    handler.parameterize(stmt);
    return stmt;
  }

  private boolean hasStatementFor(MappedStatement ms, String sql) {
    try {
      Statement statement = getStatement(ms, sql);
      return statement != null && !statement.getConnection().isClosed();
    } catch (SQLException e) {
      return false;
    }
  }

  private Statement getStatement(MappedStatement ms, String sql) {
    Map<String, Statement> statements = statementMap.get(ms);
    return statements == null ? null : statements.get(sql);
  }

  private void putStatement(MappedStatement ms, String sql, Statement stmt) {
    statementMap.computeIfAbsent(ms, key -> new HashMap<>()).put(sql, stmt);
  }

}
