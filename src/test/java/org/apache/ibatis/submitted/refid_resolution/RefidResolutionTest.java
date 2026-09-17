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
package org.apache.ibatis.submitted.refid_resolution;

import static com.googlecode.catchexception.apis.BDDCatchException.caughtException;
import static com.googlecode.catchexception.apis.BDDCatchException.when;
import static org.assertj.core.api.BDDAssertions.then;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.IOException;
import java.io.Reader;
import java.util.Map;

import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RefidResolutionTest {
  @Test
  void includes() throws Exception {
    String resource = "org/apache/ibatis/submitted/refid_resolution/MapperConfig.xml";
    Reader reader = Resources.getResourceAsReader(resource);
    SqlSessionFactoryBuilder builder = new SqlSessionFactoryBuilder();
    SqlSessionFactory sqlSessionFactory = builder.build(reader);
    Assertions.assertThrows(PersistenceException.class,
        () -> sqlSessionFactory.getConfiguration().getMappedStatementNames());
  }

  @Test
  void externalRefAfterSelectKey() {
    assertDoesNotThrow(() -> {
      String resource = "org/apache/ibatis/submitted/refid_resolution/ExternalMapperConfig.xml";
      try (Reader reader = Resources.getResourceAsReader(resource)) {
        SqlSessionFactoryBuilder builder = new SqlSessionFactoryBuilder();
        SqlSessionFactory sqlSessionFactory = builder.build(reader);
        sqlSessionFactory.getConfiguration().getMappedStatementNames();
      }
    });
  }

  @Test
  void shouldNotPreservePrivateSqlNode() throws IOException {
    SqlSessionFactory sqlSessionFactory = SqlSessionFactoryBuilder
        .buildFromResource("org/apache/ibatis/submitted/refid_resolution/ExternalMapperConfig.xml");

    Configuration configuration = sqlSessionFactory.getConfiguration();
    Map<String, XNode> sqlFragments = configuration.getSqlFragments();

    Assertions.assertFalse(sqlFragments.containsKey("externalPrivateColumnList"));
    Assertions.assertFalse(sqlFragments
        .containsKey("org.apache.ibatis.submitted.refid_resolution.ExternalMapper2.externalPrivateColumnList"));

    Boolean hasShortStatement = assertDoesNotThrow(() -> configuration.hasStatement("insert", true));
    Assertions.assertNotNull(hasShortStatement);
    Assertions.assertTrue(hasShortStatement);

    Boolean hasFullStatement = assertDoesNotThrow(
        () -> configuration.hasStatement("org.apache.ibatis.submitted.refid_resolution.ExternalMapper2.insert", true));
    Assertions.assertNotNull(hasFullStatement);
    Assertions.assertTrue(hasFullStatement);
  }

  @Test
  void shouldThrowExceptionWhenRefPrivateSqlNode() {
    Configuration configuration = new Configuration();
    configuration.addXmlMapperResource("org/apache/ibatis/submitted/refid_resolution/ExternalMapper3.xml");

    final String exceptionMessage = "Could not find SQL statement to include with refid "
        + "'org.apache.ibatis.submitted.refid_resolution.ExternalMapper2.externalPrivateColumnList'"
        + ", maybe it's declared private.";

    when(() -> configuration.hasStatement("insert", true));
    then(caughtException()).isInstanceOf(IncompleteElementException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class).hasMessageContaining(exceptionMessage);

    when(() -> configuration.hasStatement("org.apache.ibatis.submitted.refid_resolution.ExternalMapper3.insert", true));
    then(caughtException()).isInstanceOf(IncompleteElementException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class).hasMessageContaining(exceptionMessage);
  }
}
