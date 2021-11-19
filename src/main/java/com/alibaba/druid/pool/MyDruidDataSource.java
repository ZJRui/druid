package com.alibaba.druid.pool;

import com.alibaba.druid.Constants;
import com.alibaba.druid.DbType;
import com.alibaba.druid.filter.AutoLoad;
import com.alibaba.druid.filter.Filter;
import com.alibaba.druid.filter.FilterChainImpl;
import com.alibaba.druid.mock.MockDriver;
import com.alibaba.druid.pool.vendor.MSSQLValidConnectionChecker;
import com.alibaba.druid.pool.vendor.MySqlValidConnectionChecker;
import com.alibaba.druid.pool.vendor.OracleValidConnectionChecker;
import com.alibaba.druid.pool.vendor.PGValidConnectionChecker;
import com.alibaba.druid.proxy.DruidDriver;
import com.alibaba.druid.proxy.jdbc.DataSourceProxyConfig;
import com.alibaba.druid.proxy.jdbc.TransactionInfo;
import com.alibaba.druid.stat.DruidDataSourceStatManager;
import com.alibaba.druid.stat.JdbcDataSourceStat;
import com.alibaba.druid.support.clickhouse.BalancedClickhouseDriver;
import com.alibaba.druid.support.logging.Log;
import com.alibaba.druid.support.logging.LogFactory;
import com.alibaba.druid.util.JdbcConstants;
import com.alibaba.druid.util.JdbcUtils;
import com.alibaba.druid.util.StringUtils;
import com.alibaba.druid.util.Utils;
import com.ibatis.sqlmap.engine.mapping.sql.Sql;

import javax.management.MBeanRegistration;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.Referenceable;
import javax.sql.ConnectionEvent;
import javax.sql.ConnectionEventListener;
import javax.sql.ConnectionPoolDataSource;
import javax.sql.PooledConnection;
import java.io.Closeable;
import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.locks.ReentrantLock;

import static com.alibaba.druid.util.Utils.getBoolean;

