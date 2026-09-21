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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.apache.ibatis.extension.metadata.ColumnInfo;
import org.apache.ibatis.extension.metadata.ColumnMetadata;
import org.apache.ibatis.extension.metadata.TableInfo;
import org.apache.ibatis.extension.metadata.TableMetadata;
import org.apache.ibatis.extension.metadata.TableType;

/**
 * Strategy to parse simple POJOs using convention-over-configuration. Converts CamelCase to snake_case.
 */
public class SimplePojoTableInfoParser extends AbstractTableInfoParser {

  @Override
  public TableInfo parse(Class<?> clazz) {
    TableMetadata tableMetadata = new TableMetadata();

    tableMetadata.setTableName(getTableName(clazz));
    tableMetadata.setTableType(TableType.TABLE.name());

    TableInfo tableInfo = new TableInfo(tableMetadata);
    tableInfo.setEntityClass(clazz);
    tableInfo.setEntityName(clazz.getSimpleName());
    tableInfo.setColumns(parseColumns(clazz));

    return tableInfo;
  }

  private List<ColumnInfo> parseColumns(Class<?> clazz) {
    List<ColumnInfo> columns = new ArrayList<>();
    int ordinal = 1;

    for (Field field : clazz.getDeclaredFields()) {
      if (isIgnoredField(field)) {
        continue;
      }

      ColumnMetadata meta = new ColumnMetadata();

      String columnName = getColumnName(field);

      // Naive assumption: "id" field is PK
      if ("id".equalsIgnoreCase(field.getName())) {
        meta.setPrimaryKey(true);
        meta.setColumnKey("PRI");
        // Assume integer IDs are auto-increment in POJO mode
        if (Number.class.isAssignableFrom(field.getType()) || field.getType() == int.class
            || field.getType() == long.class) {
          meta.setAutoIncrement("YES");
        }
      } else {
        meta.setAutoIncrement("NO");
      }

      meta.setColumnName(columnName);
      meta.setTypeName(resolveSqlTypeName(field.getType()));
      meta.setDataType(resolveSqlType(field.getType()));
      meta.markNullable(!field.getType().isPrimitive());
      meta.setColumnSize(255); // Default assumption
      meta.setOrdinalPosition(ordinal++);

      ColumnInfo colInfo = new ColumnInfo(meta);
      colInfo.setFieldName(field.getName());
      colInfo.setJavaType(field.getType());
      columns.add(colInfo);
    }
    return columns;
  }
}
