/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.pool;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.alibaba.druid.pool.DruidPooledPreparedStatement.PreparedStatementKey;
import com.alibaba.druid.proxy.jdbc.CallableStatementProxy;
import com.alibaba.druid.proxy.jdbc.PreparedStatementProxy;
import com.alibaba.druid.support.logging.Log;
import com.alibaba.druid.support.logging.LogFactory;
import com.alibaba.druid.util.OracleUtils;

/**
 * @author wenshao [szujobs@hotmail.com]
 */
public class PreparedStatementPool {

    private final static Log              LOG = LogFactory.getLog(PreparedStatementPool.class);

    /**
     *
     */
    private final LRUCache                map;
    private final DruidAbstractDataSource dataSource;

    public PreparedStatementPool(DruidConnectionHolder holder){
        this.dataSource = holder.getDataSource();
        int initCapacity = holder.getDataSource().getMaxPoolPreparedStatementPerConnectionSize();
        if (initCapacity <= 0) {
            initCapacity = 16;
        }
        map = new LRUCache(initCapacity);
    }

    public static enum MethodType {
        M1, M2, M3, M4, M5, M6, Precall_1, Precall_2, Precall_3
    }

    public PreparedStatementHolder get(PreparedStatementKey key) throws SQLException {
        PreparedStatementHolder holder = map.get(key);

        if (holder != null) {
            if (holder.isInUse() && (!dataSource.isSharePreparedStatements())) {
                return null;
            }

            holder.incrementHitCount();
            dataSource.incrementCachedPreparedStatementHitCount();
            if (holder.isEnterOracleImplicitCache()) {
                OracleUtils.exitImplicitCacheToActive(holder.statement);
            }
        } else {
            dataSource.incrementCachedPreparedStatementMissCount();
        }

        return holder;
    }

    public void remove(PreparedStatementHolder stmtHolder) throws SQLException {
        if (stmtHolder == null) {
            return;
        }
        map.remove(stmtHolder.key);
        closeRemovedStatement(stmtHolder);
    }

    public void put(PreparedStatementHolder stmtHolder) throws SQLException {
        PreparedStatement stmt = stmtHolder.statement;

        if (stmt == null) {
            return;
        }

        if (dataSource.isOracle() && dataSource.isUseOracleImplicitCache()) {
            OracleUtils.enterImplicitCache(stmt);
            stmtHolder.setEnterOracleImplicitCache(true);
        } else {
            stmtHolder.setEnterOracleImplicitCache(false);
        }

        PreparedStatementHolder oldStmtHolder = map.put(stmtHolder.key, stmtHolder);

        if (oldStmtHolder == stmtHolder) {
            return;
        }

        if (oldStmtHolder != null) {
            oldStmtHolder.setPooling(false);
            closeRemovedStatement(oldStmtHolder);
        } else {
            if (stmtHolder.getHitCount() == 0) {
                dataSource.incrementCachedPreparedStatementCount();
            }
        }

        stmtHolder.setPooling(true);

        if (LOG.isDebugEnabled()) {
            String message = null;
            if (stmtHolder.statement instanceof PreparedStatementProxy) {
                PreparedStatementProxy stmtProxy = (PreparedStatementProxy) stmtHolder.statement;
                if (stmtProxy instanceof CallableStatementProxy) {
                    message = "{conn-" + stmtProxy.getConnectionProxy().getId() + ", cstmt-" + stmtProxy.getId()
                              + "} enter cache";
                } else {
                    message = "{conn-" + stmtProxy.getConnectionProxy().getId() + ", pstmt-" + stmtProxy.getId()
                              + "} enter cache";
                }
            } else {
                message = "stmt enter cache";
            }

            LOG.debug(message);
        }
    }

    public void clear() {
        Iterator<Entry<PreparedStatementKey, PreparedStatementHolder>> iter = map.entrySet().iterator();
        while (iter.hasNext()) {
            Entry<PreparedStatementKey, PreparedStatementHolder> entry = iter.next();

            closeRemovedStatement(entry.getValue());

            iter.remove();
        }
    }

