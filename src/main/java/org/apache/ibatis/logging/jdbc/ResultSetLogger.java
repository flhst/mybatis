/*
 *    Copyright 2009-2013 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.logging.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashSet;
import java.util.Set;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.reflection.ExceptionUtil;

/**
 * ResultSet proxy to add logging
 * 
 * @author Clinton Begin
 * @author Eduardo Macarron
 * 
 */
public final class ResultSetLogger extends BaseJdbcLogger implements InvocationHandler {

  private static Set<Integer> BLOB_TYPES = new HashSet<Integer>();
  private boolean first = true;
  private int rows = 0;
  private ResultSet rs;
  private Set<Integer> blobColumns = new HashSet<Integer>();

  static {
    BLOB_TYPES.add(Types.BINARY);
    BLOB_TYPES.add(Types.BLOB);
    BLOB_TYPES.add(Types.CLOB);
    BLOB_TYPES.add(Types.LONGNVARCHAR);
    BLOB_TYPES.add(Types.LONGVARBINARY);
    BLOB_TYPES.add(Types.LONGVARCHAR);
    BLOB_TYPES.add(Types.NCLOB);
    BLOB_TYPES.add(Types.VARBINARY);
  }
  
  private ResultSetLogger(ResultSet rs, Log statementLog, int queryStack) {
    super(statementLog, queryStack);
    this.rs = rs;
  }

  /**
   * 1、代理方法调用：
   *    如果调用的方法属于 Object 类，则直接调用并返回结果。
   * 2、处理 next 方法：
   *    如果调用的是 next 方法且返回 true，则记录一行数据。
   *    如果 isTraceEnabled 返回 true，则打印列头和列值。
   *    如果 next 方法返回 false，则记录总行数。
   * 3、异常处理：
   *    捕获并解包异常，重新抛出。
   */
  @Override
  public Object invoke(Object proxy, Method method, Object[] params) throws Throwable {
    try {
      if (Object.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, params);
      }    
      Object o = method.invoke(rs, params);
      if ("next".equals(method.getName())) {
        if (((Boolean) o)) {
          rows++;
          if (isTraceEnabled()) {
            ResultSetMetaData rsmd = rs.getMetaData();
            final int columnCount = rsmd.getColumnCount();
            if (first) {
              first = false;
              printColumnHeaders(rsmd, columnCount);
            }
            printColumnValues(columnCount);
          }
        } else {
          debug("     Total: " + rows, false);
        }
      }
      clearColumnInfo();
      return o;
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }
  }

  /**
   * 在日志中打印结果集的列名
   *    1、创建一个 StringBuilder 对象 row，并初始化字符串 " Columns: "。
   *    2、遍历结果集元数据中的所有列：
   *        检查当前列的数据类型是否为 BLOB 类型，如果是，则将其列索引添加到 blobColumns 集合中。
   *        获取当前列的列名，并将其追加到 row 中。
   *        如果当前列不是最后一列，则在列名后追加 ", "。
   *    3、将构建好的列名字符串通过 trace 方法记录到日志中。
   */
  private void printColumnHeaders(ResultSetMetaData rsmd, int columnCount) throws SQLException {
    StringBuilder row = new StringBuilder();
    row.append("   Columns: ");
    for (int i = 1; i <= columnCount; i++) {
      if (BLOB_TYPES.contains(rsmd.getColumnType(i))) {
        blobColumns.add(i);
      }
      String colname = rsmd.getColumnLabel(i);
      row.append(colname);
      if (i != columnCount) {
        row.append(", ");
      }
    }
    trace(row.toString(), false);
  }

  /**
   * 打印结果集的每一行数据
   *    1、初始化 StringBuilder 对象：
   *        创建一个 StringBuilder 对象 row，并添加前缀 " Row: "。
   *    2、遍历列：
   *        使用 for 循环遍历每一列（从 1 到 columnCount）。
   *    3、获取列名：
   *        如果当前列在 blobColumns 集合中，则设置 colname 为 "<<BLOB>>"。
   *        否则，尝试通过 rs.getString(i) 获取列值。如果发生 SQLException，则设置 colname 为 "<<Cannot Display>>"。
   *    4、拼接列值：
   *        将 colname 添加到 row 中，如果不是最后一列，则添加逗号和空格。
   *    5、记录日志：
   *        调用 trace 方法记录拼接好的行数据。
   */
  private void printColumnValues(int columnCount) {
    StringBuilder row = new StringBuilder();
    row.append("       Row: ");
    for (int i = 1; i <= columnCount; i++) {
      String colname;
      try {
        if (blobColumns.contains(i)) {
          colname = "<<BLOB>>";
        } else {
          colname = rs.getString(i);
        }
      } catch (SQLException e) {
        // generally can't call getString() on a BLOB column
        colname = "<<Cannot Display>>";
      }
      row.append(colname);
      if (i != columnCount) {
        row.append(", ");
      }
    }
    trace(row.toString(), false);
  }

  /*
   * Creates a logging version of a ResultSet
   *
   * @param rs - the ResultSet to proxy
   * @return - the ResultSet with logging
   */
  public static ResultSet newInstance(ResultSet rs, Log statementLog, int queryStack) {
    InvocationHandler handler = new ResultSetLogger(rs, statementLog, queryStack);
    ClassLoader cl = ResultSet.class.getClassLoader();
    return (ResultSet) Proxy.newProxyInstance(cl, new Class[]{ResultSet.class}, handler);
  }

  /*
   * Get the wrapped result set
   *
   * @return the resultSet
   */
  public ResultSet getRs() {
    return rs;
  }

}
