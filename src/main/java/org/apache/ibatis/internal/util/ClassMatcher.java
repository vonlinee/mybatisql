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
package org.apache.ibatis.internal.util;

import java.lang.annotation.Annotation;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A simple interface that specifies how to test classes to determine if they are to be included in the results produced
 * by the ResolverUtil.
 */
@FunctionalInterface
public interface ClassMatcher {

  /**
   * Will be called repeatedly with candidate classes. Must return True if a class is to be included in the results,
   * false otherwise.
   *
   * @param type
   *          the type
   *
   * @return true, if successful
   */
  boolean matches(Class<?> type);

  static ClassMatcher of(Predicate<Class<?>> predicate) {
    Objects.requireNonNull(predicate, "predicate is null");
    return new ClassMatcher() {
      @Override
      public boolean matches(Class<?> type) {
        return predicate.test(type);
      }
    };
  }

  /**
   * A {@link ClassMatcher} that checks to see if each class is assignable to the provided class. Note that this test
   * will match the parent type itself if it is presented for matching.
   */
  static ClassMatcher isA(final Class<?> parent) {
    return new ClassMatcher() {
      @Override
      public boolean matches(Class<?> type) {
        return type != null && parent.isAssignableFrom(type);
      }

      @Override
      public String toString() {
        return "is assignable to " + parent.getSimpleName();
      }
    };
  }

  /**
   * A {@link ClassMatcher} that checks to see if each class is annotated with a specific annotation. If it is, then the
   * test returns true, otherwise false.
   */
  static ClassMatcher annotatedWith(final Class<? extends Annotation> annotation) {
    return new ClassMatcher() {
      @Override
      public boolean matches(Class<?> type) {
        return type != null && type.isAnnotationPresent(annotation);
      }

      @Override
      public String toString() {
        return "annotated with @" + annotation.getSimpleName();
      }
    };
  }
}
