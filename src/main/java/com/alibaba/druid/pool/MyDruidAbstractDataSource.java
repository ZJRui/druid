package com.alibaba.druid.pool;

import com.alibaba.druid.DbType;
import com.alibaba.druid.filter.Filter;
import com.alibaba.druid.filter.FilterChainImpl;
import com.alibaba.druid.proxy.jdbc.DataSourceProxy;
import com.alibaba.druid.stat.JdbcDataSourceStat;
import com.alibaba.druid.support.logging.Log;
import com.alibaba.druid.support.logging.LogFactory;
import com.alibaba.druid.util.DruidPasswordCallback;
import com.alibaba.druid.util.Histogram;
import com.alibaba.druid.util.JdbcUtils;
import lombok.Data;

import javax.management.ObjectName;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.Serializable;
import java.sql.*;
import java.util.*;
import java.util.Date;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

@Data
public class MyDruidAbstractDataSource   extends WrapperAdapter implements DruidAbstractDataSourceMBean, DataSource, DataSourceProxy, Serializable {
    private static final long                          serialVersionUID                          = 1L;
    private final static Log LOG                                       = LogFactory.getLog(DruidAbstractDataSource.class);

    public final static int                            DEFAULT_INITIAL_SIZE                      = 0;
    public final static int                            DEFAULT_MAX_ACTIVE_SIZE                   = 8;
    public final static int                            DEFAULT_MAX_IDLE                          = 8;
    public final static int                            DEFAULT_MIN_IDLE                          = 0;
    public final static int                            DEFAULT_MAX_WAIT                          = -1;
    public final static String                         DEFAULT_VALIDATION_QUERY                  = null;                                                //
    public final static boolean                        DEFAULT_TEST_ON_BORROW                    = false;
    public final static boolean                        DEFAULT_TEST_ON_RETURN                    = false;
    public final static boolean                        DEFAULT_WHILE_IDLE                        = true;
    public static final long                           DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS = 60 * 1000L;
    public static final long                           DEFAULT_TIME_BETWEEN_CONNECT_ERROR_MILLIS = 500;
    public static final int                            DEFAULT_NUM_TESTS_PER_EVICTION_RUN        = 3;

    public static final long                           DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS    = 1000L * 60L * 30L;
    public static final long                           DEFAULT_MAX_EVICTABLE_IDLE_TIME_MILLIS    = 1000L * 60L * 60L * 7;
    public static final long                           DEFAULT_PHY_TIMEOUT_MILLIS                = -1;

    protected volatile boolean                         defaultAutoCommit                         = true;
    protected volatile Boolean                         defaultReadOnly;
    protected volatile Integer                         defaultTransactionIsolation;
    protected volatile String                          defaultCatalog                            = null;

    protected String                                   name;

    protected volatile String                          username;
    protected volatile String                          password;
    protected volatile String                          jdbcUrl;
    protected volatile String                          driverClass;
    protected volatile ClassLoader                     driverClassLoader;
    protected volatile Properties connectProperties                         = new Properties();

    protected volatile PasswordCallback passwordCallback;
    protected volatile NameCallback userCallback;

    protected volatile int                             initialSize                               = DEFAULT_INITIAL_SIZE;
    protected volatile int                             maxActive                                 = DEFAULT_MAX_ACTIVE_SIZE;
    protected volatile int                             minIdle                                   = DEFAULT_MIN_IDLE;
    protected volatile int                             maxIdle                                   = DEFAULT_MAX_IDLE;
    protected volatile long                            maxWait                                   = DEFAULT_MAX_WAIT;
    protected int                                      notFullTimeoutRetryCount                  = 0;

    protected volatile String                          validationQuery                           = DEFAULT_VALIDATION_QUERY;
    protected volatile int                             validationQueryTimeout                    = -1;
    protected volatile boolean                         testOnBorrow                              = DEFAULT_TEST_ON_BORROW;
    protected volatile boolean                         testOnReturn                              = DEFAULT_TEST_ON_RETURN;
    protected volatile boolean                         testWhileIdle                             = DEFAULT_WHILE_IDLE;
    protected volatile boolean                         poolPreparedStatements                    = false;
    protected volatile boolean                         sharePreparedStatements                   = false;
    protected volatile int                             maxPoolPreparedStatementPerConnectionSize = 10;

