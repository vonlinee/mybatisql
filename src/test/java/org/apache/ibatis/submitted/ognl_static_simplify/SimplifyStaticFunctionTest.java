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
package org.apache.ibatis.submitted.ognl_static_simplify;

import java.util.List;

import org.apache.ibatis.BaseDataTest;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SimplifyStaticFunctionTest {

  private static SqlSessionFactory sqlSessionFactory;

  @BeforeAll
  static void setUp() throws Exception {
    sqlSessionFactory = BaseDataTest.createDefaultHsqlDbSqlSessionFactory("ognlStaticCall");
    sqlSessionFactory.getConfiguration().setLogImpl(StdOutImpl.class);
    sqlSessionFactory.getConfiguration()
        .addXmlMapperResource("org/apache/ibatis/submitted/ognl_static_simplify/Mapper.xml");
    // populate in-memory database
    BaseDataTest.runScriptSql(sqlSessionFactory, """
        drop table users if exists;

        create table users (
          id int,
          name varchar(20)
        );

        insert into users (id, name) values(1, 'User1');
        insert into users (id, name) values(2, 'User2');
        insert into users (id, name) values(3, 'User3');
        insert into users (id, name) values(4, 'User4');
        insert into users (id, name) values(5, 'User5');
        insert into users (id, name) values(6, 'User6');
        """);
  }

  static class User {

    private Integer id;
    private String name;

    public Integer getId() {
      return id;
    }

    public void setId(Integer id) {
      this.id = id;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }

  interface Mapper {

    List<User> selectUsers(@Param("param") User param);

    List<User> selectUsers1(@Param("param") User param);
  }

  @Test
  void shouldExecuteInTag() {
    try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
      Mapper mapper = sqlSession.getMapper(Mapper.class);
      {
        User param = new User();
        param.setName("");
        List<User> users = mapper.selectUsers(param);
        Assertions.assertEquals(6, users.size());
      }

      {
        User param = new User();
        param.setName("User1");
        List<User> users = mapper.selectUsers(param);
        Assertions.assertEquals(1, users.size());

        User user = users.get(0);
        Assertions.assertEquals(1, user.getId());
        Assertions.assertEquals("User1", user.getName());
      }

      // boolean expression in <choose/>, <when/>
      {
        User param = new User();
        param.setId(null);
        List<User> users = mapper.selectUsers1(param);
        Assertions.assertEquals(6, users.size());
      }

      {
        User param = new User();
        param.setId(1);
        List<User> users = mapper.selectUsers1(param);
        Assertions.assertEquals(1, users.size());

        User user = users.get(0);
        Assertions.assertEquals(1, user.getId());
        Assertions.assertEquals("User1", user.getName());
      }
    }
  }
}
