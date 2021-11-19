# Druid配置参数详解-removeAbandoned，logAbandoned，removeAbandonedTimeoutMillis



> Druid是一个由阿里开源的数据库连接池，Druid的配置非常丰富，但是设置不当会对生产环境造成严重影响，网上Druid的资料虽多，但大部分都是互相复制粘贴，有很多不准确甚至完全错误的描述，Druid已经开源很久，而且作者WenShao的工作重心也已经不在Druid上，有些功能估计他自己都不太了解了。本系列将从源代码的角度分析Druid目前的最新版本（1.1.21）各个常用的配置项的具体含义以及是怎么起作用的。

*画外音：目前Druid在开源中国举办的2019年度最受欢迎中国开源软件中排名第7名，支持Druid的朋友可以去投票哇。*[2019年度最受欢迎中国开源软件](https://links.jianshu.com/go?to=https%3A%2F%2Fwww.oschina.net%2Fproject%2Ftop_cn_2019)

## removeAbandoned，logAbandoned，removeAbandonedTimeoutMillis是什么意思？

**removeAbandoned**：如果连接泄露，是否需要回收泄露的连接，默认false；
 **logAbandoned**：如果回收了泄露的连接，是否要打印一条log，默认false；
 **removeAbandonedTimeoutMillis**：连接回收的超时时间，默认5分钟；

*画外音：这两个参数笔者认为非常重要，但是不知为何，Druid默认是不开启的，并且官方的配置例子中也没有配置这两个参数：[Druid官方配置](https://links.jianshu.com/go?to=https%3A%2F%2Fgithub.com%2Falibaba%2Fdruid%2Fwiki%2FDruidDataSource%E9%85%8D%E7%BD%AE)*，*笔者就因为没有配置这两个参数，曾经吃过大亏*。

## removeAbandoned,logAbandoned，removeAbandonedTimeoutMillis有什么用？

举个栗子：笔者当初做过单个事务使用多数据源的功能，重写了Spring的ConnectionHolder，由于笔者的疏忽，每次从ConnectionHolder中获取连接时都获取到了两条连接，但是只是使用了其中的一条，相当于另一条连接只是从连接池中拿出来了，但是再也不会还回去了，这样就导致了连接池中的连接很快就消耗光了，即activeCount=maxActive。

如果当时我设置了removeAbandoned就不会出现这个问题，应该Druid会定期检查池中借出去的连接是否处于运行状态，如果不是处于运行状态，并且借出时间超过**removeAbandonedTimeoutMillis**（默认5分钟）就会回收该连接。

## 连接是怎么回收的？

Druid每隔**timeBetweenEvictionRunsMillis**（默认1分钟）会调用DestroyTask，在这里会判断是否可以回收泄露的连接



```java
    public class DestroyTask implements Runnable {
        public DestroyTask() {

        }

        @Override
        public void run() {
            shrink(true, keepAlive);
            //判断removeAbandoned是否为true，默认是false，不知为何
            if (isRemoveAbandoned()) {
                removeAbandoned();
            }
        }

    }
```



```java
            for (; iter.hasNext();) {
                DruidPooledConnection pooledConnection = iter.next();
                
                //判断该连接是否还在运行，只回收不运行的连接
                //Druid会在连接执行query,update的时候设置为正在运行，
                // 并在回收后设置为不运行
                if (pooledConnection.isRunning()) {
                    continue;
                }

                long timeMillis = (currrentNanos - pooledConnection.getConnectedTimeNano()) / (1000 * 1000);
                
                //判断连接借出去的时间大小
                if (timeMillis >= removeAbandonedTimeoutMillis) {
                    iter.remove();
                    pooledConnection.setTraceEnable(false);
                    abandonedList.add(pooledConnection);
                }
            }
```



```java
                //判断是否要记录连接回收日志，这个很重要，可以及时发现项目中是否有连接泄露
                if (isLogAbandoned()) {
                    StringBuilder buf = new StringBuilder();
                    buf.append("abandon connection, owner thread: ");
                    buf.append(pooledConnection.getOwnerThread().getName());
                    buf.append(", connected at : ");
                    buf.append(pooledConnection.getConnectedTimeMillis());
                    buf.append(", open stackTrace\n");
                }
```

## 总结

笔者认为这三个参数非常重要，但是官方默认是不开启的，建议使用Druid的项目都设置这三个参数，这样可以有效的发现代码中是否有连接泄露。







正常情况不应出现连接泄露；而且如果业务中确实存在长时间任务，可能被误杀。
而removeAbandonedTimeoutMillis是相对于getConnectedTime的，而不是最后一次执行时间。
也就是说，如果一个业务执行时间超过5分钟，而在DestroyTask检查的那个时间点，
代码恰好处于一次查询刚结束，还没开始更新的状态，isRunning=false，就会被误杀。
个人理解