    protected volatile boolean                         inited                                    = false;
    protected volatile boolean                         initExceptionThrow                        = true;

    protected PrintWriter logWriter                                 = new PrintWriter(System.out);

    protected List<Filter> filters                                   = new CopyOnWriteArrayList<Filter>();
    private boolean                                    clearFiltersEnable                        = true;
    protected volatile ExceptionSorter exceptionSorter                           = null;

    protected Driver driver;

    protected volatile int                             queryTimeout;
    protected volatile int                             transactionQueryTimeout;

    protected long                                     createTimespan;

    protected volatile int                             maxWaitThreadCount                        = -1;
    protected volatile boolean                         accessToUnderlyingConnectionAllowed       = true;

    protected volatile long                            timeBetweenEvictionRunsMillis             = DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS;
    protected volatile int                             numTestsPerEvictionRun                    = DEFAULT_NUM_TESTS_PER_EVICTION_RUN;
    protected volatile long                            minEvictableIdleTimeMillis                = DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS;
    protected volatile long                            maxEvictableIdleTimeMillis                = DEFAULT_MAX_EVICTABLE_IDLE_TIME_MILLIS;
    protected volatile long                            keepAliveBetweenTimeMillis                = DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS * 2;
    protected volatile long                            phyTimeoutMillis                          = DEFAULT_PHY_TIMEOUT_MILLIS;
    protected volatile long                            phyMaxUseCount                            = -1;

    protected volatile boolean                         removeAbandoned;
    protected volatile long                            removeAbandonedTimeoutMillis              = 300 * 1000;
    protected volatile boolean                         logAbandoned;

    protected volatile int                             maxOpenPreparedStatements                 = -1;

    protected volatile List<String> connectionInitSqls = new ArrayList<>();

    protected volatile String                          dbTypeName;

    protected volatile long                            timeBetweenConnectErrorMillis             = DEFAULT_TIME_BETWEEN_CONNECT_ERROR_MILLIS;

    protected volatile ValidConnectionChecker          validConnectionChecker                    = null;

    protected final Map<DruidPooledConnection, Object> activeConnections                         = new IdentityHashMap<DruidPooledConnection, Object>();
    protected final static Object                      PRESENT                                   = new Object();

    protected long                                     id;

    protected int                                      connectionErrorRetryAttempts              = 1;
    protected boolean                                  breakAfterAcquireFailure                  = false;
    protected long                                     transactionThresholdMillis                = 0L;

    protected final Date createdTime                               = new Date();
    protected Date                                     initedTime;
    protected volatile long                            errorCount                                = 0L;
    protected volatile long                            dupCloseCount                             = 0L;
    protected volatile long                            startTransactionCount                     = 0L;
    protected volatile long                            commitCount                               = 0L;
    protected volatile long                            rollbackCount                             = 0L;
    protected volatile long                            cachedPreparedStatementHitCount           = 0L;
    protected volatile long                            preparedStatementCount                    = 0L;
    protected volatile long                            closedPreparedStatementCount              = 0L;
    protected volatile long                            cachedPreparedStatementCount              = 0L;
    protected volatile long                            cachedPreparedStatementDeleteCount        = 0L;
    protected volatile long                            cachedPreparedStatementMissCount          = 0L;

