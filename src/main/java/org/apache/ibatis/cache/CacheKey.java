/*
 *    Copyright 2009-2014 the original author or authors.
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
package org.apache.ibatis.cache;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Clinton Begin
 */
/**
 * PerpetualCache的Map<Object, Object>第一个是CacheKey
 *
 * 因为MyBatis中涉及到动态SQL的原因，缓存项的key不能仅仅通过一个String来表示，
 * 所以通过CacheKey来封装缓存的key值
 * CacheKey可以封装多个影响缓存项的因素
 *  1、 mappedStementId
 *  2、指定查询结果集的范围（分页信息）
 *  3、查询所使用的SQL语句
 *  4、用户传递给SQL语句的实际参数值
 *
 * 缓存key
 * 一般缓存框架的数据结构基本上都是 Key-Value 方式存储，
 * MyBatis 对于其 Key 的生成采取规则为：[mappedStementId + offset + limit + SQL + queryParams + environment]生成一个哈希码
 */
public class CacheKey implements Cloneable, Serializable {

  private static final long serialVersionUID = 1146682552656046210L;

  public static final CacheKey NULL_CACHE_KEY = new NullCacheKey();

  // 参与hash计算的乘数的默认值
  private static final int DEFAULT_MULTIPLYER = 37;
  // hashcode默认值
  private static final int DEFAULT_HASHCODE = 17;

  // 参与hash计算的乘数
  private int multiplier;
  // CacheKey的hashcode，在update函数中实时运算出来的
  private int hashcode;
  // 校验和，hash值的和
  private long checksum;
  //updateList的中元素个数
  private int count;
  private List<Object> updateList;

  public CacheKey() {
    this.hashcode = DEFAULT_HASHCODE;
    this.multiplier = DEFAULT_MULTIPLYER;
    this.count = 0;
    this.updateList = new ArrayList<Object>();
  }

  //传入一个Object数组，更新hashcode和效验码
  public CacheKey(Object[] objects) {
    this();
    updateAll(objects);
  }

  public int getUpdateCount() {
    return updateList.size();
  }

  public void update(Object object) {
    if (object != null && object.getClass().isArray()) {
        //如果是数组，则循环调用doUpdate
      int length = Array.getLength(object);
      for (int i = 0; i < length; i++) {
        Object element = Array.get(object, i);
        doUpdate(element);
      }
    } else {
        //否则，doUpdate
      doUpdate(object);
    }
  }

  private void doUpdate(Object object) {
    //计算hash值，校验码
    int baseHashCode = object == null ? 1 : object.hashCode();

    count++;
    checksum += baseHashCode;
    baseHashCode *= count;

    hashcode = multiplier * hashcode + baseHashCode;

    //同时将对象加入列表，这样万一两个CacheKey的hash码碰巧一样，再根据对象严格equals来区分
    updateList.add(object);
  }

  public void updateAll(Object[] objects) {
    for (Object o : objects) {
      update(o);
    }
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (!(object instanceof CacheKey)) {
      return false;
    }

    final CacheKey cacheKey = (CacheKey) object;

    //先比hashcode，checksum，count，理论上可以快速比出来
    if (hashcode != cacheKey.hashcode) {
      return false;
    }
    if (checksum != cacheKey.checksum) {
      return false;
    }
    if (count != cacheKey.count) {
      return false;
    }

    //万一两个CacheKey的hash码碰巧一样，再根据对象严格equals来区分
    //这里两个list的size没比是否相等，其实前面count相等就已经保证了
    for (int i = 0; i < updateList.size(); i++) {
      Object thisObject = updateList.get(i);
      Object thatObject = cacheKey.updateList.get(i);
      if (thisObject == null) {
        if (thatObject != null) {
          return false;
        }
      } else {
        if (!thisObject.equals(thatObject)) {
          return false;
        }
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    return hashcode;
  }

  @Override
  public String toString() {
    StringBuilder returnValue = new StringBuilder().append(hashcode).append(':').append(checksum);
    for (int i = 0; i < updateList.size(); i++) {
      returnValue.append(':').append(updateList.get(i));
    }

    return returnValue.toString();
  }

  @Override
  public CacheKey clone() throws CloneNotSupportedException {
    CacheKey clonedCacheKey = (CacheKey) super.clone();
    clonedCacheKey.updateList = new ArrayList<Object>(updateList);
    return clonedCacheKey;
  }

}
