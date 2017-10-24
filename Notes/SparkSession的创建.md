# SparkSession的创建

## SparkSession：新的切入点

Apache Spark 2.0引入了SparkSession，为用户提供了一个统一的切入点来使用Spark的各项功能，并且允许用户通过它调用DataFrame和DataSet相关API来编写Spark程序。

SparkSession对SQLContext和HiveContext保持后向兼容。

```scala
var sparkSession = SparkSession
					.builder
					.appName("appName")
					.getOrCreate()
```



## SparkSession的创建

SparkSession的创建使用了建造者模式。

> 建造者模式是将一个复杂对象的构建与它的表示分离，使得同样的构建过程可以创建不同的表示
>
> 建造者模式是一步一步创建一个复杂的对象，它允许用户只通过指定复杂对象的类型和内容就可以构建它们，用户不需要知道内部的具体构建细节。

在创建SparkSession时首先获取其Builder。

> 由于Scala不允许类维护静态元素（静态变量和静态方法），在scala中提供类似功能的是使用Object成为“单例对象”，将该对象变成为其同名类的“伴侣”对象（Companion对象），这两个定义应放在同一个文件中。类和其Companion对象可以互相访问对方的私有成员。

在SparkSession中定义了其伴侣对象。
在其伴侣对象中有两个重要的成员变量

```scala
private val activeThreadSession = new InheritableThreadLocal[SparkSession] 
private val defaultSession = new AtomicReference[SparkSession] 
```

- activeThreadSession是当前线程绑定的独立的SparkSession。
  存储到当前线程IntheritableThreadLocal中，之所以采用InheritableThreadLocal是因为ThreadLocal中的值无法在父子线程之间传递，而InheritableThreadLocal（可继承的ThreadLocal）则可以解决这个问题。

- defaultSession，全局的SparkSession。
  实用化AtomicReference确保对它的修改都是原子的。
  当当前线程未绑定可用SparkSession，使用DefaultSession。

  > AtomicReference基于JAVA Unsafe的CAS操作

在其伴侣对象中定义了Builder这个类

```scala
def builder(): Builder = new Builder
```

通过调用builder()来创建一个新的builder。

在Builder中，

```scala
private[this] val options = new scala.collection.mutable.HashMap[String, String]
```

options是sparksession的属性k-v的map

```scala
def config(key: String, value: Long): Builder = synchronized {
      options += key -> value.toString
      this
    }
```

bulider中有若干针对不同属性值类型的config方法，向options中增加属性
config操作都是同步的

```scala
def appName(name:String):Builder = config("spark.app.name",name)
def master(name:String):Builder = config("spark.master",name)
```

appName()和master()方法来设置application的name和master地址

最后通过getOrCreate()方法来创建SparkSession

```scala
def getOrCreate(): SparkSession = synchronized {  //spark session的创建
      var session = activeThreadSession.get()  //当前线程（父线程）的session
      if ((session ne null) && !session.sparkContext.isStopped) {
        options.foreach { case (k, v) => session.sessionState.conf.setConfString(k, v) }
        if (options.nonEmpty) {
          logWarning("Using an existing SparkSession; some configuration may not take effect.")
        }
        return session  //返回当前线程的session
      }
      SparkSession.synchronized {  //如果当前线程没有绑定可用的session，获取全局的session
        session = defaultSession.get()  //获取global session
        if ((session ne null) && !session.sparkContext.isStopped) {
          options.foreach { case (k, v) => session.sessionState.conf.setConfString(k, v) }  //修改是原子操作，AutomicReference
          if (options.nonEmpty) {
            logWarning("Using an existing SparkSession; some configuration may not take effect.")
          }
          return session
        }
        //当前线程即没有绑定可用的session，又无法获取global spark session
        val sparkContext = userSuppliedContext.getOrElse {  //option getOrElse
          // set app name if not given
          val randomAppName = java.util.UUID.randomUUID().toString  //随机app name
          val sparkConf = new SparkConf()  //创建SparkConf
          options.foreach { case (k, v) => sparkConf.set(k, v) }  //设置sparkConf属性
          if (!sparkConf.contains("spark.app.name")) {
            sparkConf.setAppName(randomAppName)  //设置app name
          }
          val sc = SparkContext.getOrCreate(sparkConf)  //获取SparkContext，如果没有则创建新的
          options.foreach { case (k, v) => sc.conf.set(k, v) }  //更新SparkContext的属性
          if (!sc.conf.contains("spark.app.name")) {
            sc.conf.setAppName(randomAppName)
          }
          sc
        }
        // Initialize extensions if the user has defined a configurator class.
        val extensionConfOption = sparkContext.conf.get(StaticSQLConf.SPARK_SESSION_EXTENSIONS)
        if (extensionConfOption.isDefined) {
          val extensionConfClassName = extensionConfOption.get
          try {
            val extensionConfClass = Utils.classForName(extensionConfClassName)
            val extensionConf = extensionConfClass.newInstance()
              .asInstanceOf[SparkSessionExtensions => Unit]
            extensionConf(extensions)
          } catch {
            // Ignore the error if we cannot find the class or when the class has the wrong type.
            case e @ (_: ClassCastException |
                      _: ClassNotFoundException |
                      _: NoClassDefFoundError) =>
              logWarning(s"Cannot use $extensionConfClassName to configure session extensions.", e)
          }
        }

        session = new SparkSession(sparkContext, None, None, extensions)  //根据sparkContext和extensions创建session
        options.foreach { case (k, v) => session.sessionState.conf.setConfString(k, v) }
        defaultSession.set(session)  //设置sparkSession

        // Register a successfully instantiated context to the singleton. This should be at the
        // end of the class definition so that the singleton is updated only if there is no
        // exception in the construction of the instance.
        sparkContext.addSparkListener(new SparkListener {  //监听application终止事件
          override def onApplicationEnd(applicationEnd: SparkListenerApplicationEnd): Unit = {
            defaultSession.set(null)
            sqlListener.set(null)
          }
        })
      }

      return session
    }
```

方法首先检查当前线程有无Local的可用的SparkSession，如果有则返回该Session；
如果没有则去检查有无Global Default 的SparkSession，如果有则返回该Session；
没有则创建一个新的Session，并assign 给defaultSession。

需要注意的是，在该方法中，一共出现了两次Synchronized，一次标注了getOrCreate的方法体，一次标注了获取或创建DefaultSession的代码块。

首先说第一个synchronized，
在scala中，synchronized不像在java中是一个关键字，而是AnyRef（可以理解成java.lang.Object的别名）的一个成员。
所以synchronized和this.synchronized之间并没有区别，就像toString()和this.toString之间的关系一样。
因此第一个synchronized相当于builder.synchronized，当创建session 时，首先要获取builder这个对象的锁。

第二个synchronized获取的是SparkSession这个对象的锁，锁的粒度比第一个synchronized更大，这是为了确保同一时刻只有一个线程对全局的SparkSession进行修改。



## 源码清单

*org.apache.spark.sql.SparkSession*





 