    final static AtomicLongFieldUpdater<MyDruidAbstractDataSource> errorCountUpdater                         = AtomicLongFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "errorCount");
    final static AtomicLongFieldUpdater<MyDruidAbstractDataSource> dupCloseCountUpdater                      = AtomicLongFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "dupCloseCount");
    final static AtomicLongFieldUpdater<MyDruidAbstractDataSource> startTransactionCountUpdater              = AtomicLongFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "startTransactionCount");
    final static AtomicLongFieldUpdater<MyDruidAbstractDataSource> commitCountUpdater                        = AtomicLongFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "commitCount");
    final static AtomicLongFieldUpdater<MyDruidAbstractDataSource> rollbackCountUpdater                      = AtomicLongFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "rollbackCount");
    final static AtomicLongFieldUpdater<MyDruidAbstractDataSource> cachedPreparedStatementHitCountUpdater    = AtomicLongFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "cachedPreparedStatementHitCount");
    final static AtomicLongFieldUpdater<MyDruidAbstractDataSource> preparedStatementCountUpdater             = AtomicLongFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "preparedStatementCount");
    final static AtomicLongFieldUpdater<MyDruidAbstractDataSource> closedPreparedStatementCountUpdater       = AtomicLongFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "closedPreparedStatementCount");
    final static AtomicLongFieldUpdater<MyDruidAbstractDataSource> cachedPreparedStatementCountUpdater       = AtomicLongFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "cachedPreparedStatementCount");
    final static AtomicLongFieldUpdater<MyDruidAbstractDataSource> cachedPreparedStatementDeleteCountUpdater = AtomicLongFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "cachedPreparedStatementDeleteCount");
    final static AtomicLongFieldUpdater<MyDruidAbstractDataSource> cachedPreparedStatementMissCountUpdater   = AtomicLongFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "cachedPreparedStatementMissCount");


    protected final Histogram transactionHistogram                      = new Histogram(1,
            10,
            100,
            1000,
            10 * 1000,
            100 * 1000);

    private boolean                                    dupCloseLogEnable                         = false;

    private ObjectName objectName;

    protected volatile long                            executeCount                              = 0L;
    protected volatile long                            executeQueryCount                         = 0L;
    protected volatile long                            executeUpdateCount                        = 0L;
    protected volatile long                            executeBatchCount                         = 0L;

    final static AtomicLongFieldUpdater<MyDruidAbstractDataSource> executeQueryCountUpdater = AtomicLongFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "executeQueryCount");
    final static AtomicLongFieldUpdater<MyDruidAbstractDataSource> executeUpdateCountUpdater = AtomicLongFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "executeUpdateCount");
    final static AtomicLongFieldUpdater<MyDruidAbstractDataSource> executeBatchCountUpdater = AtomicLongFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "executeBatchCount");
    final static AtomicLongFieldUpdater<MyDruidAbstractDataSource> executeCountUpdater = AtomicLongFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "executeCount");

    protected volatile Throwable                       createError;
    protected volatile Throwable                       lastError;
    protected volatile long                            lastErrorTimeMillis;
    protected volatile Throwable                       lastCreateError;
    protected volatile long                            lastCreateErrorTimeMillis;
    protected volatile long                            lastCreateStartTimeMillis;

    protected boolean                                  isOracle                                  = false;
    protected boolean                                  isMySql                                   = false;
    protected boolean                                  useOracleImplicitCache                    = true;

    protected ReentrantLock lock;
    protected Condition notEmpty;
    protected Condition                                empty;

    protected ReentrantLock                            activeConnectionLock                      = new ReentrantLock();

    protected volatile int                             createErrorCount                          = 0;
    protected volatile int                             creatingCount                             = 0;
    protected volatile int                             directCreateCount                         = 0;
    protected volatile long                            createCount                               = 0L;
    protected volatile long                            destroyCount                              = 0L;
    protected volatile long                            createStartNanos                          = 0L;

    final static AtomicIntegerFieldUpdater<MyDruidAbstractDataSource> createErrorCountUpdater      = AtomicIntegerFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "createErrorCount");
    final static AtomicIntegerFieldUpdater<MyDruidAbstractDataSource> creatingCountUpdater         = AtomicIntegerFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "creatingCount");
    final static AtomicIntegerFieldUpdater<MyDruidAbstractDataSource> directCreateCountUpdater     = AtomicIntegerFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "directCreateCount");
    final static AtomicLongFieldUpdater<MyDruidAbstractDataSource>    createCountUpdater           = AtomicLongFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "createCount");
    final static AtomicLongFieldUpdater<MyDruidAbstractDataSource>    destroyCountUpdater          = AtomicLongFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "destroyCount");
    final static AtomicLongFieldUpdater<MyDruidAbstractDataSource> createStartNanosUpdater         = AtomicLongFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "createStartNanos");

    private Boolean                                    useUnfairLock                             = null;
    private boolean                                    useLocalSessionState                      = true;

    protected long                                     timeBetweenLogStatsMillis;
    protected DruidDataSourceStatLogger statLogger                                = new DruidDataSourceStatLoggerImpl();

    private boolean                                    asyncCloseConnectionEnable                = false;
    protected int                                      maxCreateTaskCount                        = 3;
    protected boolean                                  failFast                                  = false;
    protected volatile int                             failContinuous                            = 0;
    protected volatile long                            failContinuousTimeMillis                  = 0L;
    protected ScheduledExecutorService destroyScheduler;
    protected ScheduledExecutorService                 createScheduler;

    final static AtomicLongFieldUpdater<MyDruidAbstractDataSource> failContinuousTimeMillisUpdater = AtomicLongFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "failContinuousTimeMillis");
    final static AtomicIntegerFieldUpdater<MyDruidAbstractDataSource> failContinuousUpdater        = AtomicIntegerFieldUpdater.newUpdater(MyDruidAbstractDataSource.class, "failContinuous");

    protected boolean                                  initVariants                              = false;
    protected boolean                                  initGlobalVariants                        = false;
    protected volatile boolean                         onFatalError                              = false;
    protected volatile int                             onFatalErrorMaxActive                     = 0;
    protected volatile int                             fatalErrorCount                           = 0;
    protected volatile int                             fatalErrorCountLastShrink                 = 0;
    protected volatile long                            lastFatalErrorTimeMillis                  = 0;
    protected volatile String                          lastFatalErrorSql                         = null;
    protected volatile Throwable                       lastFatalError                            = null;
    public void closePreapredStatement(PreparedStatementHolder preparedStatementHolder) {

        if (preparedStatementHolder == null) {
            return;
        }
        closedPreparedStatementCountUpdater.incrementAndGet(this);
        decrementCachedPreparedStatementCount();
        incrementCachedPreparedStatementDeleteCount();


        JdbcUtils.close(preparedStatementHolder.statement);
    }

    public void decrementCachedPreparedStatementCount() {
        cachedPreparedStatementCountUpdater.decrementAndGet(this);
    }

    public void incrementCachedPreparedStatementDeleteCount() {
        cachedPreparedStatementDeleteCountUpdater.incrementAndGet(this);
    }

