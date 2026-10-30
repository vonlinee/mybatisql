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

import java.util.List;
import java.util.Map;

import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.scripting.DynamicContext;
import org.apache.ibatis.scripting.SqlBuildContext;
import org.apache.ibatis.scripting.SqlNode;
import org.apache.ibatis.scripting.expression.ExpressionEvaluator;
import org.jetbrains.annotations.NotNull;

/**
 * @author Clinton Begin
 */
public class ForEachSqlNode extends XmlSqlNode {

  private final String collectionExpression;
  private final Boolean nullable;
  private final SqlNode contents;
  private final String open;
  private final String close;
  private final String separator;
  private final String item;
  private final String index;

  /**
   * @since 3.5.9
   */
  public ForEachSqlNode(SqlNode contents, String collectionExpression, Boolean nullable, String index, String item,
      String open, String close, String separator) {
    this.collectionExpression = collectionExpression;
    this.nullable = nullable;
    this.contents = contents;
    this.open = open;
    this.close = close;
    this.separator = separator;
    this.index = index;
    this.item = item;
  }

  protected Iterable<?> getIterable(SqlBuildContext context) {
    final Map<String, Object> bindings = context.getBindings();
    final boolean nullableOnForEach = this.nullable == null ? context.getConfiguration().isNullableOnForEach()
        : this.nullable;
    final ExpressionEvaluator evaluator = context.getExpressionEvaluator();
    return evaluator.evaluateIterable(collectionExpression, bindings, nullableOnForEach);
  }

  @Override
  public boolean apply(@NotNull SqlBuildContext context) {
    final Iterable<?> iterable = this.getIterable(context);
    if (iterable == null || !iterable.iterator().hasNext()) {
      return true;
    }
    return applyIterableItems(context, iterable);
  }

  protected final boolean applyIterableItems(SqlBuildContext context, Iterable<?> iterable) {
    boolean first = true;
    applyOpen(context);
    int i = 0;
    for (Object o : iterable) {
      first = applyElement(context, i, o, first);
      i++;
    }
    applyClose(context);
    return true;
  }

  protected final boolean applyElement(SqlBuildContext context, int index, Object o, boolean first) {
    PrefixedContext scopedContext = new PrefixedContext(context, first || separator == null ? "" : separator);
    // Issue #709
    applyIndexedItem(scopedContext, index, o);
    contents.apply(scopedContext);
    if (first) {
      first = !scopedContext.isPrefixApplied();
    }
    return first;
  }

  protected void applyIndexedItem(SqlBuildContext context, int index, Object o) {
    if (o instanceof Map.Entry) {
      @SuppressWarnings("unchecked")
      Map.Entry<Object, Object> mapEntry = (Map.Entry<Object, Object>) o;
      applyIndex(context, mapEntry.getKey());
      applyItem(context, mapEntry.getValue());
    } else {
      applyIndex(context, index);
      applyItem(context, o);
    }
  }

  protected void applyIndex(SqlBuildContext context, Object o) {
    if (index != null) {
      context.bind(index, o);
    }
  }

  protected void applyItem(SqlBuildContext context, Object o) {
    if (item != null) {
      context.bind(item, o);
    }
  }

  protected void applyOpen(SqlBuildContext context) {
    if (open != null) {
      context.appendSql(open);
    }
  }

  protected void applyClose(SqlBuildContext context) {
    if (close != null) {
      context.appendSql(close);
    }
  }

  protected static class PrefixedContext extends DynamicContext {
    private final SqlBuildContext delegate;
    private final String prefix;
    private boolean prefixApplied;

    public PrefixedContext(SqlBuildContext delegate, String prefix) {
      super(delegate);
      this.delegate = delegate;
      this.prefix = prefix;
      this.prefixApplied = false;
      this.bindings.putAll(delegate.getBindings());
    }

    public boolean isPrefixApplied() {
      return prefixApplied;
    }

    @Override
    public void appendSql(String sql) {
      if (!prefixApplied && sql != null && !sql.trim().isEmpty()) {
        delegate.appendSql(prefix);
        prefixApplied = true;
      }
      delegate.appendSql(sql);
    }

    @Override
    public String getSql() {
      return delegate.getSql();
    }

    @Override
    public List<ParameterMapping> getParameterMappings() {
      return delegate.getParameterMappings();
    }
  }

  public String getCollectionExpression() {
    return collectionExpression;
  }

  public Boolean getNullable() {
    return nullable;
  }

  public SqlNode getContents() {
    return contents;
  }

  public String getOpen() {
    return open;
  }

  public String getClose() {
    return close;
  }

  public String getSeparator() {
    return separator;
  }

  public String getItem() {
    return item;
  }

  public String getIndex() {
    return index;
  }

}
