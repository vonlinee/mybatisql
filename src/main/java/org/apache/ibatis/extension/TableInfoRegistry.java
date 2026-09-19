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
package org.apache.ibatis.extension;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.persistence.Entity;
import javax.persistence.Table;

import org.apache.ibatis.extension.metadata.TableInfo;
import org.apache.ibatis.internal.util.ClassMatcher;
import org.apache.ibatis.io.ResolverUtil;
import org.jetbrains.annotations.Nullable;

/**
 * A central registry that manages the lifecycle and retrieval of {@link TableInfo} metadata.
 * <p>
 * This class acts as the bridge between raw Java classes and the library's internal metadata representation. It
 * handles:
 * <ol>
 * <li><b>Parsing:</b> Converting Entity classes into {@link TableInfo} via a {@link TableInfoParser}.</li>
 * <li><b>Caching:</b> Storing metadata for high-performance O(1) retrieval.</li>
 * <li><b>Lookup:</b> retrieving metadata by Class or Table Name.</li>
 * </ol>
 */
public class TableInfoRegistry {

  public static ClassMatcher JPA_CLASS_MATCHER = type -> !type.isInterface()
      && (type.isAnnotationPresent(Entity.class) || type.isAnnotationPresent(Table.class));

  /**
   * Primary cache: Maps the Java Entity Class to its parsed metadata.
   */
  private final Map<Class<?>, TableInfo> classToTableInfoMap = new ConcurrentHashMap<>();

  /**
   * Secondary cache: Maps the Database Table Name (normalized to lowercase) to the metadata.
   */
  private final Map<String, TableInfo> tableNameToTableInfoMap = new ConcurrentHashMap<>();

  /**
   * The strategy used to parse classes into metadata.
   */
  private final TableInfoParser parser;

  /**
   * Creates a registry with the default smart parser (CompositeTableInfoParser).
   */
  public TableInfoRegistry() {
    this(new CompositeTableInfoParser());
  }

  /**
   * Creates a registry with a custom parser strategy.
   *
   * @param parser
   *          the parser strategy to use
   */
  public TableInfoRegistry(TableInfoParser parser) {
    this.parser = parser;
  }

  /**
   * Registers one or more classes.
   * <p>
   * This parses the classes immediately and caches the results. This method is idempotent; registering the same class
   * twice has no side effects.
   *
   * @param classes
   *          the classes to register
   */
  public void register(Class<?>... classes) {
    for (Class<?> clazz : classes) {
      registerClass(clazz);
    }
  }

  public void registerPackage(String packageName, ClassMatcher matcher) {
    ResolverUtil.findClassesInPackage(packageName, null, matcher).forEach(this::registerClass);
  }

  /**
   * Registers a collection of classes.
   *
   * @param classes
   *          the collection of classes
   */
  public void register(Collection<Class<?>> classes) {
    classes.forEach(this::registerClass);
  }

  /**
   * Internal registration logic.
   *
   * @param clazz
   *          the class to parse and register
   */
  private void registerClass(Class<?> clazz) {
    if (clazz == null || clazz.isInterface()) {
      return;
    }

    if (classToTableInfoMap.containsKey(clazz)) {
      return;
    }

    try {
      // 1. Parse the class
      TableInfo tableInfo = parser.parse(clazz);

      // 2. Validate basic integrity
      String tableName = tableInfo.getTableName();
      if (tableName == null || tableName.trim().isEmpty()) {
        throw new IllegalStateException("Failed to determine table name for class: " + clazz.getName());
      }

      // 3. Store in primary cache
      classToTableInfoMap.put(clazz, tableInfo);

      // 4. Store in secondary cache (handle collisions logic: keep the first one registered)
      String normalizedTableName = tableName.toLowerCase();
      if (!tableNameToTableInfoMap.containsKey(normalizedTableName)) {
        tableNameToTableInfoMap.put(normalizedTableName, tableInfo);
      }

    } catch (Exception e) {
      throw new RuntimeException("Entity registration failed for " + clazz.getName(), e);
    }
  }

  /**
   * Retrieves metadata for a specific entity class.
   *
   * @param clazz
   *          the entity class
   *
   * @return the TableInfo, or Optional.empty() if not registered
   */
  @Nullable
  public TableInfo getTableInfo(Class<?> clazz) {
    return classToTableInfoMap.get(clazz);
  }

  /**
   * Retrieves metadata for a specific table name. This lookup is case-insensitive.
   *
   * @param tableName
   *          the database table name
   *
   * @return the TableInfo, or Optional.empty() if not found
   */
  @Nullable
  public TableInfo getTableInfo(String tableName) {
    if (tableName == null || tableName.isEmpty()) {
      return null;
    }
    return tableNameToTableInfoMap.get(tableName.toLowerCase());
  }

  /**
   * Checks if a class is registered.
   *
   * @param clazz
   *          the class to check
   *
   * @return true if registered
   */
  public boolean hasTableInfo(Class<?> clazz) {
    return classToTableInfoMap.containsKey(clazz);
  }

  /**
   * Returns all registered TableInfos.
   *
   * @return a collection of all metadata
   */
  public Collection<TableInfo> getAllTableInfos() {
    return classToTableInfoMap.values();
  }
}
