/*
 *    Copyright 2009-2022 the original author or authors.
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.ibatis.BaseDataTest;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BaseJdbcLoggerTest {

  @Mock
  Log log;
  @Mock
  Array array;
  private BaseJdbcLogger logger;

  @BeforeEach
  void setUp() {
    logger = new BaseJdbcLogger(new JdbcLog(log, 1)) {
    };
  }

  @Test
  void shouldDescribePrimitiveArrayParameter() throws Exception {
    logger.setColumn("1", array);
    when(array.getArray()).thenReturn(new int[] { 1, 2, 3 });
    assertThat(logger.getParameterValueString()).startsWith("[1, 2, 3]");
  }

  @Test
  void shouldDescribeObjectArrayParameter() throws Exception {
    logger.setColumn("1", array);
    when(array.getArray()).thenReturn(new String[] { "one", "two", "three" });
    assertThat(logger.getParameterValueString()).startsWith("[one, two, three]");
  }

  @Test
  void shouldExposeParameterIndexesToCustomFormatting() {
    JdbcLog jdbcLog = new JdbcLog(log, 1) {
      @Override
      protected String getParameterValueAsString(List<Object> columnNames, List<Object> columnValues) {
        return columnNames.get(0) + "=" + columnValues.get(0) + ", " + columnNames.get(1) + "=" + columnValues.get(1);
      }
    };
    BaseJdbcLogger indexedLogger = new BaseJdbcLogger(jdbcLog) {
    };

    indexedLogger.setColumn(2, "secret");
    indexedLogger.setColumn(1, "visible");

    assertThat(indexedLogger.getParameterValueString()).isEqualTo("2=secret, 1=visible");
  }

  @Test
  @SuppressWarnings("deprecation")
  void shouldPreserveLegacyJdbcLogFactoryOverrides() {
    Connection connection = org.mockito.Mockito.mock(Connection.class);
    boolean[] legacyMethodCalled = { false };
    DefaultJdbcLogFactory factory = new DefaultJdbcLogFactory() {
      @Override
      public Connection enableSqlLog(Connection connection, MappedStatement stmt, int queryStack) {
        legacyMethodCalled[0] = true;
        return connection;
      }
    };

    Connection result = factory.enableSqlLog(connection, null, (BoundSql) null, 1);

    assertThat(result).isSameAs(connection);
    assertThat(legacyMethodCalled[0]).isTrue();
  }

  interface Mapper {

    @Select("select * from users where name = #{name} order by id")
    List<User> selectUsers(@Param("name") String name);
  }

  @Test
  void shouldMaskLogOutput() throws SQLException, IOException {
    SqlSessionFactory factory = BaseDataTest.createDefaultHsqlDbSqlSessionFactory("log_mask");
    BaseDataTest.runScriptSql(factory, """
        drop table users if exists;
        create table users (
          id int,
          name varchar(20)
        );
        insert into users values (1, 'User1');
        """);

    Configuration configuration = factory.getConfiguration();
    RecordingLog.clear();
    configuration.setLogImpl(RecordingLog.class);
    MaskableJdbcLogFactory maskableJdbcLogFactory = new MaskableJdbcLogFactory();
    configuration.setJdbcLogFactory(maskableJdbcLogFactory);
    configuration.addMapper(Mapper.class);

    try (SqlSession sqlSession = factory.openSession()) {
      Mapper mapper = sqlSession.getMapper(Mapper.class);
      List<User> users = mapper.selectUsers("User1");
      assertThat(users).hasSize(1);
      assertThat(users.get(0).getName()).isEqualTo("User1");
    }

    String logOutput = RecordingLog.output();
    assertThat(logOutput).contains("Parameters: *****(String)").doesNotContain("Parameters: User1(String)")
        .doesNotContain("User1");
  }

  static class MaskableJdbcLogFactory extends DefaultJdbcLogFactory {

    @Override
    public Connection enableSqlLog(Connection connection, MappedStatement stmt, BoundSql boundSql, int queryStack) {
      return ConnectionLogger.newInstance(connection,
          new JdbcLog(stmt.getStatementLog(), boundSql.getParameterMappings(), queryStack) {
            @Override
            protected String getColumnValue(ResultSet rs, int columnIndex, Set<Integer> blobColumns)
                throws SQLException {
              if ("NAME".equalsIgnoreCase(rs.getMetaData().getColumnLabel(columnIndex))) {
                return "*****";
              }
              return super.getColumnValue(rs, columnIndex, blobColumns);
            }

            @Override
            protected String getParameterValueAsString(List<Object> columnNames, List<Object> columnValues) {
              ArrayList<Object> values = new ArrayList<>(columnValues);
              for (int i = 0; i < values.size(); i++) {
                Object columnName = columnNames.get(i);
                int parameterIndex = columnName instanceof Number ? ((Number) columnName).intValue() - 1 : i;
                if (parameterIndex < 0 || parameterIndex >= parameterMappings.size()) {
                  continue;
                }
                ParameterMapping parameterMapping = parameterMappings.get(parameterIndex);
                String property = parameterMapping.getProperty();
                if ("name".equals(property)) {
                  values.set(i, "*****");
                }
              }
              return super.getParameterValueAsString(values);
            }
          });
    }
  }
}
