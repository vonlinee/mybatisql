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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.extension.SqlUtils;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.*;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Node;

/**
 * @author Clinton Begin
 */
public class XMLScriptBuilder {

  private final Map<String, NodeHandler> nodeHandlerMap = new HashMap<>();
  private static final Map<String, SqlNode> emptyNodeCache = new ConcurrentHashMap<>();

  public XMLScriptBuilder() {
    initNodeHandlerMap();
  }

  private void initNodeHandlerMap() {
    nodeHandlerMap.put("trim", new TrimHandler());
    nodeHandlerMap.put("where", new WhereHandler());
    nodeHandlerMap.put("set", new SetHandler());
    nodeHandlerMap.put("foreach", new ForEachHandler());
    nodeHandlerMap.put("if", new IfHandler());
    nodeHandlerMap.put("choose", new ChooseHandler());
    nodeHandlerMap.put("when", new IfHandler());
    nodeHandlerMap.put("otherwise", new OtherwiseHandler());
    nodeHandlerMap.put("bind", new BindHandler());
    nodeHandlerMap.put("in", new InHandler());
    nodeHandlerMap.put("pagination", new PaginationHandler());
    nodeHandlerMap.put("columns", new ColumnsHandler());
  }

  public SqlNode parseSqlNode(Configuration configuration, XNode context) {
    return parseDynamicTags(configuration, context);
  }

  protected SqlNode parseDynamicTags(Configuration configuration, XNode node) {
    final List<SqlNode> contents = new ArrayList<>();
    List<XNode> childNodes = node.getChildNodes();
    for (XNode child : childNodes) {
      if (child.isTextualNode()) {
        String data = child.getStringBody("");
        if (data.trim().isEmpty()) {
          contents.add(emptyNodeCache.computeIfAbsent(data, WhitespaceSqlNode::new));
          continue;
        }
        TextSqlNode textSqlNode = new TextSqlNode(data);
        if (textSqlNode.isDynamic()) {
          contents.add(textSqlNode);
        } else {
          contents.add(new StaticTextSqlNode(data));
        }
      } else if (child.isElementNode()) { // issue #628
        String nodeName = child.getNode().getNodeName();
        NodeHandler handler = nodeHandlerMap.get(nodeName);
        if (handler == null) {
          throw new BuilderException("Unknown element <" + nodeName + "> in SQL statement.");
        }
        contents.add(handler.handleNode(configuration, child));
      }
    }
    return new MixedSqlNode(contents);
  }

  private interface NodeHandler {
    SqlNode handleNode(Configuration configuration, XNode nodeToHandle);
  }

  private static class BindHandler implements NodeHandler {

    @Override
    public SqlNode handleNode(Configuration configuration, XNode nodeToHandle) {
      final String name = nodeToHandle.getStringAttribute("name");
      final String expression = nodeToHandle.getStringAttribute("value");
      return new VarDeclSqlNode(name, expression);
    }
  }

  private static class PaginationHandler implements NodeHandler {

    @Override
    public SqlNode handleNode(Configuration configuration, XNode nodeToHandle) {
      return new PaginationSqlNode();
    }
  }

  private class TrimHandler implements NodeHandler {

    @Override
    public SqlNode handleNode(Configuration configuration, XNode nodeToHandle) {
      SqlNode sqlNode = parseDynamicTags(configuration, nodeToHandle);
      String prefix = nodeToHandle.getStringAttribute("prefix");
      String prefixOverrides = nodeToHandle.getStringAttribute("prefixOverrides");
      String suffix = nodeToHandle.getStringAttribute("suffix");
      String suffixOverrides = nodeToHandle.getStringAttribute("suffixOverrides");
      return new TrimSqlNode(sqlNode, prefix, prefixOverrides, suffix, suffixOverrides);
    }
  }

  private class WhereHandler implements NodeHandler {

    @Override
    public SqlNode handleNode(Configuration configuration, XNode nodeToHandle) {
      SqlNode sqlNode = parseDynamicTags(configuration, nodeToHandle);
      return new WhereSqlNode(sqlNode);
    }
  }

  private class SetHandler implements NodeHandler {

    @Override
    public SqlNode handleNode(Configuration configuration, XNode nodeToHandle) {
      SqlNode sqlNode = parseDynamicTags(configuration, nodeToHandle);
      return new SetSqlNode(sqlNode);
    }
  }

  private class ForEachHandler implements NodeHandler {

    @Override
    public SqlNode handleNode(Configuration configuration, XNode nodeToHandle) {
      SqlNode sqlNode = parseDynamicTags(configuration, nodeToHandle);
      String collection = nodeToHandle.getStringAttribute("collection");
      Boolean nullable = nodeToHandle.getBooleanAttribute("nullable");
      String item = nodeToHandle.getStringAttribute("item");
      String index = nodeToHandle.getStringAttribute("index");
      String open = nodeToHandle.getStringAttribute("open");
      String close = nodeToHandle.getStringAttribute("close");
      String separator = nodeToHandle.getStringAttribute("separator");
      return new ForEachSqlNode(sqlNode, collection, nullable, index, item, open, close, separator);
    }
  }

  private class IfHandler implements NodeHandler {

    @Override
    public SqlNode handleNode(Configuration configuration, XNode nodeToHandle) {
      SqlNode sqlNode = parseDynamicTags(configuration, nodeToHandle);
      String testExpression = nodeToHandle.getStringAttribute("test");
      testExpression = configuration.getExpressionEvaluator().postProcessExpression(testExpression);
      return new IfSqlNode(sqlNode, testExpression);
    }
  }

