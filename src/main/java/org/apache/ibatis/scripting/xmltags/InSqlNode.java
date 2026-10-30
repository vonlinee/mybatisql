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
package org.apache.ibatis.scripting.xmltags;

import java.util.Iterator;

import org.apache.ibatis.scripting.SqlBuildContext;
import org.apache.ibatis.scripting.SqlNode;
import org.jetbrains.annotations.NotNull;

/**
 * SQL node for the {@code <in>} element.
 */
public class InSqlNode extends ForEachSqlNode {

  private final String column;

  public InSqlNode(SqlNode contents, String collectionExpression, Boolean nullable, String index, String item,
      String column) {
    super(contents, collectionExpression, nullable, index, item, " IN (", ")", ",");
    this.column = column;
  }

  @Override
  public boolean apply(@NotNull SqlBuildContext context) {
    Iterable<?> iterable = super.getIterable(context);
    Iterator<?> iterator = iterable.iterator();
    if (!iterator.hasNext()) {
      return true;
    }
    Object firstItem = iterator.next();
    if (!iterator.hasNext()) {
      // If there is only one element, we should use the shortcut [column = #{item}]
      applyColumn(context);
      context.appendSql(" = ");
      applyElement(context, 0, firstItem, true);
      return true;
    }
    applyColumn(context);
    return super.applyIterableItems(context, iterable);
  }

  private void applyColumn(SqlBuildContext context) {
    if (column != null) {
      context.appendSql(column);
    }
  }
}
