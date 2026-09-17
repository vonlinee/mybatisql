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
package org.apache.ibatis.submitted.map_result;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.apache.ibatis.BaseDataTest;
import org.apache.ibatis.binding.BindingException;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MapResultTest {

  private static SqlSessionFactory sqlSessionFactory;

  @BeforeAll
  static void setUp() throws Exception {
    sqlSessionFactory = BaseDataTest.createDefaultHsqlDbSqlSessionFactory("map_result");
    sqlSessionFactory.getConfiguration()
        .addXmlMapperResource("org/apache/ibatis/submitted/map_result/NoticeMapper.xml");

    BaseDataTest.runScriptSql(sqlSessionFactory, """
        drop table notice if exists;
        create table notice (
          id int,
          status int
        );

        insert into notice (id, status) values (1, 1);
        insert into notice (id, status) values (2, 2);
        insert into notice (id, status) values (3, 1);
        insert into notice (id, status) values (4, 2);
        insert into notice (id, status) values (5, 1);
        insert into notice (id, status) values (6, 2);
        insert into notice (id, status) values (7, 2);
        """);
  }

  @Test
  void shouldSelectMapResultUsingMapKey() {
    try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
      NoticeMapper mapper = sqlSession.getMapper(NoticeMapper.class);

      {
        Map<Integer, Notice> countNoticeMap = mapper.getNoticeMap1();
        assertEquals(7, countNoticeMap.size());
        for (int i = 1; i <= 7; i++) {
          assertNotNull(countNoticeMap.get(i));
        }
      }

      {
        // select id, status from notice
        Map<Integer, Notice> countNoticeMap = mapper.getNoticeMap2();
        assertNotNull(countNoticeMap.get(1));
        // the last row will override the previous row
        assertEquals(5, countNoticeMap.get(1).getId());
        assertNotNull(countNoticeMap.get(2));
        assertEquals(7, countNoticeMap.get(2).getId());
      }
    }
  }

  @Test
  void shouldSelectMapResultUsingMapResult() {
    try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
      NoticeMapper mapper = sqlSession.getMapper(NoticeMapper.class);
      Map<Integer, Integer> statusCount = mapper.groupStatus();

      assertEquals(2, statusCount.size());
      assertEquals(3, statusCount.get(1));
      assertEquals(4, statusCount.get(2));
    }
  }

  @Test
  void shouldSelectMapResultUsingMapResultWithRowBounds() {
    try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
      NoticeMapper mapper = sqlSession.getMapper(NoticeMapper.class);
      Map<Integer, Integer> statusCount = mapper.groupStatus(new RowBounds(0, 1));

      assertEquals(1, statusCount.size());
      assertEquals(3, statusCount.get(1));
    }
  }

  @Test
  void shouldSelectMapResultUsingSqlSessionManagerWithRowBounds() {
    try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
      Map<Integer, Integer> statusCount = sqlSession.selectMap(
          "org.apache.ibatis.submitted.map_result.NoticeMapper.groupStatus", null, "status", "count",
          RowBounds.DEFAULT);

      assertEquals(2, statusCount.size());
      assertEquals(3, statusCount.get(1));
      assertEquals(4, statusCount.get(2));
    }
  }

  @Test
  void shouldRejectConflictingMapResultKey() {
    try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
      NoticeMapper mapper = sqlSession.getMapper(NoticeMapper.class);
      assertThrows(BindingException.class, mapper::groupStatusWithConflictingKey);
    }
  }
}