//    PhysicalConnectionInfo pyConnectInfo = createPhysicalConnection();
//


    DruidAbstractDataSource.PhysicalConnectionInfo createPhysicalConnection() throws  SQLException{
        String url = this.getJdbcUrl();
        Properties connectProperties = getConnectProperties();
        String user;
        if (getUserCallback() != null) {
            user = getUserCallback().getName();
        }else{
            user = getUsername();
        }
        String password = getPassword();
        PasswordCallback passwordCallback = getPasswordCallback();

        if (passwordCallback != null) {
            if (passwordCallback instanceof DruidPasswordCallback) {
                DruidPasswordCallback druidPasswordCallback = (DruidPasswordCallback) passwordCallback;

                druidPasswordCallback.setUrl(url);
                druidPasswordCallback.setProperties(connectProperties);
            }

            char[] chars = passwordCallback.getPassword();
            if (chars != null) {
                password = new String(chars);
            }
        }

        Properties physicalConnectProperties = new Properties();
        if (connectProperties != null) {
            physicalConnectProperties.putAll(connectProperties);
        }

        if (user != null && user.length() != 0) {
            physicalConnectProperties.put("user", user);
        }

        if (password != null && password.length() != 0) {
            physicalConnectProperties.put("password", password);
        }

        Connection conn = null;
        long connectStartNanoTime = System.nanoTime();
        long connectedNanos,initedNanos,validatedNanos;
        Map<String, Object> variables = initVariants ? new HashMap<>() : null;
        Map<String, Object> globalVariables = initGlobalVariants ? new HashMap<>() : null;
        createStartNanosUpdater.set(this, connectStartNanoTime);
        creatingCountUpdater.incrementAndGet(this);
        try {
            conn = createPhysicalConnection(url, physicalConnectProperties);
            connectedNanos = System.nanoTime();
            if (conn == null) {
                throw new SQLException("Connect error ,url " + url + ",driverClass" + this.driverClass);
            }
            initPhysicalConnection(conn, variables, globalVariables);
            initedNanos = System.nanoTime();


            validateConnection(conn);
            validatedNanos = System.nanoTime();


            setFailContinuous(false);
            setCreateError(null);

        } catch (SQLException exception) {
            setCreateError(exception);
            JdbcUtils.close(conn);
            throw exception;
        } catch (RuntimeException exception) {
            setCreateError(exception);
            JdbcUtils.close(conn);
            throw exception;
        } catch (Error error) {
            createErrorCountUpdater.incrementAndGet(this);
            setCreateError(error);
            JdbcUtils.close(conn);
            throw error;
        }finally{
            long nano = System.nanoTime() - connectStartNanoTime;
            createTimespan += nano;
            createCountUpdater.decrementAndGet(this);
        }
        return new DruidAbstractDataSource.PhysicalConnectionInfo(conn, connectStartNanoTime, initedNanos, validatedNanos, variables, globalVariables);


    }
    protected void setFailContinuous(boolean fail) {
        if (fail) {
            failContinuousTimeMillisUpdater.set(this, System.currentTimeMillis());
        } else {
            failContinuousTimeMillisUpdater.set(this, 0L);
        }

        boolean currentState = failContinuousUpdater.get(this) == 1;
        if (currentState == fail) {
            return;
        }

        if (fail) {
            failContinuousUpdater.set(this, 1);
            if (LOG.isInfoEnabled()) {
                LOG.info("{dataSource-" + this.getID() + "} failContinuous is true");
            }
        } else {
            failContinuousUpdater.set(this, 0);
            if (LOG.isInfoEnabled()) {
                LOG.info("{dataSource-" + this.getID() + "} failContinuous is false");
            }
        }
    }
    public long getID() {
        return this.id;
    }

    public void validateConnection(Connection connection) throws  SQLException {
        String query = getValidationQuery();
        if (connection.isClosed()) {
            throw new SQLException("validateConnection :connection closed");
        }
        if (validConnectionChecker != null) {
            boolean result;
            Exception error = null;
            try {
                result=validConnectionChecker.isValidConnection(connection, validationQuery, validationQueryTimeout);
                if (result && onFatalError) {
                    lock.lock();
                    try{
                        if (onFatalError) {
                            onFatalError=false;

                        }
                    }finally{
                        lock.unlock();
                    }
                }
            } catch (SQLException e) {
                throw e;
            } catch (Exception e) {
                result=false;
                error = e;
            }
            if (!result) {
                SQLException sqlError=error!=null:new SQLException("validateConnection false" ,error):
                new SQLException("validateConnection false");
                throw sqlError;
            }
            return ;
        }
        if (null != query) {
            Statement stmt = null;
            ResultSet rs = null;
            try {
                stmt = connection.createStatement();
                if (getValidationQueryTimeout() > 0) {
                    stmt.setQueryTimeout(getValidationQueryTimeout());
                }
                rs = stmt.executeQuery(query);
                if (!rs.next()) {
                    throw new SQLException("validationQuery didn't return a row");
                }

                if (onFatalError) {
                    lock.lock();
                    try {
                        if (onFatalError) {
                            onFatalError = false;
                        }
                    }
                    finally {
                        lock.unlock();
                    }
                }
            } finally {
                JdbcUtils.close(rs);
                JdbcUtils.close(stmt);
            }
        }


    }
    public void initPhysicalConnection(Connection connection,Map<String,Object>variables,Map<String,Object>globalVariables)throws  SQLException{
        if (connection.getAutoCommit() != defaultAutoCommit) {
            connection.setAutoCommit(defaultAutoCommit);
        }
        if (defaultReadOnly!=null) {
            if (connection.isReadOnly() != defaultReadOnly) {
                connection.setReadOnly(defaultReadOnly);

            }
        }
        if (getDefaultTransactionIsolation() != null) {
            if (connection.getTransactionIsolation() != getDefaultTransactionIsolation().intValue()) {
                connection.setTransactionIsolation(getDefaultTransactionIsolation());
            }
        }
        if (getDefaultCatalog() != null && getDefaultCatalog().length() != 0) {
            connection.setCatalog(getDefaultCatalog());

        }
        Collection<String> initSqls = getConnectionInitSqls();
        if (initSqls.size() == 0 && variables == null && globalVariables == null) {
            return;
        }
        Statement statement=null;
        try{
            statement = connection.createStatement();
            for (String sql : initSqls) {
                if (sql == null) {
                    continue;
                }
                statement.execute(sql);
            }
            DbType dbType = DbType.of(this.dbTypeName);
            if (dbType == DbType.mysql || dbType == DbType.ads) {
                if (variables != null) {
                    ResultSet rs = null;
                    try{
                        rs=statement.executeQuery("show variables");
                        while (rs.next()) {
                            String name = rs.getString(1);
                            Object value = rs.getObject(2);
                            variables.put(name, value);
                        }
                    }finally{
                        JdbcUtils.close(rs);
                    }

                }

                if (globalVariables != null) {
                    ResultSet rs = null;
                    try{
                        rs = statement.executeQuery("show global variables");
                        while (rs.next()) {
                            String name = rs.getString(1);
                            Object value = rs.getObject(2);
                            globalVariables.put(name, value);
                        }

                    }finally{
                        JdbcUtils.close(rs);
                    }
                }
            }//end if



        }finally {
            JdbcUtils.close(statement);
        }

    }



   public  Connection createPhysicalConnection(String url, Properties properties)throws SQLException {
       Connection connection;
       if(getProxyFilters().size()==0){
           connection = getDriver().connect(url, properties);
       }else{
           connection = new FilterChainImpl(this).connection_connect(properties);
       }
       createCountUpdater.incrementAndGet(this);
       return connection;
    }

    @Override
    public List<Filter> getProxyFilters() {
        return filters;
    }

    @Override
    public long createConnectionId() {
        return 0;
    }

    @Override
    public long createStatementId() {
        return 0;
    }

    @Override
    public long createResultSetId() {
        return 0;
    }

    @Override
    public long createMetaDataId() {
        return 0;
    }

    @Override
    public long createTransactionId() {
        return 0;
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {

    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return null;
    }

    @Override
    public JdbcDataSourceStat getDataSourceStat() {
        return null;
    }

    @Override
    public String getDbType() {
        return null;
    }

    @Override
    public Driver getRawDriver() {
        return null;
    }

    @Override
    public String getUrl() {
        return null;
    }

    @Override
    public String getRawJdbcUrl() {
        return null;
    }

    @Override
    public String getDriverClassName() {
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
    public long getCreateTimespanMillis() {
        return 0;
    }

    @Override
    public List<String> getActiveConnectionStackTrace() {
        return null;
    }

    @Override
    public List<String> getFilterClassNames() {
        return null;
    }

    @Override
    public long getRemoveAbandonedCount() {
        return 0;
    }

    @Override
    public String getProperties() {
        return null;
    }

    @Override
    public int getRawDriverMinorVersion() {
        return 0;
    }

    @Override
    public int getRawDriverMajorVersion() {
        return 0;
    }

    @Override
    public String getValidConnectionCheckerClassName() {
        return null;
    }

    @Override
    public long[] getTransactionHistogramValues() {
        return new long[0];
    }

    @Override
    public long getCachedPreparedStatementAccessCount() {
        return 0;
    }

    @Override
    public int getDriverMajorVersion() {
        return 0;
    }

    @Override
    public int getDriverMinorVersion() {
        return 0;
    }

    @Override
    public String getExceptionSorterClassName() {
        return null;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return null;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return null;
    }
}

