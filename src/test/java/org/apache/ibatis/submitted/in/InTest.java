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
package org.apache.ibatis.submitted.in;

import static org.apache.ibatis.builder.annotation.MapperAnnotationBuilder.getQualifiedStatementId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.ibatis.BaseDataTest;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class InTest {

  private static SqlSessionFactory sqlSessionFactory;

  @BeforeAll
  static void setUp() throws Exception {
    // create a SqlSessionFactory
    sqlSessionFactory = SqlSessionFactoryBuilder.buildFromResource("org/apache/ibatis/submitted/in/mybatis-config.xml");

    // populate in-memory database
    BaseDataTest.runScript(sqlSessionFactory, "org/apache/ibatis/submitted/in/CreateDB.sql");
  }

  @Test
  void shouldExecuteInTag() {
    try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
      Mapper mapper = sqlSession.getMapper(Mapper.class);

      // 1. Test standard <in> tag with body
      User user1 = new User();
      user1.setId(2);
      user1.setName("User2");
      User user2 = new User();
      user2.setId(3);
      user2.setName("User3");
      List<User> users = new ArrayList<>();
      users.add(user1);
      users.add(user2);
      int countNormal = mapper.countByUserListWithInTag(users);
      Assertions.assertEquals(2, countNormal);

      // 2. Test shorthand <in> tag without body
      List<Integer> ids = Arrays.asList(2, 3, 4);
      int countShorthand = mapper.countByUserListWithInTagShorthand(ids);
      Assertions.assertEquals(3, countShorthand);
    }
  }

  @Test
  void shouldExecuteWithColumnInTag() {
    try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
      Mapper mapper = sqlSession.getMapper(Mapper.class);

      // 1. Test standard <in> tag with body
      User user1 = new User();
      user1.setId(2);
      user1.setName("User2");
      User user2 = new User();
      user2.setId(3);
      user2.setName("User3");
      List<User> users = new ArrayList<>();
      users.add(user1);
      users.add(user2);
      int countNormal = mapper.countByUserListWithInTag(users);
      Assertions.assertEquals(2, countNormal);

      // 2. Test shorthand <in> tag without body
      List<Integer> ids = Arrays.asList(2, 3, 4);
      int countShorthand = mapper.countByUserListWithColumnInTag(ids);
      Assertions.assertEquals(3, countShorthand);
    }
  }

  @Test
  void shouldUseEqualsForSingleItemInTagWhenConfigured() {
    Configuration configuration = sqlSessionFactory.getConfiguration();
    configuration.setUseEqualsWhenInTagHasSingleItem(true);
    configuration.setShrinkWhitespacesInSql(true);
    try {
      MappedStatement bodyStatement = configuration
          .getMappedStatement(getQualifiedStatementId(Mapper::countByUserListWithInTag));
      User user = new User();
      user.setId(2);
      BoundSql bodySql = bodyStatement.getBoundSql(Collections.singletonMap("list", Collections.singleton(user)));
      Assertions.assertTrue(bodySql.getSql().contains("id = ?"));

      MappedStatement shorthandStatement = configuration
          .getMappedStatement(getQualifiedStatementId(Mapper::countByUserListWithInTagShorthand));
      BoundSql shorthandSql = shorthandStatement.getBoundSql(Collections.singletonMap("ids", Collections.singleton(2)));
      Assertions.assertTrue(shorthandSql.getSql().contains("id = ?"));

      BoundSql nullSql = shorthandStatement
          .getBoundSql(Collections.singletonMap("ids", Collections.singletonList(null)));
      Assertions.assertTrue(nullSql.getSql().contains("id = ?"));

      MappedStatement columnStatement = configuration
          .getMappedStatement(getQualifiedStatementId(Mapper::countByUserListWithColumnInTag));
      BoundSql columnSql = columnStatement.getBoundSql(Collections.singletonMap("ids", Collections.singleton(2)));
      Assertions.assertTrue(columnSql.getSql().contains("u.id = ?"));

      BoundSql multipleSql = shorthandStatement.getBoundSql(Collections.singletonMap("ids", Arrays.asList(2, 3)));
      Assertions.assertTrue(multipleSql.getSql().contains("id IN ( ? , ? )"));

      BoundSql emptySql = shorthandStatement.getBoundSql(Collections.singletonMap("ids", Collections.emptyList()));
      Assertions.assertFalse(emptySql.getSql().contains("IN"));
    } finally {
      configuration.setUseEqualsWhenInTagHasSingleItem(false);
    }
  }

}
