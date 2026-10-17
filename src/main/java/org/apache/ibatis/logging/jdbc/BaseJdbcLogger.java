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

import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.ibatis.builder.SqlSourceBuilder;

/**
 * Base class for proxies to do logging.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public abstract class BaseJdbcLogger {

  protected static final Set<String> SET_METHODS;
  protected static final Set<String> EXECUTE_METHODS = new HashSet<>();

  private final Map<Object, Object> columnMap = new HashMap<>();

  protected final List<Object> columnNames = new ArrayList<>();
  protected final List<Object> columnValues = new ArrayList<>();

  protected final JdbcLog log;

  /*
   * Default constructor
   */
  public BaseJdbcLogger(JdbcLog log) {
    this.log = log;
  }

  static {
    SET_METHODS = Arrays.stream(PreparedStatement.class.getDeclaredMethods())
        .filter(method -> method.getName().startsWith("set")).filter(method -> method.getParameterCount() > 1)
        .map(Method::getName).collect(Collectors.toSet());

    EXECUTE_METHODS.add("execute");
    EXECUTE_METHODS.add("executeUpdate");
    EXECUTE_METHODS.add("executeQuery");
    EXECUTE_METHODS.add("addBatch");
  }

  protected void setColumn(Object key, Object value) {
    columnMap.put(key, value);
    columnNames.add(key);
    columnValues.add(value);
  }

  protected Object getColumn(Object key) {
    return columnMap.get(key);
  }

  protected String getParameterValueString() {
    return log.getParameterValueAsString(columnNames, columnValues);
  }

  /**
   * Formats a single parameter value for compatibility with existing logger subclasses.
   *
   * @param value
   *          the value to format
   *
   * @return the formatted value
   */
  @Deprecated
  protected String objectValueString(Object value) {
    return log.objectValueString(value);
  }

  protected String getColumnString() {
    return columnNames.toString();
  }

  protected void clearColumnInfo() {
    columnMap.clear();
    columnNames.clear();
    columnValues.clear();
  }

  protected String removeExtraWhitespace(String original) {
    return SqlSourceBuilder.removeExtraWhitespaces(original);
  }

  protected boolean isDebugEnabled() {
    return log.isDebugEnabled();
  }

  protected boolean isTraceEnabled() {
    return log.isTraceEnabled();
  }
}
