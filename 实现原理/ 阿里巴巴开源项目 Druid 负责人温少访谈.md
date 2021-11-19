Druid是阿里巴巴开源平台上的一个项目，整个项目由数据库连接池、插件框架和SQL解析器组成。该项目主要是为了扩展JDBC的一些限制，可以让程序员实现一些特殊的需求，比如向密钥服务请求凭证、统计SQL信息、SQL性能收集、SQL注入检查、SQL翻译等，程序员可以通过定制来实现自己需要的功能。

该项目在阿里巴巴内部得到了广泛的部署，在外部也有大量的用户群。为了使大家更好地了解和使用Druid，我们采访了Druid项目的主要负责人——温少（[博客](http://wenshao.iteye.com/)）。

#### 目 录 [[ - \]](https://www.iteye.com/magazines/90?page=2#)

1. [温少是ITeye的名人了，为了照顾新会员，先来个自我介绍吧！](https://www.iteye.com/magazines/90?page=2#110)
2. [Druid是什么？有什么作用？](https://www.iteye.com/magazines/90?page=2#111)
3. [Druid的项目背景？目前的项目团队情况？开源目的？](https://www.iteye.com/magazines/90?page=2#112)
4. [Druid支持哪些数据库？](https://www.iteye.com/magazines/90?page=2#113)
5. [Druid是如何扩展JDBC的?](https://www.iteye.com/magazines/90?page=2#114)
6. [为什么说Druid是“最好的数据库连接池”?体现在哪些方面？这是如何实现的？](https://www.iteye.com/magazines/90?page=2#115)
7. [Druid的性能如何？能否给出一些测试对比数据？](https://www.iteye.com/magazines/90?page=2#116)
8. [谈谈Druid的SQL解析功能？效率如何？](https://www.iteye.com/magazines/90?page=2#117)
9. [Druid的扩展性如何？](https://www.iteye.com/magazines/90?page=2#118)
10. [在SQL注入防御方面，Druid的优势是什么？实现原理是什么？](https://www.iteye.com/magazines/90?page=2#119)
11. [目前Druid的应用（部署）情况？](https://www.iteye.com/magazines/90?page=2#120)
12. [我想将其中的某个模块（比如监控模块）用到其他连接池，是否可以？模块的独立性如何？](https://www.iteye.com/magazines/90?page=2#121)
13. [我想在项目中使用，应该注意哪些事项？能否用于商业项目？](https://www.iteye.com/magazines/90?page=2#122)
14. [配置是否复杂？能否给出一个典型的配置实例？](https://www.iteye.com/magazines/90?page=2#123)
15. [我目前使用其他连接池（DBCP/C3P0/Proxool等），如何迁移到Druid？](https://www.iteye.com/magazines/90?page=2#124)
16. [其他开发者如何反馈问题、提交bug？](https://www.iteye.com/magazines/90?page=2#125)

## 温少是ITeye的名人了，为了照顾新会员，先来个自我介绍吧！[![Top](https://www.iteye.com/images/wiki/top.gif?1448702469)](https://www.iteye.com/magazines/90?page=2#top)

我2001年毕业于深圳大学，毕业后到金蝶软件研发中心工作9年，工作内容包括工作流引擎、多数据库支持引擎、短信网网关等。

2010年3月加入阿里巴巴至今，主要的工作是设计和实现阿里巴巴应用监控系统Dragoon，Druid和Fastjson都是监控系统实现的副产品。

## Druid是什么？有什么作用？[![Top](https://www.iteye.com/images/wiki/top.gif?1448702469)](https://www.iteye.com/magazines/90?page=2#top)

Druid首先是一个数据库连接池，但它不仅仅是一个数据库连接池，它还包含一个ProxyDriver，一系列内置的JDBC组件库，一个SQL Parser。

## Druid的项目背景？目前的项目团队情况？开源目的？[![Top](https://www.iteye.com/images/wiki/top.gif?1448702469)](https://www.iteye.com/magazines/90?page=2#top)

2010年开始，我负责设计一个叫做Dragoon的监控系统，需要一些监控组件，监控应用程序的运行情况，包括Web URI、Spring、JDBC等。为了监控SQL执行情况，我做了一个Filter-Chain模式的ProxyDriver，缺省提供StatFilter。当时我还做了一个SQL Parser。老板说，不如我们来一个更大的计划，把连接池、SQL Parser、Proxy Driver合起来做一个项目，命名为Druid，于是Druid就诞生了。

2011年2月春节期间，我完成了连接池（DruidDataSource）的第一个版本，4月开始在生产环境测试，2012年第一季度开始大规模实施。

提交过代码的开发者有5个人，主要代码是我维护，有一人专门负责内部实施。

通过开源，希望有更多使用场景，更多的反馈，更多人参与其中，共同打造最好的数据库连接池。

## Druid支持哪些数据库？[![Top](https://www.iteye.com/images/wiki/top.gif?1448702469)](https://www.iteye.com/magazines/90?page=2#top)

Druid支持所有JDBC兼容的数据库，包括Oracle、MySql、Derby、Postgresql、SQL Server、H2等等。

Druid针对Oracle和MySql做了特别优化，比如Oracle的PS Cache内存占用优化，MySql的ping检测优化。

## Druid是如何扩展JDBC的?[![Top](https://www.iteye.com/images/wiki/top.gif?1448702469)](https://www.iteye.com/magazines/90?page=2#top)

Druid在DruidDataSourc和ProxyDriver上提供了Filter-Chain模式的扩展API，类似Serlvet的Filter，配置Filter拦截JDBC的方法调用。

## 为什么说Druid是“最好的数据库连接池”?体现在哪些方面？这是如何实现的？[![Top](https://www.iteye.com/images/wiki/top.gif?1448702469)](https://www.iteye.com/magazines/90?page=2#top)

阿里巴巴是一个重度使用关系数据库的公司，我们在生产环境中大量的使用Druid，通过长期在极高负载的生产环境中实际使用、修改和完善，让Druid逐步发展成最好的数据库连接池。Druid在监控、可扩展性、稳定性和性能方面都有明显的优势。

首先，强大的监控特性，通过Druid提供的监控功能，可以清楚知道连接池和SQL的工作情况。



- 监控SQL的执行时间、ResultSet持有时间、返回行数、更新行数、错误次数、错误堆栈信息。
- SQL执行的耗时区间分布。什么是耗时区间分布呢？比如说，某个SQL执行了1000次，其中0~1毫秒区间50次，1~10毫秒800次，10~100毫秒100次，100~1000毫秒30次，1~10秒15次，10秒以上5次。通过耗时区间分布，能够非常清楚知道SQL的执行耗时情况。
- 监控连接池的物理连接创建和销毁次数、逻辑连接的申请和关闭次数、非空等待次数、PSCache命中率等。

![点击查看原始大小图片](http://dl.iteye.com/upload/attachment/0070/8056/775a7a10-fd54-35ab-8728-23f8049025d1.jpg)


其次，方便扩展。Druid提供了Filter-Chain模式的扩展API，可以自己编写Filter拦截JDBC中的任何方法，可以在上面做任何事情，比如说性能监控、SQL审计、用户名密码加密、日志等等。

Druid内置提供了用于监控的StatFilter、日志输出的Log系列Filter、防御SQL注入攻击的WallFilter。

阿里巴巴内部实现了用于数据库密码加密的CirceFilter，以及和Web、Spring关联监控的DragoonStatFilter。



![点击查看原始大小图片](http://dl.iteye.com/upload/attachment/0070/8058/8a350468-05d3-3e8c-a2d8-2f1fcc7a7fe9.jpg)


第三，Druid集合了开源和商业数据库连接池的优秀特性，并结合阿里巴巴大规模苛刻生产环境的使用经验进行优化。



- ExceptionSorter。当一个连接产生不可恢复的异常时，例如Oracle error_code_28 session has been killed，必须立刻从连接池中逐出，否则会产生大量错误。目前只有Druid和JBoss DataSource实现了ExceptionSorter。
- PSCache内存占用优化对于支持游标的数据库（Oracle、SQL Server、DB2等，不包括MySql），PSCache可以大幅度提升SQL执行性能。一个PreparedStatement对应服务器一个游标，如果PreparedStatement被缓存起来重复执行，PreparedStatement没有被关闭，服务器端的游标就不会被关闭，性能提高非常显著。在类似“SELECT * FROM T WHERE ID = ?”这样的场景，性能可能是一个数量级的提升。但在Oracle JDBC Driver中，其他的数据库连接池（DBCP、JBossDataSource）会占用内存过多，极端情况可能大于1G。Druid调用OracleDriver提供管理PSCache内部API。
- LRU是一个性能关键指标，特别Oracle，每个Connection对应数据库端的一个进程，如果数据库连接池遵从LRU，有助于数据库服务器优化，这是重要的指标。Druid、DBCP、Proxool、JBoss是遵守LRU的。BoneCP、C3P0则不是。BoneCP在mock环境下性能可能还好，但在真实环境中则就不好了。

## Druid的性能如何？能否给出一些测试对比数据？[![Top](https://www.iteye.com/images/wiki/top.gif?1448702469)](https://www.iteye.com/magazines/90?page=2#top)

性能不是Druid的设计目标，但是测试数据表明，Druid性能比DBCP、C3P0、Proxool、JBoss都好。

这里有一些测试数据：http://code.alibabatech.com/wiki/pages/viewpage.action?pageId=2916539

## 谈谈Druid的SQL解析功能？效率如何？[![Top](https://www.iteye.com/images/wiki/top.gif?1448702469)](https://www.iteye.com/magazines/90?page=2#top)

Druid提供了MySql、Oracle、Postgresql、SQL-92的SQL的完整支持，这是一个手写的高性能SQL Parser，支持Visitor模式，使得分析SQL的抽象语法树很方便。

简单SQL语句用时10微秒以内，复杂SQL用时30微秒。

通过Druid提供的SQL Parser可以在JDBC层拦截SQL做相应处理，比如说分库分表、审计等。Druid防御SQL注入攻击的WallFilter就是通过Druid的SQL Parser分析语义实现的。

## Druid的扩展性如何？[![Top](https://www.iteye.com/images/wiki/top.gif?1448702469)](https://www.iteye.com/magazines/90?page=2#top)

Druid提供Filter-Chain模式的插件框架，通过编写Filter配置到DruidDataSource中就可以拦截JDBC的各种API，从而实现扩展。Druid提供了一系列内置Filter。

## 在SQL注入防御方面，Druid的优势是什么？实现原理是什么？[![Top](https://www.iteye.com/images/wiki/top.gif?1448702469)](https://www.iteye.com/magazines/90?page=2#top)

Druid的优势是在JDBC最低层进行拦截做判断，不会遗漏。

Druid实现了Oracle、MySql、Postgresql、SQL-92的Parser，基于SQL语法分析实现，理解其中的SQL语义，智能、准确、误报率低。

具体细节参考这里：http://code.alibabatech.com/wiki/display/Druid/WallFilter

## 目前Druid的应用（部署）情况？[![Top](https://www.iteye.com/images/wiki/top.gif?1448702469)](https://www.iteye.com/magazines/90?page=2#top)

Druid是阿里巴巴监控系统Dragoon的副产品，从Dragoon监控系统的数据来看，在阿里巴巴已经部署了600多个应用。在阿里巴巴外部也有很多Druid的用户，外部用户没有正式统计数据，但经常有反馈。

## 我想将其中的某个模块（比如监控模块）用到其他连接池，是否可以？模块的独立性如何？[![Top](https://www.iteye.com/images/wiki/top.gif?1448702469)](https://www.iteye.com/magazines/90?page=2#top)

可以通过DruidDriver把内置的Filter用在其他连接池中。在2011年上半年DruidDataSource不成熟的时候，我们也是这么做的。在其他连接池中使用内置的Filter，需要修改jdbc-url，使用DruidDriver作为一个ProxyDriver。

## 我想在项目中使用，应该注意哪些事项？能否用于商业项目？[![Top](https://www.iteye.com/images/wiki/top.gif?1448702469)](https://www.iteye.com/magazines/90?page=2#top)

Druid是一个开源项目，基于Apache 2.0协议，你可以免费自由使用。Druid只支持JDK 6以上版本，不支持JDK 1.4和JDK 5.0。

## 配置是否复杂？能否给出一个典型的配置实例？[![Top](https://www.iteye.com/images/wiki/top.gif?1448702469)](https://www.iteye.com/magazines/90?page=2#top)

为了方便大家迁移，Druid的配置和DBCP是基本一致的，如果你原来是使用DBCP，迁移是十分方便的，只需要把corg.apache.commons.dbcp.BasicDataSource修改为om.alibaba.druid.pool.DruidDataSource就好了。

以下是一个参考配置：



Xml代码 

1. **<****bean** id="dataSource" class="com.alibaba.druid.pool.DruidDataSource" init-method="init" destroy-method="close"**>**  
2.   **<****property** name="url" value="${jdbc_url}" **/>** 
3.   **<****property** name="username" value="${jdbc_user}" **/>** 
4.   **<****property** name="password" value="${jdbc_password}" **/>** 
5. ​    
6.   **<****property** name="filters" value="stat" **/>** 
7.   
8.   **<****property** name="maxActive" value="20" **/>** 
9.   **<****property** name="initialSize" value="1" **/>** 
10.   **<****property** name="maxWait" value="60000" **/>** 
11.   **<****property** name="minIdle" value="1" **/>** 
12.   
13.   **<****property** name="timeBetweenEvictionRunsMillis" value="60000" **/>** 
14.   **<****property** name="minEvictableIdleTimeMillis" value="300000" **/>** 
15.   
16.   **<****property** name="validationQuery" value="SELECT 'x'" **/>** 
17.   **<****property** name="testWhileIdle" value="true" **/>** 
18.   **<****property** name="testOnBorrow" value="false" **/>** 
19.   **<****property** name="testOnReturn" value="false" **/>** 
20. ​    
21.   **<****property** name="poolPreparedStatements" value="true" **/>** 
22.   **<****property** name="maxPoolPreparedStatementPerConnectionSize" value="50" **/>** 
23. **</****bean****>** 


在上面的配置中，通常你需要配置url、username、password、maxActive这几项。

在DruidDataSource中，你可以不配置DriverClass，它根据url自动识别。Druid能够自动识别20多中url，常见的JDBC Driver都包括了。

## 我目前使用其他连接池（DBCP/C3P0/Proxool等），如何迁移到Druid？[![Top](https://www.iteye.com/images/wiki/top.gif?1448702469)](https://www.iteye.com/magazines/90?page=2#top)

从DBCP迁移最方便，把org.apache.commons.dbcp.BasicDataSource修改为om.alibaba.druid.pool.DruidDataSource就好了。

Druid网站上提供了[Druid/DBCP/C3P0/JBoss/WebLogic的参数对照表](http://code.alibabatech.com/wiki/pages/viewpage.action?pageId=6947005)，通过这个对照表来迁移你目前的配置。

## 其他开发者如何反馈问题、提交bug？[![Top](https://www.iteye.com/images/wiki/top.gif?1448702469)](https://www.iteye.com/magazines/90?page=2#top)

Druid源码托管在github.com上，项目地址是https://github.com/AlibabaTech/druid。

你可以在github上提交patch和issue（包括bug和新特性）。你也可以加入我们的QQ群92748305，和开发者以及其他用户一起交流。