public class MyDruidDataSource extends DruidAbstractDataSource  implements DruidDataSourceMBean, ManagedDataSource, Referenceable , Closeable,Cloneable,
        ConnectionPoolDataSource, MBeanRegistration {

    private final static Log LOG = LogFactory.getLog(MyDruidDataSource.class);
    private static final Long serialVersionUID=1L;
    //stat
    private  volatile long  recycleErrorCount=0;
    private long  connectCount=0;
    private long closeCount=0;
    private long connectErrorCount=0;
    private long recycleCount=0;
    private long removeAbandonedCount=0;
    private long notEmptyWaitCount=0;
    private long notEmptySignalCount=0;
    private long notEmptyWaitNanos=0;
    private long keepAliveCheckCount=0;
    private long activePeak=0;
    private  long activePeakTime=0;
    private long poolingPeak=0;
    private long poolingPeakTime=0;

    //store
    private volatile DruidConnectionHolder[]connections;

    private int poolingCount=0;
    private int activeCount=0;
    private volatile long  discardCount=0;
    private int notEmptyWaitThreadCount=0;
    private int notEmptyWaitThreadPeak=0;


    //evict vt. 驱逐；逐出
    private DruidConnectionHolder[] evictConnections;
    private DruidConnectionHolder[] keepAliveConnections;

    private volatile ScheduledFuture<?> destorySchedulerFeature;
    private DruidDataSource.DestroyTask destroyTask;

    private volatile Future<?> createSchedulerFeature;


    private DruidDataSource.CreateConnectionThread createConnectionThread;

    private DruidDataSource.DestroyConnectionThread destroyConnectionThread;

    private DruidDataSource.LogStatsThread logStatsThread;
    protected boolean                        killWhenSocketReadTimeout = false;

    private int createTaskCount;
    private volatile long createTaskIDSeed=1L;
    private long[] createTasks;

    private final CountDownLatch initedLatch = new CountDownLatch(2);

    private volatile  boolean enable=true;
    private boolean resetStatEnable=true;

    private volatile  long resetCount=0L;
    private String initStackTrace;

    private volatile boolean closing = false;
    private volatile boolean closed = false;
    private long closeTimeMillis=0L;

    private JdbcDataSourceStat dataSourceStat;
    private boolean useGlobalDataSourceStat = false;

    private boolean mbeanRegistered=false;

    public static ThreadLocal<Long> waitNanosLocal = new ThreadLocal<>();
    private boolean logDifferentThread=true;
    private volatile boolean keepAlive=false;

    private boolean asyncInit=false;
    private boolean keepWhenSocketReadTimeout=false;

    private boolean checkExecuteTime=false;

    private static List<Filter> autoFilters = null;
    private boolean loadSpifilterSkip=false;

    private volatile DataSourceDisableException disableException;
    protected static final AtomicLongFieldUpdater<MyDruidDataSource> recycleErrorCountUpdater = AtomicLongFieldUpdater.newUpdater(MyDruidDataSource.class, "recycleErrorCount");
    protected static final AtomicLongFieldUpdater<MyDruidDataSource> connectErrorCountUpdater = AtomicLongFieldUpdater.newUpdater(MyDruidDataSource.class, "connectErrorCount");
    protected static final AtomicLongFieldUpdater<MyDruidDataSource> resetCountUpdater = AtomicLongFieldUpdater.newUpdater(MyDruidDataSource.class, "resetCount");
    protected static final AtomicLongFieldUpdater<MyDruidDataSource> createTaskIDSeedUpdater = AtomicLongFieldUpdater.newUpdater(MyDruidDataSource.class, "createTaskIDSeed");


    public MyDruidDataSource() {
        this(false);
    }

    public MyDruidDataSource(boolean lockFair) {
        super(lockFair);
        configFromPropety(System.getProperties());

    }


    public void configFromPropety(Properties properties) {
        {
            String property = properties.getProperty("druid.name");
            if (property != null) {
                this.setName(property);
            }
        }
        {
            String property = properties.getProperty("druid.url");
            if (property != null) {
                this.setUrl(property);
            }
        }
        {
            String property = properties.getProperty("druid.username");
            if (property != null) {
                this.setUsername(property);
            }
        }
        {
            String property = properties.getProperty("druid.password");
            if (property != null) {
                this.setPassword(property);
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.testWhileIdle");
            if (value != null) {
                this.testWhileIdle = value;
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.testOnBorrow");
            if (value != null) {
                this.testOnBorrow = value;
            }
        }
        {
            String property = properties.getProperty("druid.validationQuery");
            if (property != null && property.length() > 0) {
                this.setValidationQuery(property);
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.useGlobalDataSourceStat");
            if (value != null) {
                this.setUseGlobalDataSourceStat(value);
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.useGloalDataSourceStat"); // compatible for early versions
            if (value != null) {
                this.setUseGlobalDataSourceStat(value);
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.asyncInit"); // compatible for early versions
            if (value != null) {
                this.setAsyncInit(value);
            }
        }
        {
            String property = properties.getProperty("druid.filters");

            if (property != null && property.length() > 0) {
                try {
                    this.setFilters(property);
                } catch (SQLException e) {
                    LOG.error("setFilters error", e);
                }
            }
        }
        {
            String property = properties.getProperty(Constants.DRUID_TIME_BETWEEN_LOG_STATS_MILLIS);
            if (property != null && property.length() > 0) {
                try {
                    long value = Long.parseLong(property);
                    this.setTimeBetweenLogStatsMillis(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property '" + Constants.DRUID_TIME_BETWEEN_LOG_STATS_MILLIS + "'", e);
                }
            }
        }
        {
            String property = properties.getProperty(Constants.DRUID_STAT_SQL_MAX_SIZE);
            if (property != null && property.length() > 0) {
                try {
                    int value = Integer.parseInt(property);
                    if (dataSourceStat != null) {
                        dataSourceStat.setMaxSqlSize(value);
                    }
                } catch (NumberFormatException e) {
                    LOG.error("illegal property '" + Constants.DRUID_STAT_SQL_MAX_SIZE + "'", e);
                }
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.clearFiltersEnable");
            if (value != null) {
                this.setClearFiltersEnable(value);
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.resetStatEnable");
            if (value != null) {
                this.setResetStatEnable(value);
            }
        }
        {
            String property = properties.getProperty("druid.notFullTimeoutRetryCount");
            if (property != null && property.length() > 0) {
                try {
                    int value = Integer.parseInt(property);
                    this.setNotFullTimeoutRetryCount(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.notFullTimeoutRetryCount'", e);
                }
            }
        }
        {
            String property = properties.getProperty("druid.timeBetweenEvictionRunsMillis");
            if (property != null && property.length() > 0) {
                try {
                    long value = Long.parseLong(property);
                    this.setTimeBetweenEvictionRunsMillis(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.timeBetweenEvictionRunsMillis'", e);
                }
            }
        }
        {
            String property = properties.getProperty("druid.maxWaitThreadCount");
            if (property != null && property.length() > 0) {
                try {
                    int value = Integer.parseInt(property);
                    this.setMaxWaitThreadCount(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.maxWaitThreadCount'", e);
                }
            }
        }
        {
            String property = properties.getProperty("druid.maxWait");
            if (property != null && property.length() > 0) {
                try {
                    int value = Integer.parseInt(property);
                    this.setMaxWait(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.maxWait'", e);
                }
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.failFast");
            if (value != null) {
                this.setFailFast(value);
            }
        }
        {
            String property = properties.getProperty("druid.phyTimeoutMillis");
            if (property != null && property.length() > 0) {
                try {
                    long value = Long.parseLong(property);
                    this.setPhyTimeoutMillis(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.phyTimeoutMillis'", e);
                }
            }
        }
        {
            String property = properties.getProperty("druid.phyMaxUseCount");
            if (property != null && property.length() > 0) {
                try {
                    long value = Long.parseLong(property);
                    this.setPhyMaxUseCount(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.phyMaxUseCount'", e);
                }
            }
        }
        {
            String property = properties.getProperty("druid.minEvictableIdleTimeMillis");
            if (property != null && property.length() > 0) {
                try {
                    long value = Long.parseLong(property);
                    this.setMinEvictableIdleTimeMillis(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.minEvictableIdleTimeMillis'", e);
                }
            }
        }
        {
            String property = properties.getProperty("druid.maxEvictableIdleTimeMillis");
            if (property != null && property.length() > 0) {
                try {
                    long value = Long.parseLong(property);
                    this.setMaxEvictableIdleTimeMillis(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.maxEvictableIdleTimeMillis'", e);
                }
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.keepAlive");
            if (value != null) {
                this.setKeepAlive(value);
            }
        }
        {
            String property = properties.getProperty("druid.keepAliveBetweenTimeMillis");
            if (property != null && property.length() > 0) {
                try {
                    long value = Long.parseLong(property);
                    this.setKeepAliveBetweenTimeMillis(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.keepAliveBetweenTimeMillis'", e);
                }
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.poolPreparedStatements");
            if (value != null) {
                this.setPoolPreparedStatements0(value);
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.initVariants");
            if (value != null) {
                this.setInitVariants(value);
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.initGlobalVariants");
            if (value != null) {
                this.setInitGlobalVariants(value);
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.useUnfairLock");
            if (value != null) {
                this.setUseUnfairLock(value);
            }
        }
        {
            String property = properties.getProperty("druid.driverClassName");
            if (property != null) {
                this.setDriverClassName(property);
            }
        }
        {
            String property = properties.getProperty("druid.initialSize");
            if (property != null && property.length() > 0) {
                try {
                    int value = Integer.parseInt(property);
                    this.setInitialSize(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.initialSize'", e);
                }
            }
        }
        {
            String property = properties.getProperty("druid.minIdle");
            if (property != null && property.length() > 0) {
                try {
                    int value = Integer.parseInt(property);
                    this.setMinIdle(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.minIdle'", e);
                }
            }
        }
        {
            String property = properties.getProperty("druid.maxActive");
            if (property != null && property.length() > 0) {
                try {
                    int value = Integer.parseInt(property);
                    this.setMaxActive(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.maxActive'", e);
                }
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.killWhenSocketReadTimeout");
            if (value != null) {
                setKillWhenSocketReadTimeout(value);
            }
        }
        {
            String property = properties.getProperty("druid.connectProperties");
            if (property != null) {
                this.setConnectionProperties(property);
            }
        }
        {
            String property = properties.getProperty("druid.maxPoolPreparedStatementPerConnectionSize");
            if (property != null && property.length() > 0) {
                try {
                    int value = Integer.parseInt(property);
                    this.setMaxPoolPreparedStatementPerConnectionSize(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.maxPoolPreparedStatementPerConnectionSize'", e);
                }
            }
        }
        {
            String property = properties.getProperty("druid.initConnectionSqls");
            if (property != null && property.length() > 0) {
                try {
                    StringTokenizer tokenizer = new StringTokenizer(property, ";");
                    setConnectionInitSqls(Collections.list(tokenizer));
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.initConnectionSqls'", e);
                }
            }
        }
        {
            String property = System.getProperty("druid.load.spifilter.skip");
            if (property != null && !"false".equals(property)) {
                loadSpifilterSkip = true;
            }
        }
        {
            String property = System.getProperty("druid.checkExecuteTime");
            if (property != null && !"false".equals(property)) {
                checkExecuteTime = true;
            }
        }
    }

    @Override
    public void setPoolPreparedStatements(boolean value) {
        setPoolPreparedStatements0(false);

    }
    public void setPoolPreparedStatements0(boolean value) {
        if (this.poolPreparedStatements == value) {
            return;
        }
        this.poolPreparedStatements = value;
        if (!inited) {
            return;
        }
        if (LOG.isInfoEnabled()) {
            LOG.info("set poolPreparedStatements " + this.poolPreparedStatements + " -> " + value);
        }
        if (!value) {
            lock.lock();
            try {
                for (int i = 0; i < poolingCount; i++) {
                    DruidConnectionHolder connection = connections[i];
                    Collection<PreparedStatementHolder> values = connection.getStatementPool().getMap().values();
                    for (PreparedStatementHolder preparedStatementHolder : values) {
                        closePreapredStatement(preparedStatementHolder);
                    }
                    connection.getStatementPool().getMap().clear();

                }
            } catch (Exception e) {
                e.printStackTrace();
            }finally {
                lock.unlock();
            }
        }


    }

    public void restart()throws  SQLException{
        lock.lock();
        try{
            if (activeCount > 0) {
                throw new SQLException("can not restart, activeCount not zero. " + activeCount);
            }
            this.close();
            this.resetStat();
            this.inited = false;
            this.closed = false;
            this.enable = false;
        }finally {
            lock.unlock();

        }
    }


    public void setKillWhenSocketReadTimeout(boolean killWhenSocketTimeOut) {
        this.killWhenSocketReadTimeout = killWhenSocketTimeOut;
    }

    @Override
    public void logTransaction(TransactionInfo info) {

    }

    @Override
    public long getResetCount() {
        return 0;
    }

    @Override
    public boolean isEnable() {
        return false;
    }

    @Override
    public void setEnable(boolean value) {

    }

    @Override
    public void shrink() {

    }

    @Override
    public int removeAbandoned() {
        return 0;
    }

    @Override
    public String dump() {
        return null;
    }

    @Override
    public int getWaitThreadCount() {
        return 0;
    }

    @Override
    public int getLockQueueLength() {
        return 0;
    }

    @Override
    public long getNotEmptyWaitCount() {
        return 0;
    }

    @Override
    public int getNotEmptyWaitThreadCount() {
        return 0;
    }

    @Override
    public long getNotEmptySignalCount() {
        return 0;
    }

    @Override
    public long getNotEmptyWaitMillis() {
        return 0;
    }

    @Override
    public long getNotEmptyWaitNanos() {
        return 0;
    }

    @Override
    public void resetStat() {

    }

    @Override
    public boolean isResetStatEnable() {
        return false;
    }

    @Override
    public void setResetStatEnable(boolean resetStatEnable) {

    }

    @Override
    public String getVersion() {
        return null;
    }



    @Override
    public long getConnectCount() {
        return 0;
    }

    @Override
    public long getCloseCount() {
        return 0;
    }

    @Override
    public long getConnectErrorCount() {
        return 0;
    }

    @Override
    public int getPoolingCount() {
        return 0;
    }

    @Override
    public long getRecycleCount() {
        return 0;
    }

    @Override
    public int getActiveCount() {
        return 0;
    }

    @Override
    public long getCreateCount() {
        return 0;
    }

    @Override
    public long getDestroyCount() {
        return 0;
    }

    @Override
    public List<String> getFilterClassNames() {
        return null;
    }

    @Override
    public void setMaxActive(int maxActive) {

    }

    @Override
    public long getRemoveAbandonedCount() {
        return 0;
    }

    @Override
    public void setConnectProperties(Properties properties) {

    }

    @Override
    public void handleConnectionException(DruidPooledConnection conn, Throwable t, String sql) throws SQLException {
        DruidConnectionHolder connectionHolder = conn.getConnectionHolder();
        if (connectionHolder == null) {
            return;
        }
        errorCountUpdater.incrementAndGet(this);
        lastError=t;
        lastErrorTimeMillis = System.currentTimeMillis();
        if (t instanceof SQLException) {
            SQLException sqlException=(SQLException) t;
            PooledConnection theConnection;
            ConnectionEvent connectionEvent=new ConnectionEvent(conn, sqlException);
            for (ConnectionEventListener connectionEventListener : connectionHolder.getConnectionEventListeners()) {
                connectionEventListener.connectionErrorOccurred(connectionEvent);

            }
            if (exceptionSorter != null && exceptionSorter.isExceptionFatal(sqlException)) {
                handleFatalError(conn, sqlException, sql);
            }
            throw sqlException;
        }else{
            throw new SQLException("Error", t);
        }
    }

    public void handleFatalError(DruidPooledConnection connection,SQLException sqlException,String sql)throws  SQLException {
        final DruidConnectionHolder holder = connection.getConnectionHolder();
        if (connection.isTraceEnable()) {
            activeConnectionLock.lock();
            try{
                if (connection.isTraceEnable()) {
                    activeConnections.remove(connection);
                    connection.setTraceEnable(false);

                }
            }finally {
                activeConnectionLock.unlock();

            }
        }

        long lastErrorMillis = this.lastErrorTimeMillis;
        if (lastErrorMillis == 0) {
            lastErrorMillis = System.currentTimeMillis();
        }
        if (sql != null && sql.length() > 1024) {
            sql = sql.substring(0, 1024);
        }
        boolean requireDiscard=false;
        final ReentrantLock lock = connection.lock;

        lock.lock();
        try{
            //如果connection没有关闭或者connection没有disable
            if ((!connection.isClosed() || !connection.isDisable())) {
                connection.disable(sqlException);
                requireDiscard = true;
            }
            lastFatalErrorTimeMillis = lastErrorTimeMillis;
            fatalErrorCount++;
            if (fatalErrorCount - fatalErrorCountLastShrink > onFatalErrorMaxActive) {
                onFatalError=true;

            }
            lastFatalError = sqlException;
            lastFatalErrorSql = sql;
        }finally {
            lock.unlock();
        }
        if (onFatalError && holder != null && holder.getDataSource() != null) {
            ReentrantLock dataSourceLock = holder.getDataSource().lock;
            dataSourceLock.lock();
            try{

                emptySignal();
            }finally {
                dataSourceLock.unlock();

            }
        }

    }

    public void emptySignal() {
        if (createScheduler == null) {
            empty.signal();
            return;
        }
        if (createTaskCount >= maxCreateTaskCount) {
            return;
        }
        if (activeCount + poolingCount + createTaskCount > maxActive) {
            return;
        }
        submitCreateTask(false);
    }

    public void submitCreateTask(boolean initTask) {
        createTaskCount++;
        CreateConnectionTask createConnectionTask = new CreateConnectionTask(initTask);
        if (createTasks == null) {
            createTasks = new long[8];
        }
        boolean putted = false;
        for (int i = 0; i < createTasks.length; i++) {
            if (createTasks[i] == 0) {
                createTasks[i] = createConnectionTask.taskId;
                putted = true;
                break;

            }
        }
        //如果没有放置成功
        if (!putted) {
            long[] array = new long[createTasks.length * 3 / 2];
            System.arraycopy(createTasks, 0, array, 0, createTasks.length);
            array[createTasks.length] = createConnectionTask.taskId;
            createTasks = array;
        }
        this.createSchedulerFeature = this.createScheduler.submit(createConnectionTask);
    }
    @Override
    protected void recycle(DruidPooledConnection pooledConnection) throws SQLException {

    }

    @Override
    public int getActivePeak() {
        return 0;
    }

    @Override
    public int getPoolingPeak() {
        return 0;
    }

    @Override
    public Date getActivePeakTime() {
        return null;
    }

    @Override
    public Date getPoolingPeakTime() {
        return null;
    }

    @Override
    public long getErrorCount() {
        return 0;
    }

    @Override
    public void clearStatementCache() throws SQLException {

    }

    @Override
    public long getDiscardCount() {
        return 0;
    }

    @Override
    public int fill() throws SQLException {
        return 0;
    }

    @Override
    public int fill(int toCount) throws SQLException {
        return 0;
    }

    @Override
    public boolean isUseGlobalDataSourceStat() {
        return false;
    }

    @Override
    public int getRawDriverMajorVersion() {
        return 0;
    }

    @Override
    public int getRawDriverMinorVersion() {
        return 0;
    }

    @Override
    public String getProperties() {
        return null;
    }

    @Override
    public void discardConnection(Connection realConnection) {

    }

    private DruidConnectionHolder pollLast(long naos) throws  InterruptedException,SQLException {
        long estimate = naos;

        for (; ; ) {
            //表示connections中的可用连接数量为0
            if (poolingCount == 0) {

                emptySignal();

                if (failFast && isFailContinuous()) {
                    throw new DataSourceNotAvailableException(createError);
                }
                if (estimate <= 0) {
                    waitNanosLocal.set(naos - estimate);
                    return null;
                }
                notEmptyWaitThreadCount++;
                if (notEmptyWaitThreadCount > notEmptyWaitThreadPeak) {
                    notEmptyWaitThreadPeak = notEmptyWaitThreadCount;
                }
                try {
                    long startEstimate = estimate;
                    //notEmpty：当为空的时候调用awaitNanos 阻塞当前线程，等待createThread或者其他线程将连接归还后唤醒
                    estimate = notEmpty.awaitNanos(estimate);

                    notEmptyWaitCount++;
                    notEmptyWaitNanos += (startEstimate - estimate);
                    if (!enable) {
                        connectErrorCountUpdater.incrementAndGet(this);
                        if (disableException != null) {
                            throw disableException;
                        }

                        throw new DataSourceDisableException();
                    }
                } catch (InterruptedException interruptedException) {

                    notEmpty.signal();

                    notEmptySignalCount++;
                    throw interruptedException;

                }finally {
                    notEmptyWaitCount--;
                }
                if (poolingCount == 0) {
                    if (estimate > 0) {
                        continue;
                    }
                    waitNanosLocal.set(naos - estimate);
                    return null;
                }

            }//end poolingCount=0

            decrementPoolingCount();
            DruidConnectionHolder last = connections[poolingCount];
            connections[poolingCount] = null;
            long waitNanos = naos - estimate;
            last.setLastNotEmptyWaitNanos(waitNanos);
            return last;
        }

    }


    private final void decrementPoolingCount() {
        poolingCount--;
    }
    @Override
    public JdbcDataSourceStat getDataSourceStat() {
        return null;
    }

    @Override
    public void close()  {
        lock.lock();
        try{
            if (this.closed) {
                return;
            }
            if (!this.inited) {
                return;
            }
            this.closing=true;
            if (logStatsThread != null) {
                logStatsThread.interrupt();
            }
            if (createConnectionThread != null) {
                createConnectionThread.interrupt();

            }
            if (destroyConnectionThread != null) {
                destroyConnectionThread.interrupt();

            }
            if (createSchedulerFeature != null) {
                createSchedulerFeature.cancel(true);
            }
            if (destorySchedulerFeature != null) {
                destorySchedulerFeature.cancel(true);
            }
            for (int i = 0; i < poolingCount; i++) {
                DruidConnectionHolder connectionHolder = connections[i];
                Collection<PreparedStatementHolder> values = connectionHolder.getStatementPool().getMap().values();
                for (PreparedStatementHolder preparedStatementHolder : values) {
                    connectionHolder.getStatementPool().closeRemovedStatement(preparedStatementHolder);
                }
                connectionHolder.getStatementPool().getMap().clear();
                Connection physicalConnection = connectionHolder.getConnection();
                try {
                    physicalConnection.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                connections[i] = null;
                destroyCountUpdater.incrementAndGet(this);
            }


            poolingCount=0;
            unregisterMBean();

            enable=false;
            notEmpty.signalAll();
            notEmptySignalCount++;

            this.closed=true;
            this.closeTimeMillis = System.currentTimeMillis();
            disableException = new DataSourceDisableException();
            for (Filter filter : filters) {
                filter.destroy();
            }
        }finally {
            closing = false;
            lock.unlock();

        }
    }


    public  void unregisterMBean(){
        if (mbeanRegistered) {
            AccessController.doPrivileged(new PrivilegedAction<Object>() {

                @Override
                public Object run() {
                    DruidDataSourceStatManager.removeDataSource(MyDruidDataSource.this);
                    MyDruidDataSource.this.mbeanRegistered = false;

                    return null;
                }
            });
        }
    }

    @Override
    public ObjectName preRegister(MBeanServer server, ObjectName name) throws Exception {
        return null;
    }

    @Override
    public void postRegister(Boolean registrationDone) {

    }

    @Override
    public void preDeregister() throws Exception {

    }

    @Override
    public void postDeregister() {

    }

    @Override
    public Reference getReference() throws NamingException {
        return null;
    }

    @Override
    public PooledConnection getPooledConnection() throws SQLException {
        return null;
    }

    @Override
    public PooledConnection getPooledConnection(String user, String password) throws SQLException {
        return null;
    }

    @Override
    public DruidPooledConnection getConnection() throws SQLException {
        return getConnection(maxWait);
    }

    public DruidPooledConnection getConnection(long maxWatiMillis) throws  SQLException{
        init();
        if (filters.size() > 0) {
            FilterChainImpl filterChain = new FilterChainImpl(this);
            return filterChain.dataSource_connect(this, maxWatiMillis);
        }else{
            return getConnectionDirect(maxWatiMillis);
        }


    }

    protected DruidPooledConnection getConnectionDirect(long maxWatiMillis) {
        int notFullTimeoutRetryCnt=0;

        for (; ; ) {
            //yhandle notFulltimeoutRetry

            DruidPooledConnection poolableConnection;
            try {
                poolableConnection = getConnectionInternal(maxWatiMillis);
            } catch (GetConnectionTimeoutException e) {

            }



        }

        return null;
    }

    /**
     *
     *
     * @param maxWaitMillis
     * @return
     * @throws SQLException
     */
    private DruidPooledConnection getConnectionInternal(long maxWaitMillis) throws SQLException {

        if (closed) {
            connectErrorCountUpdater.incrementAndGet(this);
            throw new DataSourceClosedException("dataSource already closed at " + new Date(closeTimeMillis));
        }
        if (!enable) {
            connectErrorCountUpdater.incrementAndGet(this);
            if (disableException != null) {
                throw disableException;

            }
            throw new DataSourceDisableException();
        }
        final long naos = TimeUnit.MICROSECONDS.toNanos(maxWaitMillis);
        final long maxWaitThreadCount = this.maxWaitThreadCount;
        DruidConnectionHolder holder;
        for (boolean createDirect = false; ; ) {
            if (createDirect) {
                createStartNanosUpdater.set(this, System.nanoTime());

                if (creatingCountUpdater.compareAndSet(this, 0, 1)) {
                    PhysicalConnectionInfo physicalConnection = MyDruidDataSource.this.createPhysicalConnection();

                    holder = new DruidConnectionHolder(this, physicalConnection);
                    holder.lastActiveTimeMillis = System.currentTimeMillis();

                    creatingCountUpdater.decrementAndGet(this);
                    directCreateCountUpdater.incrementAndGet(this);

                    boolean discard=false;
                    lock.lock();

                    try{
                        if (activeCount < maxActive) {
                            activeCount++;
                            holder.active=true;
                            if (activeCount > activePeak) {
                                activePeak = activeCount;
                                activePeakTime = System.currentTimeMillis();

                            }
                            break;

                        }else{
                            discard = true;
                        }

                    }finally {
                        lock.unlock();
                    }
                    if (discard) {
                        JdbcUtils.close(physicalConnection.getPhysicalConnection());
                    }
                }//end cas
            }//end  createDirect


            //
            try {
                lock.lockInterruptibly();
            } catch (InterruptedException e) {
                connectErrorCountUpdater.incrementAndGet(this);
                throw new SQLException("interrupt", e);
            }
            //获取锁成功
            try{
                if (maxWaitThreadCount > 0 && notEmptyWaitThreadCount >= maxWaitThreadCount) {
                    connectErrorCountUpdater.incrementAndGet(this);
                    //lock
                    throw new SQLException("maxWaitThreadCount" + maxWaitThreadCount, ", current wait therad count" + lock.getQueueLength());
                }

                if (onFatalError && onFatalErrorMaxActive > 0 && activeCount > onFatalErrorMaxActive) {
                    connectErrorCountUpdater.incrementAndGet(this);
                    StringBuilder errorMsg = new StringBuilder();
                    errorMsg.append("onFatalError, activeCount ")
                            .append(activeCount)
                            .append(", onFatalErrorMaxActive ")
                            .append(onFatalErrorMaxActive);

                    if (lastFatalErrorTimeMillis > 0) {
                        errorMsg.append(", time '")
                                .append(StringUtils.formatDateTime19(
                                        lastFatalErrorTimeMillis, TimeZone.getDefault()))
                                .append("'");
                    }

                    if (lastFatalErrorSql != null) {
                        errorMsg.append(", sql \n")
                                .append(lastFatalErrorSql);
                    }

                    throw new SQLException(
                            errorMsg.toString(), lastFatalError);
                }
                //统计连接的数量？
                connectCount++;

                if (createScheduler != null && poolingCount == 0 && activeCount < maxActive && creatingCountUpdater.get(this) == 0 && createScheduler instanceof ScheduledThreadPoolExecutor) {
                    ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) createScheduler;
                    if (executor.getQueue().size() > 0) {
                        createDirect = true;
                        continue;

                    }
                }
                if (maxWaitMillis > 0) {
                    holder = pollLast(naos);
                }else{
                    holder = taskLast();
                }


            }



        }//end for
    }

    public void init() throws SQLException {
        if (inited) {
            return;
        }
        DruidDriver.getInstance();
        final ReentrantLock lock = this.lock;
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            throw new SQLException("interrupt", e);
        }
        boolean init=false;
        try {
            if (inited) {
                return;
            }
            initStackTrace = Utils.toString(Thread.currentThread().getStackTrace());
            this.id = DruidDriver.createDataSourceId();
            if (this.id > 1) {
                long delta = (this.id - 1) * 100000;
                this.connectionIdSeedUpdater.addAndGet(this, delta);
                this.statementIdSeedUpdater.addAndGet(this, delta);
                this.resultSetIdSeedUpdater.addAndGet(this, delta);
                this.transactionIdSeedUpdater.addAndGet(this, delta);
            }
            if (this.jdbcUrl != null) {
                this.jdbcUrl = this.jdbcUrl.trim();
                initFromWrapDriverUrl(this.jdbcUrl);
            }
            for (Filter filter : this.filters) {
                filter.init(this);

            }
            if (this.dbTypeName == null || this.dbTypeName.length() == 0) {
                this.dbTypeName = JdbcUtils.getDbType(this.jdbcUrl, null);
            }
            DbType dbType = DbType.of(this.dbTypeName);
            if (dbType == DbType.mysql || dbType == DbType.mariadb || dbType == DbType.oceanbase || dbType == DbType.ads) {
                boolean cacheServerConfigurationSet = false;
                if (this.connectProperties.containsKey("cacheServerConfiguration")) {
                    cacheServerConfigurationSet = true;
                } else if (this.jdbcUrl.indexOf("cacheServerConfiguration") != -1) {
                    cacheServerConfigurationSet = true;
                }
                if (cacheServerConfigurationSet) {
                    this.connectProperties.put("cacheServerConfiguration", "true");
                }

            }
            if (maxActive <= 0) {
                throw new IllegalArgumentException("illegal maxActive " + maxActive);
            }

            if (maxActive < minIdle) {
                throw new IllegalArgumentException("illegal maxActive " + maxActive);
            }

            if (getInitialSize() > maxActive) {
                throw new IllegalArgumentException("illegal initialSize " + this.initialSize + ", maxActive " + maxActive);
            }

            if (timeBetweenLogStatsMillis > 0 && useGlobalDataSourceStat) {
                throw new IllegalArgumentException("timeBetweenLogStatsMillis not support useGlobalDataSourceStat=true");
            }

            if (maxEvictableIdleTimeMillis < minEvictableIdleTimeMillis) {
                throw new SQLException("maxEvictableIdleTimeMillis must be grater than minEvictableIdleTimeMillis");
            }

            if (keepAlive && keepAliveBetweenTimeMillis <= timeBetweenEvictionRunsMillis) {
                throw new SQLException("keepAliveBetweenTimeMillis must be grater than timeBetweenEvictionRunsMillis");
            }
            if (this.driverClass != null) {
                this.driverClass = this.driverClass.trim();
            }
            initFromSPIServiceLoader();
            resolveDriver();

            initCheck();
            initExceptionSorter();

            initValidConnectionChecker();
            validationQueryCheck();

            if (isUseGlobalDataSourceStat()) {
                dataSourceStat = JdbcDataSourceStat.getGlobal();
                if (dataSourceStat == null) {
                    dataSourceStat = new JdbcDataSourceStat("Global", "Global", this.dbTypeName);
                    JdbcDataSourceStat.setGlobal(dataSourceStat);
                }
                if (dataSourceStat.getDbType() == null) {
                    dataSourceStat.setDbType(this.dbTypeName);
                }
            } else {
                dataSourceStat = new JdbcDataSourceStat(this.name, this.jdbcUrl, this.dbTypeName, this.connectProperties);
            }
            dataSourceStat.setResetStatEnable(this.resetStatEnable);
            //创建连接池
            connections = new DruidConnectionHolder[maxActive];
            evictConnections = new DruidConnectionHolder[maxActive];
            keepAliveConnections = new DruidConnectionHolder[maxActive];

            SQLException connectError = null;

            if (createScheduler != null && asyncInit) {
                for (int i = 0; i < initialSize; i++) {
                    submitCreateTask(true);
                }
            } else if (!asyncInit) {
                //为什么是poolingCount？
                while (poolingCount < initialSize) {

                    try {
                        //通过 java.sql.Driver .connect 创建Connection
                        PhysicalConnectionInfo physicalConnectionInfo = createPhysicalConnection();
                        DruidConnectionHolder holder = new DruidConnectionHolder(this, physicalConnectionInfo);
                        connections[poolingCount++] = holder;
                    } catch (SQLException e) {
                        if (initExceptionThrow) {
                            connectError=e;
                            break;
                        }else{
                            Thread.sleep(3000);
                        }
                    }
                }
                if (poolingCount > 0) {
                    poolingPeak = poolingCount;
                    poolingPeakTime = System.currentTimeMillis();
                }
            }//end if

            createAndLogThread();

            createAndStartCreatorThread();


        } catch (Exception e) {

        }


    }

    public  void createAndStartCreatorThread() {
        if (createScheduler == null) {
            String threadName = "Druid-connectionpool-create-" + System.identityHashCode(this);
            createConnectionThread = new DruidDataSource.CreateConnectionThread(threadName);
            createConnectionThread.start();

            return;
        }
        initedLatch.countDown();


    }
    private void createAndLogThread() {
        if (this.timeBetweenLogStatsMillis <= 0) {
            return;
        }
        String threadName = "Druid=connectionPool-log-" + System.identityHashCode(this);
        logStatsThread = new DruidDataSource.LogStatsThread(threadName);
        logStatsThread.start();
        this.resetStatEnable = false;
    }

    public void validationQueryCheck() {
        if (this.validConnectionChecker != null) {
            return;
        }

        String realDriverClassName = driver.getClass().getName();
        if (JdbcUtils.isMySqlDriver(realDriverClassName)) {
            this.validConnectionChecker = new MySqlValidConnectionChecker();

        } else if (realDriverClassName.equals(JdbcConstants.ORACLE_DRIVER)
                || realDriverClassName.equals(JdbcConstants.ORACLE_DRIVER2)) {
            this.validConnectionChecker = new OracleValidConnectionChecker();

        } else if (realDriverClassName.equals(JdbcConstants.SQL_SERVER_DRIVER)
                || realDriverClassName.equals(JdbcConstants.SQL_SERVER_DRIVER_SQLJDBC4)
                || realDriverClassName.equals(JdbcConstants.SQL_SERVER_DRIVER_JTDS)) {
            this.validConnectionChecker = new MSSQLValidConnectionChecker();

        } else if (realDriverClassName.equals(JdbcConstants.POSTGRESQL_DRIVER)
                || realDriverClassName.equals(JdbcConstants.ENTERPRISEDB_DRIVER)
                || realDriverClassName.equals(JdbcConstants.POLARDB_DRIVER)) {
            this.validConnectionChecker = new PGValidConnectionChecker();
        }
    }

    public void initValidConnectionChecker() {
        if (this.validConnectionChecker != null) {
            return;
        }

        String realDriverClassName = driver.getClass().getName();
        if (JdbcUtils.isMySqlDriver(realDriverClassName)) {
            this.validConnectionChecker = new MySqlValidConnectionChecker();

        } else if (realDriverClassName.equals(JdbcConstants.ORACLE_DRIVER)
                || realDriverClassName.equals(JdbcConstants.ORACLE_DRIVER2)) {
            this.validConnectionChecker = new OracleValidConnectionChecker();

        } else if (realDriverClassName.equals(JdbcConstants.SQL_SERVER_DRIVER)
                || realDriverClassName.equals(JdbcConstants.SQL_SERVER_DRIVER_SQLJDBC4)
                || realDriverClassName.equals(JdbcConstants.SQL_SERVER_DRIVER_JTDS)) {
            this.validConnectionChecker = new MSSQLValidConnectionChecker();

        } else if (realDriverClassName.equals(JdbcConstants.POSTGRESQL_DRIVER)
                || realDriverClassName.equals(JdbcConstants.ENTERPRISEDB_DRIVER)
                || realDriverClassName.equals(JdbcConstants.POLARDB_DRIVER)) {
            this.validConnectionChecker = new PGValidConnectionChecker();
        }
    }

    public void initExceptionSorter() {

    }
    public void initCheck() {

    }


    protected void resolveDriver() throws SQLException {
        if (this.driver == null) {
            if (this.driverClass == null || this.driverClass.isEmpty()) {
                this.driverClass = JdbcUtils.getDriverClassName(this.jdbcUrl);
            }

            if (MockDriver.class.getName().equals(driverClass)) {
                driver = MockDriver.instance;
            } else if ("com.alibaba.druid.support.clickhouse.BalancedClickhouseDriver".equals(driverClass)) {
                Properties info = new Properties();
                info.put("user", username);
                info.put("password", password);
                info.putAll(connectProperties);
                driver = new BalancedClickhouseDriver(jdbcUrl, info);
            } else {
                if (jdbcUrl == null && (driverClass == null || driverClass.length() == 0)) {
                    throw new SQLException("url not set");
                }
                driver = JdbcUtils.createDriver(driverClassLoader, driverClass);
            }
        } else {
            if (this.driverClass == null) {
                this.driverClass = driver.getClass().getName();
            }
        }
    }
    public void initFromSPIServiceLoader() {
        if (loadSpifilterSkip) {
            return;
        }
        if (autoFilters == null) {
            List<Filter> filters = new ArrayList<>();
            ServiceLoader<Filter> filterServiceLoader = ServiceLoader.load(Filter.class);

            for (Filter filter : filterServiceLoader) {
                AutoLoad annotation = filter.getClass().getAnnotation(AutoLoad.class);
                if (annotation != null && annotation.value()){
                    filters.add(filter);
                }
            }
            autoFilters = filters;

        }
        for (Filter autoFilter : autoFilters) {
            addFilter(autoFilter);
        }


    }

    /**
     * 会去重复
     *
     * @param filter
     */
    private void addFilter(Filter filter) {
        boolean exists = false;
        for (Filter initedFilter : this.filters) {
            if (initedFilter.getClass() == filter.getClass()) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            filter.init(this);
            this.filters.add(filter);
        }

    }

    private void initFromWrapDriverUrl() throws SQLException {
        if (!jdbcUrl.startsWith(DruidDriver.DEFAULT_PREFIX)) {
            return;
        }

        DataSourceProxyConfig config = DruidDriver.parseConfig(jdbcUrl, null);
        this.driverClass = config.getRawDriverClassName();

        LOG.error("error url : '" + jdbcUrl + "', it should be : '" + config.getRawUrl() + "'");

        this.jdbcUrl = config.getRawUrl();
        if (this.name == null) {
            this.name = config.getName();
        }

        for (Filter filter : config.getFilters()) {
            addFilter(filter);
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return null;
    }

    //get /set

    public long getRecycleErrorCount() {
        return recycleErrorCount;
    }

    public void setRecycleErrorCount(long recycleErrorCount) {
        this.recycleErrorCount = recycleErrorCount;
    }

    public void setConnectCount(long connectCount) {
        this.connectCount = connectCount;
    }

    public void setCloseCount(long closeCount) {
        this.closeCount = closeCount;
    }

    public void setConnectErrorCount(long connectErrorCount) {
        this.connectErrorCount = connectErrorCount;
    }

    public void setRecycleCount(long recycleCount) {
        this.recycleCount = recycleCount;
    }

    public void setRemoveAbandonedCount(long removeAbandonedCount) {
        this.removeAbandonedCount = removeAbandonedCount;
    }

    public void setNotEmptyWaitCount(long notEmptyWaitCount) {
        this.notEmptyWaitCount = notEmptyWaitCount;
    }

    public void setNotEmptySignalCount(long notEmptySignalCount) {
        this.notEmptySignalCount = notEmptySignalCount;
    }

    public void setNotEmptyWaitNanos(long notEmptyWaitNanos) {
        this.notEmptyWaitNanos = notEmptyWaitNanos;
    }

    public long getKeepAliveCheckCount() {
        return keepAliveCheckCount;
    }

    public void setKeepAliveCheckCount(long keepAliveCheckCount) {
        this.keepAliveCheckCount = keepAliveCheckCount;
    }

    public void setActivePeak(long activePeak) {
        this.activePeak = activePeak;
    }

    public void setActivePeakTime(long activePeakTime) {
        this.activePeakTime = activePeakTime;
    }

    public void setPoolingPeak(long poolingPeak) {
        this.poolingPeak = poolingPeak;
    }

    public void setPoolingPeakTime(long poolingPeakTime) {
        this.poolingPeakTime = poolingPeakTime;
    }

    public DruidConnectionHolder[] getConnections() {
        return connections;
    }

    public void setConnections(DruidConnectionHolder[] connections) {
        this.connections = connections;
    }

    public void setPoolingCount(int poolingCount) {
        this.poolingCount = poolingCount;
    }

    public void setActiveCount(int activeCount) {
        this.activeCount = activeCount;
    }

    public void setDiscardCount(long discardCount) {
        this.discardCount = discardCount;
    }

    public void setNotEmptyWaitThreadCount(int notEmptyWaitThreadCount) {
        this.notEmptyWaitThreadCount = notEmptyWaitThreadCount;
    }

    public int getNotEmptyWaitThreadPeak() {
        return notEmptyWaitThreadPeak;
    }

    public void setNotEmptyWaitThreadPeak(int notEmptyWaitThreadPeak) {
        this.notEmptyWaitThreadPeak = notEmptyWaitThreadPeak;
    }

    public DruidConnectionHolder[] getEvictConnections() {
        return evictConnections;
    }

    public void setEvictConnections(DruidConnectionHolder[] evictConnections) {
        this.evictConnections = evictConnections;
    }

    public DruidConnectionHolder[] getKeepAliveConnections() {
        return keepAliveConnections;
    }

    public void setKeepAliveConnections(DruidConnectionHolder[] keepAliveConnections) {
        this.keepAliveConnections = keepAliveConnections;
    }

    public ScheduledFuture<?> getDestorySchedulerFeature() {
        return destorySchedulerFeature;
    }

    public void setDestorySchedulerFeature(ScheduledFuture<?> destorySchedulerFeature) {
        this.destorySchedulerFeature = destorySchedulerFeature;
    }

    public DruidDataSource.DestroyTask getDestroyTask() {
        return destroyTask;
    }

    public void setDestroyTask(DruidDataSource.DestroyTask destroyTask) {
        this.destroyTask = destroyTask;
    }

    public Future<?> getCreateSchedulerFeature() {
        return createSchedulerFeature;
    }

    public void setCreateSchedulerFeature(Future<?> createSchedulerFeature) {
        this.createSchedulerFeature = createSchedulerFeature;
    }

    public DruidDataSource.CreateConnectionThread getCreateConnectionThread() {
        return createConnectionThread;
    }

    public void setCreateConnectionThread(DruidDataSource.CreateConnectionThread createConnectionThread) {
        this.createConnectionThread = createConnectionThread;
    }

    public DruidDataSource.DestroyConnectionThread getDestroyConnectionThread() {
        return destroyConnectionThread;
    }

    public void setDestroyConnectionThread(DruidDataSource.DestroyConnectionThread destroyConnectionThread) {
        this.destroyConnectionThread = destroyConnectionThread;
    }

    public DruidDataSource.LogStatsThread getLogStatsThread() {
        return logStatsThread;
    }

    public void setLogStatsThread(DruidDataSource.LogStatsThread logStatsThread) {
        this.logStatsThread = logStatsThread;
    }

    public int getCreateTaskCount() {
        return createTaskCount;
    }

    public void setCreateTaskCount(int createTaskCount) {
        this.createTaskCount = createTaskCount;
    }

    public long getCreateTaskIDSeed() {
        return createTaskIDSeed;
    }

    public void setCreateTaskIDSeed(long createTaskIDSeed) {
        this.createTaskIDSeed = createTaskIDSeed;
    }

    public long[] getCreateTasks() {
        return createTasks;
    }

    public void setCreateTasks(long[] createTasks) {
        this.createTasks = createTasks;
    }

    public CountDownLatch getInitedLatch() {
        return initedLatch;
    }

    public void setResetCount(long resetCount) {
        this.resetCount = resetCount;
    }

    public String getInitStackTrace() {
        return initStackTrace;
    }

    public void setInitStackTrace(String initStackTrace) {
        this.initStackTrace = initStackTrace;
    }

    public boolean isClosing() {
        return closing;
    }

    public void setClosing(boolean closing) {
        this.closing = closing;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public long getCloseTimeMillis() {
        return closeTimeMillis;
    }

    public void setCloseTimeMillis(long closeTimeMillis) {
        this.closeTimeMillis = closeTimeMillis;
    }


    public void setDataSourceStat(JdbcDataSourceStat dataSourceStat) {
        this.dataSourceStat = dataSourceStat;
    }

    public void setUseGlobalDataSourceStat(boolean useGlobalDataSourceStat) {
        this.useGlobalDataSourceStat = useGlobalDataSourceStat;
    }

    public boolean isMbeanRegistered() {
        return mbeanRegistered;
    }

    public void setMbeanRegistered(boolean mbeanRegistered) {
        this.mbeanRegistered = mbeanRegistered;
    }

    public static ThreadLocal<Long> getWaitNanosLocal() {
        return waitNanosLocal;
    }

    public static void setWaitNanosLocal(ThreadLocal<Long> waitNanosLocal) {
        MyDruidDataSource.waitNanosLocal = waitNanosLocal;
    }

    public boolean isLogDifferentThread() {
        return logDifferentThread;
    }

    public void setLogDifferentThread(boolean logDifferentThread) {
        this.logDifferentThread = logDifferentThread;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public boolean isAsyncInit() {
        return asyncInit;
    }

    public void setAsyncInit(boolean asyncInit) {
        this.asyncInit = asyncInit;
    }

    public boolean isKeepWhenSocketReadTimeout() {
        return keepWhenSocketReadTimeout;
    }

    public void setKeepWhenSocketReadTimeout(boolean keepWhenSocketReadTimeout) {
        this.keepWhenSocketReadTimeout = keepWhenSocketReadTimeout;
    }

    public boolean isCheckExecuteTime() {
        return checkExecuteTime;
    }

    public void setCheckExecuteTime(boolean checkExecuteTime) {
        this.checkExecuteTime = checkExecuteTime;
    }

    public static List<Filter> getAutoFilters() {
        return autoFilters;
    }

    public static void setAutoFilters(List<Filter> autoFilters) {
        MyDruidDataSource.autoFilters = autoFilters;
    }

    public boolean isKillWhenSocketReadTimeout() {
        return killWhenSocketReadTimeout;
    }

    public void setLoadSpifilterSkip(boolean loadSpifilterSkip) {
        this.loadSpifilterSkip = loadSpifilterSkip;
    }

    public DataSourceDisableException getDisableException() {
        return disableException;
    }

    public void setDisableException(DataSourceDisableException disableException) {
        this.disableException = disableException;
    }


    public class CreateConnectionTask implements  Runnable{

        private int errorCount=0;
        private boolean initTask=false;
        private final long taskId;

        public CreateConnectionTask(boolean initTask) {
            this.initTask = initTask;
            taskId = createTaskIDSeedUpdater.incrementAndGet(MyDruidDataSource.this);

        }

        @Override
        public void run() {
            runInternal();
        }

        private void runInternal() {
            for (; ; ) {
                lock.lock();
                try{
                    if (closed || closing) {
                        clearCreateTask(this.taskId);
                        return;
                    }
                    boolean emptyWait=true;
                    if (createError != null && poolingCount == 0) {
                        emptyWait=false;
                    }
                    if (emptyWait) {
                        if (poolingCount >= notEmptyWaitThreadCount && (!(keepAlive && activeCount + poolingCount < minIdle))
                                && (!initTask)
                                && !isFailContinuous()
                                && !isOnFatalError()) {
                            clearCreateTask(taskId);
                            return;
                        }

                        if (activeCount + poolingCount > maxActive) {
                            clearCreateTask(taskId);
                            return;
                        }
                    }//end if

                }finally {
                    lock.unlock();

                }

                //
            }
        }
    }
    //failContinueUpdate==1 返回true 表示失败之后继续
    public boolean isFailContinuous() {
        return failContinuousUpdater.get(this) == 1;
    }
    public boolean isOnFatalError() {
        return onFatalError;
    }
    private boolean clearCreateTask(long taskId) {
        if (createTasks == null) {
            return false;
        }
        if (taskId == 0) {
            return false;
        }
        for (int i = 0; i < createTasks.length; i++) {
            if (createTasks[i] == taskId) {
                createTasks[i]=0;
                createTaskCount--;
                if (createTaskCount < 0) {
                    createTaskCount = 0;
                }
                if (createTaskCount == 0 && createTasks.length > 8) {
                    createTasks = new long[8];
                }
                return true;
            }
        }
        return false;
    }




    public class CreateConnectionThread extends  Thread{

        public CreateConnectionThread(String name) {
            super(name);
            this.setDaemon(true);

        }
        public void run(){

            initedLatch.countDown();
            long lastDiscardCount=0;
            int errorCount=0;
            for (; ; ) {

                try {
                    lock.lockInterruptibly();
                } catch (InterruptedException interruptedException) {
                    break;
                }
                //获取lock锁成功
                long discardCount = MyDruidDataSource.this.discardCount;
                boolean discardChanged = discardCount - lastDiscardCount > 0;
                lastDiscardCount = discardCount;
                try {
                    boolean emptyWait = true;

                    if (createError != null && poolingCount == 0 && !discardChanged) {
                        emptyWait = false;
                    }
                    if (emptyWait && asyncInit && createCount < initialSize) {
                        emptyWait = false;
                    }
                    if (emptyWait) {
                        //必须存在线程等待，才能创建
                        if (poolingCount >= notEmptyWaitThreadCount && (!(keepAlive && activeCount + poolingCount < minIdle)) && !isFailContinuous()) {
                            empty.await();
                        }
                    }

                    if (activeCount + poolingCount >= maxActive) {
                        empty.await();
                        continue;

                    }
                } catch (InterruptedException interruptedException) {
                    lastCreateError = interruptedException;
                    lastErrorTimeMillis = System.currentTimeMillis();
                    if ((!closing) && (!closed)) {
                        //log
                    }
                    break;

                }finally{
                    lock.unlock();

                }

                PhysicalConnectionInfo connectionInfo = null;
                try {
                    connectionInfo = createPhysicalConnection();
                } catch (SQLException e) {
                    errorCount++;
                    if (errorCount > connectionErrorRetryAttempts && timeBetweenConnectErrorMillis > 0) {
                        setFailContinuous(true);
                        if (failFast) {
                            lock.lock();
                            try {
                                notEmpty.signalAll();
                            } finally {
                                lock.unlock();

                            }
                        }
                        if (breakAfterAcquireFailure) {
                            break;
                        }
                        try {
                            Thread.sleep(timeBetweenConnectErrorMillis);

                        } catch (InterruptedException interruptedException) {
                            break;
                        }
                    }
                } catch (RuntimeException runtimeException) {
                    setFailContinuous(true);
                    continue;
                } catch (Error error) {
                    setFailContinuous(true);
                    break;

                }
                if (connectionInfo == null) {
                    continue;
                }
                boolean result = put(connectionInfo);

            }

        }

    }

    protected boolean put(PhysicalConnectionInfo physicalConnectionInfo) {
        DruidConnectionHolder holder = null;
        try {
            holder = new DruidConnectionHolder(MyDruidDataSource.this, physicalConnectionInfo);

        } catch (SQLException sqlException) {
            lock.lock();
            try{
                if (createScheduler != null) {
                    clearCreateTask(physicalConnectionInfo.createTaskId);
                }
            }finally{
                lock.unlock();

            }
            return false;
        }
        return put(holder, physicalConnectionInfo.createTaskId);
    }

    private boolean put(DruidConnectionHolder holder, long createTaskId) {
        lock.lock();
        try{
            if (this.closed || this.closing) {
                return false;
            }
            if (poolingCount > maxActive) {
                if (createScheduler != null) {
                    clearCreateTask(createTaskId);
                }
                return false;
            }
            connections[poolingCount] = holder;
            incrementPoolingCount();
            if (poolingCount > poolingPeak) {
                poolingPeak = poolingCount;
                poolingPeakTime = System.currentTimeMillis();
            }
            //告诉其他线程存在connection可用
            notEmpty.signal();
            notEmptySignalCount++;
            if (createScheduler != null) {
                clearCreateTask(createTaskId);
                if (poolingCount + createTaskCount < notEmptyWaitThreadCount && activeCount + poolingCount + createTaskCount < maxActive) {
                    emptySignal();

                }
            }
        }finally{
            lock.unlock();

        }
        return true;
    }

    private final void incrementPoolingCount() {
        poolingCount++;
    }
    private final void decrementPoolingCount{
        poolingCount--;
    }
}