  /**
   * Node handler for the &lt;in&gt; element.
   * <p>
   * Acts as syntactic sugar for &lt;foreach&gt; tailored for generating SQL `IN ( ... )` clauses. <br>
   * Usage Example 1 (Standard):
   *
   * <pre>
   *   &lt;select id="selectUsersByIds" resultType="User"&gt;
   *     SELECT * FROM users
   *     WHERE id &lt;in collection="list" item="item"&gt;#{item.id}&lt;/in&gt;
   *   &lt;/select&gt;
   * </pre>
   *
   * Usage Example 2 (Implicit Item and Body support):
   * <p>
   * If the tag has no children, it evaluates to `#{item}` automatically. If `item` is omitted, it defaults to `"item"`.
   * This is highly useful for collections of primitives:
   *
   * <pre>
   *   &lt;select id="selectUsersByIds" resultType="User"&gt;
   *     SELECT * FROM users
   *     WHERE id &lt;in collection="ids" /&gt;
   *   &lt;/select&gt;
   * </pre>
   *
   * The above evaluates precisely to `IN (#{item_0}, #{item_1}, ...)` at runtime.
   */
  private class InHandler implements NodeHandler {

    @Override
    public SqlNode handleNode(Configuration configuration, XNode nodeToHandle) {
      SqlNode sqlNode = parseDynamicTags(configuration, nodeToHandle);
      String collection = nodeToHandle.getStringAttribute("collection");
      Boolean nullable = nodeToHandle.getBooleanAttribute("nullable");
      String item = nodeToHandle.getStringAttribute("item", "item");
      String index = nodeToHandle.getStringAttribute("index");
      String column = nodeToHandle.getStringAttribute("column");
      SqlNode contents = sqlNode;
      if (!nodeToHandle.hasElementChildNodes() && nodeToHandle.isBodyBlank()) {
        contents = new TextSqlNode("#{" + item + "}");
      }
      return new InSqlNode(contents, collection, nullable, index, item, column);
    }
  }

  private static class ColumnsHandler implements NodeHandler {

    @Override
    public SqlNode handleNode(Configuration configuration, XNode nodeToHandle) {
      if (!isWithinStatement(nodeToHandle, "select")) {
        throw new BuilderException("<columns> element is only supported in <select> statements.");
      }
      StringBuilder columns = new StringBuilder();
      for (XNode child : nodeToHandle.getChildNodes()) {
        if (!child.isTextualNode()) {
          throw new BuilderException("<columns> element can only contain text nodes.");
        }
        columns.append(child.getStringBody(""));
      }
      return new StaticTextSqlNode(
          SqlUtils.qualifyColumns(nodeToHandle.getStringAttribute("table"), columns.toString()));
    }
  }

  private class OtherwiseHandler implements NodeHandler {

    @Override
    public SqlNode handleNode(Configuration configuration, XNode nodeToHandle) {
      return parseDynamicTags(configuration, nodeToHandle);
    }
  }

  private class ChooseHandler implements NodeHandler {

    @Override
    public SqlNode handleNode(Configuration configuration, XNode nodeToHandle) {
      List<SqlNode> whenSqlNodes = new ArrayList<>();
      List<SqlNode> otherwiseSqlNodes = new ArrayList<>();
      handleWhenOtherwiseNodes(configuration, nodeToHandle, whenSqlNodes, otherwiseSqlNodes);
      SqlNode defaultSqlNode = getDefaultSqlNode(otherwiseSqlNodes);
      return new ChooseSqlNode(whenSqlNodes, defaultSqlNode);
    }

    private void handleWhenOtherwiseNodes(Configuration configuration, XNode chooseSqlNode, List<SqlNode> ifSqlNodes,
        List<SqlNode> defaultSqlNodes) {
      List<XNode> children = chooseSqlNode.getChildElements();
      for (XNode child : children) {
        String nodeName = child.getNode().getNodeName();
        NodeHandler handler = nodeHandlerMap.get(nodeName);
        if (handler instanceof IfHandler) {
          ifSqlNodes.add(handler.handleNode(configuration, child));
        } else if (handler instanceof OtherwiseHandler) {
          defaultSqlNodes.add(handler.handleNode(configuration, child));
        } else {
          throw new BuilderException("Unknown element <" + nodeName + "> in SQL statement.");
        }
      }
    }

    private SqlNode getDefaultSqlNode(List<SqlNode> defaultSqlNodes) {
      SqlNode defaultSqlNode = null;
      if (defaultSqlNodes.size() == 1) {
        defaultSqlNode = defaultSqlNodes.get(0);
      } else if (defaultSqlNodes.size() > 1) {
        throw new BuilderException("Too many default (otherwise) elements in choose statement.");
      }
      return defaultSqlNode;
    }
  }

  /**
   * Determines whether the node is contained in a {@code <select> | <update> | <insert> | <delete>} statement.
   *
   * @param node
   *          the node to inspect
   * @param sqlStatementType
   *          <select> | <update> | <insert> | <delete>
   *
   * @return {@code true} if the node is contained in specified statement type
   */
  private static boolean isWithinStatement(XNode node, String sqlStatementType) {
    XNode current = node;
    while (current != null) {
      if (sqlStatementType.equals(current.getName())) {
        return true;
      }
      current = current.getParent();
      // skip XML script in annotation like @Select
      if (isScriptNode(current)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isScriptNode(XNode node) {
    if (node == null) {
      return false;
    }
    return node.getNode().getNodeType() == Node.ELEMENT_NODE && "script".equals(node.getNode().getNodeName());
  }

}
