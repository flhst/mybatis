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
package org.apache.ibatis.cache.decorators;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;

import org.apache.ibatis.cache.Cache;

/**
 * The 2nd level cache transactional buffer.
 * 
 * This class holds all cache entries that are to be added to the 2nd level cache during a Session.
 * Entries are sent to the cache when commit is called or discarded if the Session is rolled back. 
 * Blocking cache support has been added. Therefore any get() that returns a cache miss 
 * will be followed by a put() so any lock associated with the key can be released. 
 * 
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
/**
 * 事务缓存
 * 一次性存入多个缓存，移除多个缓存
 *
 * TransactionalCache实现了Cache接口，
 * 作用是保存某个sqlSession的某个事务中需要向某个二级缓存中添加的缓存数据，
 * 换句话说就是：某些缓存数据会先保存在这里，然后再提交到二级缓存中。
 *
 *
 * 事务缓存装饰器：
 *      TransactionCache增加了两个方法：
 *      commit()和rollback()当写入缓存时，
 *      只有调用commit()，缓存对象才会真正添加到TransactionCache中的delegate中（也就是真正的二级缓存），
 *      如果调用了rollback()，写入操作将被回滚。
 */
public class TransactionalCache implements Cache {

  // 底层封装的二级缓存所对应的Cache对象
  private Cache delegate;
  //commit时要不要清缓存
  // 该字段为true时，则表示当前TransactionalCache不可查询，
  // 且提交事务时，会将底层的Cache清空
  private boolean clearOnCommit;
  // 暂时记录添加都TransactionalCahce中的数据，在事务提交时，会将其中的数据添加到二级缓存中
  //commit时要添加的元素
  // 等待刷入数据，在提交时刷入cache实例
  private Map<Object, Object> entriesToAddOnCommit;
  // 读取缓存时，没有数据的key集合，提交时，创建value为null的entry对象，放入cache实例
  // BlockingCache.getObject()
  private Set<Object> entriesMissedInCache;

  public TransactionalCache(Cache delegate) {
    this.delegate = delegate;
    //默认commit时不清缓存
    this.clearOnCommit = false;
    this.entriesToAddOnCommit = new HashMap<Object, Object>();
    this.entriesMissedInCache = new HashSet<Object>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  // 查询底层的二级缓存
  @Override
  public Object getObject(Object key) {
    // issue #116
    // BlockingCache.getObject()
    Object object = delegate.getObject(key);
    if (object == null) {
      entriesMissedInCache.add(key);
    }
    // issue #146
    // 如果二级缓存不可查询返回null
    if (clearOnCommit) {
      return null;
    } else {
      return object;
    }
  }

  @Override
  public ReadWriteLock getReadWriteLock() {
    return null;
  }

  // 该方法并没有直接将查询的结果对象存储到其封装的二级缓存Cache对象中，
  // 而是暂时保存到entriesToAddOnCommit集合中，在事务提交时才会将这些结果从entriesToAddOnCommit集合中添加到二级缓存中
  @Override
  public void putObject(Object key, Object object) {
    // 放入到临时集合，等待提交
    entriesToAddOnCommit.put(key, object);
  }

  @Override
  public Object removeObject(Object key) {
    return null;
  }

  @Override
  public void clear() {
    clearOnCommit = true;
    entriesToAddOnCommit.clear();
  }

  // 将entriesToAddOnCommit集合中的结果对象添加到二级缓存中，
  // 准确的说是PerpetualCache类的HashMap中
  // 多了commit方法，提供事务功能
  //    1、检查是否需要清空缓存：
  //        如果 clearOnCommit 为 true，则调用 delegate.clear() 清空缓存。
  //    2、刷新待处理的缓存条目：
  //        调用 flushPendingEntries() 方法，
  //        将 entriesToAddOnCommit 中的条目添加到缓存中，
  //        并将 entriesMissedInCache 中未找到的条目设置为 null。
  //    3、重置状态：
  //        调用 reset() 方法，重置 clearOnCommit、
  //        entriesToAddOnCommit 和 entriesMissedInCache。
  public void commit() {
    if (clearOnCommit) {
      delegate.clear();
    }
    flushPendingEntries();
    reset();
  }

  public void rollback() {
    unlockMissedEntries();
    reset();
  }

  // 清除所有在事务中准备提交的数据，
  // 使 TransactionalCache 恢复到初始状态
  private void reset() {
    clearOnCommit = false;
    entriesToAddOnCommit.clear();
    entriesMissedInCache.clear();
  }

  // 在事务提交时将缓存中的待处理条目刷新到实际的缓存实例中
  private void flushPendingEntries() {
    for (Map.Entry<Object, Object> entry : entriesToAddOnCommit.entrySet()) {
      delegate.putObject(entry.getKey(), entry.getValue());
    }
    for (Object entry : entriesMissedInCache) {
      if (!entriesToAddOnCommit.containsKey(entry)) {
        delegate.putObject(entry, null);
      }
    }
  }

  // 在事务中，如果尝试读取某个缓存项但未找到，
  // 这些键会被记录在 entriesMissedInCache 集合中。
  // 通过将这些键值对（值为 null）添加到委托的 Cache 实例中，
  // 可以确保这些键在事务回滚后不会被误认为已存在。
  // 这一步实际上是释放了这些键的锁，
  // 使得其他事务可以重新尝试获取这些缓存项。
  //  BlockingCache.getObject
  // 将 entriesMissedInCache 集合中的所有键值对（其中值为 null）添加到委托的 Cache 实例中
  private void unlockMissedEntries() {
    for (Object entry : entriesMissedInCache) {
      delegate.putObject(entry, null);
    }
  }

}
