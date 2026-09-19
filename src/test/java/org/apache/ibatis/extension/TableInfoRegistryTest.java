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

import org.apache.ibatis.extension.entity.Role;
import org.apache.ibatis.extension.metadata.TableInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TableInfoRegistryTest {

  @Test
  void shouldScanJpaTableClasses() {
    final String packageName = TableInfoRegistryTest.class.getPackage().getName();

    TableInfoRegistry registry = new TableInfoRegistry();
    registry.registerPackage(packageName, TableInfoRegistry.JPA_CLASS_MATCHER);
    Assertions.assertTrue(registry.hasTableInfo(User.class));
    Assertions.assertTrue(registry.hasTableInfo(Role.class));

    TableInfo t_user = registry.getTableInfo(User.class);
    Assertions.assertNotNull(t_user);
    Assertions.assertEquals(3, t_user.getColumnCount());

    TableInfo t_role = registry.getTableInfo(Role.class);
    Assertions.assertNotNull(t_role);
    Assertions.assertEquals(2, t_role.getColumnCount());
  }
}