    public void closeRemovedStatement(PreparedStatementHolder holder) {
        if (LOG.isDebugEnabled()) {
            String message = null;
            if (holder.statement instanceof PreparedStatementProxy) {
                PreparedStatementProxy stmtProxy = (PreparedStatementProxy) holder.statement;
                if (stmtProxy instanceof CallableStatementProxy) {
                    message = "{conn-" + stmtProxy.getConnectionProxy().getId() + ", cstmt-" + stmtProxy.getId()
                              + "} exit cache";
                } else {
                    message = "{conn-" + stmtProxy.getConnectionProxy().getId() + ", pstmt-" + stmtProxy.getId()
                              + "} exit cache";
                }
            } else {
                message = "stmt exit cache";
            }

            LOG.debug(message);
        }

        holder.setPooling(false);
        if (holder.isInUse()) {
            return;
        }

        if (holder.isEnterOracleImplicitCache()) {
            try {
                OracleUtils.exitImplicitCacheToClose(holder.statement);
            } catch (Exception ex) {
                LOG.error("exitImplicitCacheToClose error", ex);
            }
        }
        dataSource.closePreapredStatement(holder);
    }

    public Map<PreparedStatementKey, PreparedStatementHolder> getMap() {
        return map;
    }

    public int size() {
        return this.map.size();
    }

    public class LRUCache extends LinkedHashMap<PreparedStatementKey, PreparedStatementHolder> {

        private static final long serialVersionUID = 1L;

        public LRUCache(int maxSize){
            super(maxSize, 0.75f, true);
        }

        /**
         *Returns true if this map should remove its eldest entry. This method is invoked by put and putAll after inserting a new entry into the map. It provides the implementor with the opportunity to remove the eldest entry each time a new one is added. This is useful if the map represents a cache: it allows the map to reduce memory consumption by deleting stale entries.
         * Sample use: this override will allow the map to grow up to 100 entries and then delete the eldest entry each time a new entry is added, maintaining a steady state of 100 entries.
         *            private static final int MAX_ENTRIES = 100;
         *
         *            protected boolean removeEldestEntry(Map.Entry eldest) {
         *               return size() > MAX_ENTRIES;
         *            }
         *
         * This method typically does not modify the map in any way, instead allowing the map to modify itself as directed by its return value. It is permitted for this method to modify the map directly, but if it does so, it must return false (indicating that the map should not attempt any further modification). The effects of returning true after modifying the map from within this method are unspecified.
         * This implementation merely returns false (so that this map acts like a normal map - the eldest element is never removed).
         *
         *
         *
         * 如果该地图应该移除其最年长的条目，则返回true。在映射中插入新条目后，put和putAll调用此方法。它为实现者提供了在每次添加新条目时删除最老条目的机会。如果映射表示缓存，这很有用:它允许映射通过删除过时的条目来减少内存消耗。
         * 示例使用:这个覆盖将允许映射扩展到100个条目，然后在每次添加新条目时删除最老的条目，保持100个条目的稳定状态。
         * private static final int MAX_ENTRIES = 100;
         *
         * 保护布尔removeEldestEntry(地图。条目老大){
         * 返回size() > MAX_ENTRIES;
         * ｝
         *
         * 该方法通常不会以任何方式修改映射，而是允许映射按照其返回值的指示修改自身。允许此方法直接修改映射，但如果这样做，它必须返回false(表示映射不应尝试任何进一步的修改)。在此方法中修改映射后返回true的效果是不确定的。
         * 这个实现只返回false(因此这个映射的行为就像一个普通映射——最年长的元素永远不会被删除)。
         *
         * @param eldest
         * @return
         */
        protected boolean removeEldestEntry(Entry<PreparedStatementKey, PreparedStatementHolder> eldest) {
            boolean remove = (size() > dataSource.getMaxPoolPreparedStatementPerConnectionSize());

            if (remove) {
                //在这里关闭 eldest  Statement。 removeEldestEntry方法中并没有将eldest从Map中移除。正如doc中建议的该方法不建议修改map
                //结构。这里只是在移除Eldest之前进行一个关闭操作非常巧妙
                closeRemovedStatement(eldest.getValue());
            }

            return remove;
        }
    }
}
