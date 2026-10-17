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
package org.apache.ibatis.logging.jdbc;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.ibatis.logging.Log;

public class RecordingLog implements Log {

  private static final List<String> messages = new CopyOnWriteArrayList<>();

  public RecordingLog(String loggerName) {
    // LogFactory requires a public String constructor.
  }

  public static void clear() {
    messages.clear();
  }

  public static String output() {
    return String.join(System.lineSeparator(), messages);
  }

  @Override
  public boolean isDebugEnabled() {
    return true;
  }

  @Override
  public boolean isTraceEnabled() {
    return true;
  }

  @Override
  public void error(String s, Throwable e) {
    messages.add(s);
  }

  @Override
  public void error(String s) {
    messages.add(s);
  }

  @Override
  public void debug(String s) {
    messages.add(s);
  }

  @Override
  public void trace(String s) {
    messages.add(s);
  }

  @Override
  public void warn(String s) {
    messages.add(s);
  }

}
