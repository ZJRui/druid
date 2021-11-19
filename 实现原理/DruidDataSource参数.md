

# druid-数据库连接池监控

# 背景

1.生产环境有时候会发生数据库连接池的问题
2.连接池数量调优
连接池数量不是越大越好，因为连接太多，数据库服务器也处理不过来，cpu就那么几个，所以一般建议配置数量为cpu*2 + 1。具体效果，可以通过监控来调优。

# 配置

添加监控过滤器配置

```
spring.datasource.druid.mer.filters = mergeStat //统计监控过滤器
复制代码
```

# 写监控信息到日志

添加写日志配置

```
spring.datasource.druid.mer.time-between-log-stats-millis=300000 //每间隔5分钟写监控信息到日志
复制代码
```

------

说明

日志输出是通过定时调用DruidDataSourceStatLogger.log(DruidDataSourceStatValue)方法实现的。目前文档不完全，大家先看代码 [github.com/alibaba/dru…](https://link.juejin.cn/?target=https%3A%2F%2Fgithub.com%2Falibaba%2Fdruid%2Fblob%2Fmaster%2Fsrc%2Fmain%2Fjava%2Fcom%2Falibaba%2Fdruid%2Fpool%2FDruidDataSourceStatLoggerImpl.java%3Fsource%3Dcc)

```
 com.alibaba.druid.pool.DruidDataSourceStatLoggerImpl
复制代码
```

[github.com/alibaba/dru…](https://link.juejin.cn/?target=https%3A%2F%2Fgithub.com%2Falibaba%2Fdruid%2Fwiki%2F%E5%AE%9A%E6%97%B6%E8%BE%93%E5%87%BA%E7%BB%9F%E8%AE%A1%E4%BF%A1%E6%81%AF%E5%88%B0%E6%97%A5%E5%BF%97%E4%B8%AD)

# 字段说明

示例

```
2020-06-23 19:52:15.128|INFO |uFLfKvgpNt2t-57-78|com.alibaba.druid.pool.DruidDataSourceStatLoggerImpl.log:77||
{"url":"jdbc:oracle:thin:@paid-m.uatdb.com:1525:paid", 
"dbType":"oracle",
"name":"D*", //数据源名字"Name": "DataSource-1606272155",

"activeCount":0, //activeCount 是当前正在被使用的连接数量，这部分已经被用户拿去使用还没归还。poolingCount + activeCount 的数量才是 druid 拥有的所有连接数量。
"activePeak":47 //activeCount正在使用的连接数量，peak是activeCount的峰值
"activePeakTime":"2020-06-23 19:48:36", //活跃最高峰时间

"poolingCount":50," //当前连接池中数据库连接的数量
poolingPeak":50 //池的最高峰数量
"poolingPeakTime":"2020-06-23 19:47:17", //池的最高峰时间

"connectCount":112, //连接数量
"closeCount":112, //关闭连接数量
"executeCount":112, //执行数量

"notEmptyWaitCount":10, //阻塞等待线程数量。两个有用的字段：notEmptyWaitThreadCount这个字段也就是消费者数量，notEmptyWaitCount维护的就是正在等待的消费者数量，可以在代码里查看，每次在pollLast方法里陷入等待前会把属性notEmptyWaitThreadCount进行累加，阻塞结束后会递减，由此可见notEmptyWaitThreadCount就是表示当前等待可用连接时阻塞的业务线程的总个数。

"transactionHistogram":[0,31,64,17], //事务运行时间分布，分布区间为[0-10 ms, 10-100 ms, 100-1 s, 1-10 s, 10-100 s, >100 s],这个值是一个数组，数值的索引位的含义如上述，第几索引上的数据就代表在这个时间区间内包含的事务数。当前情况是，0到10ms，0个事务；10到100ms，31个事务；100到1s，64个事务；1到10s，17个事务。
"connectionHoldTimeHistogram":[0,1,47,64], //连接持有时间分布，分布区间为[0-1 ms, 1-10 ms, 10-100 ms, 100ms-1s, 1-10 s, 10-100 s, 100-1000 s, >1000 s]，这个值是一个数组，数值的索引位的含义如上述，第几索引上的数据就代表在这个时间区间内包含的连接数。

"sqlList":[{"sql":"select * from 表 where id=?", //sql集合

"executeCount":112, //执行数量
"executeMillisMax":62, //最大执行时间
"executeMillisTotal":994, //总共执行时间

"executeHistogram":[0,87,25],
"executeAndResultHoldHistogram":[112],
"concurrentMax":7, //最大并发数量(sql粒度)
"fetchRowCount":1, //查询结果记录数量
"fetchRowHistogram":[0,112],
"inTransactionCount":112}]
}
复制代码
```

------

```
数据返回是一个json格式的，说一下各个参数的含义吧，只说关键的监控字段

参数	值（栗子中的值）	含义
ActiveCount	0	当前连接池中活跃连接数
ActivePeak	1	连接池中活跃连接数峰值
ActivePeakTime	2020/4/13 16:12	活跃连接池峰值出现的时间
BlobOpenCount	0	Blob打开数
ClobOpenCount	0	Clob打开数
CommitCount	0	提交数
ConnectionHoldTimeHistogram	0,0,0,0,0,0,0,0	连接持有时间分布，分布区间为[0-1 ms, 1-10 ms, 10-100 ms, 100ms-1s, 1-10 s, 10-100 s, 100-1000 s, >1000 s]，这个值是一个数组，数值的索引位的含义如上述，第几索引上的数据就代表在这个时间区间内包含的连接数
ErrorCount	0	错误数
ExecuteCount	0	执行数
InitialSize	60	连接池建立时创建的初始化连接数
LogicCloseCount	2	产生的逻辑连接关闭总数
LogicConnectCount	2	产生的逻辑连接建立总数
LogicConnectErrorCount	0	产生的逻辑连接出错总数
LoginTimeout	0	数据库客户端登录超时时间
MaxActive	200	连接池中最大的活跃连接数
MinIdle	120	连接池中最小的活跃连接数
NotEmptyWaitCount	0	获取连接时最多等待多少次
NotEmptyWaitMillis	0	获取连接时最多等待多长时间，毫秒为单位
PSCacheAccessCount	0	PSCache访问总数
PSCacheHitCount	0	PSCache命中次数
PSCacheMissCount	0	PSCache未命中次数
PhysicalCloseCount	0	产生的物理关闭总数
PhysicalConnectCount	60	产生的物理连接建立总数
PhysicalConnectErrorCount	0	产生的物理连接失败总数
PoolingCount	60	当前连接池中的连接数
PoolingPeak	60	连接池中连接数的峰值
PoolingPeakTime	2020/4/13 16:12	连接池数目峰值出现的时间
QueryTimeout	0	查询超时数
RollbackCount	0	回滚数
StartTransactionCount	0	事务开始的个数
TransactionHistogram	0,0,0,0,0,0,0,0	事务运行时间分布，分布区间为[0-10 ms, 10-100 ms, 100-1 s, 1-10 s, 10-100 s, >100 s],这个值是一个数组，数值的索引位的含义如上述，第几索引上的数据就代表在这个时间区间内包含的事务数
TransactionQueryTimeout	0	事务查询超时数
WaitThreadCount	0	当前等待获取连接的线程数
复制代码
```

使用druid的监控组件进行数据库连接池的监控 [mp.weixin.qq.com/s?src=11&ti…](https://link.juejin.cn/?target=https%3A%2F%2Fmp.weixin.qq.com%2Fs%3Fsrc%3D11%26timestamp%3D1593574768%26ver%3D2433%26signature%3DqfJoNH7pdVSZbi7AmZy6KNwY6vaRGN1JQ7bJui06yjIN4QM-d3y-ks6JYdxvq8A-EbGoTLJbfkBvmRNxXBbJkXipDDHeGDtdP3LabG*bWIo0jCHEiNVcsHH8RI68ryI6%26new%3D1)

------

阻塞数量

一、notEmptyWaitCount(累计数据)

```
NotEmptyWaitCount	10	获取连接时最多等待多少次 //获取数据库连接的时候，由于数据库连接池满，连接池里没有连接可用，这个时候会阻塞，累计阻塞请求数量(比如，5分钟内，累计有10个请求阻塞了，这个值就是10)
NotEmptyWaitMillis	0	获取连接时最多等待多长时间，毫秒为单位 //阻塞时间
复制代码
```

代码逻辑也比较清楚，poolCount是连接池的目前的可用连接数量。

1.如果为0，就通过emptySignal唤醒生产者线程创建新的连接，同时当前线程挂起等待notEmpty的信号。notEmptyWaitCount维护的就是正在等待的消费者数量。

2.如果不为0，就从数组中取出最后一个连接返回。

```
数据库连接池的原理没你想得这么复杂
https://mp.weixin.qq.com/s?src=11&timestamp=1593585430&ver=2433&signature=Q*XsrGZKDhpOQnhV4WknmUBVz9lru2z*jxGnC8n0aqZ9wZKx5RsaLxvqSm1B9KprFEP40uyZMP7n3W*bIM45yoPaNKhjzFAjiyxdCs5HLxeBQWb-8b5VEcxwwOX-Slog&new=1
复制代码
```

二、WaitThreadCount(瞬时数据)

```
WaitThreadCount	0	当前等待获取连接的线程数(最后一次更新统计数据时的数据)
复制代码
```

# 总结

1.监控时间间隔

每隔5分钟，写监控数据到日志。

有sql请求的时候，会更新统计数据。

在5分钟间隔内，druid会每间隔很短的时间，更新一次统计数据。//所以，瞬时数据在写日志的时候可能为0，因为后面的统计是在没有请求的时候统计的，所以瞬时数据当前值为0，然后就把瞬时数据自己之前的值(比如，最高峰时的值)覆盖了。

2.监控数据说明

有的是统计时的当前数据(瞬时数据)。 //比如，activeCount(正在被使用的连接数量)。

有的是统计期间的最高峰数据。//比如，activePeak(activeCount的峰值)、poolingPeak(poolingCount的峰值)

有的数据是5分钟累计的数据，主要包含
1）所有sql的数量统计
2）单个sql的数量统计

