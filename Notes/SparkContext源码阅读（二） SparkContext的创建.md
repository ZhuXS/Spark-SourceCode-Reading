# SparkContext源码阅读（二） SparkContext的创建

## 验证SparkConf的合法性

```scala
_conf = config.clone()
_conf.validateSettings()
```

首先获取传入构造参数的SparkConf，然后检查是否有不合法或者已经弃用的参数，validateSettings()方法可能会改变_conf，这是因为在验证的过程中，会将弃用的参数类型转换成为现版本支持的参数。

```scala
if (!_conf.contains("spark.master")) {
  throw new SparkException("A master URL must be set in your configuration")
}
if (!_conf.contains("spark.app.name")) {
  throw new SparkException("An application name must be set in your configuration")
}
```

SparkConf必须指定master的url和应用的名称。

```scala
if (master == "yarn" && deployMode == "cluster" && !_conf.contains("spark.yarn.app.id")) {
  throw new SparkException("Detected yarn cluster mode, but isn't running on a cluster. " +
    "Deployment to YARN is not supported directly by SparkContext. Please use spark-submit.")
}
```

如果选择Yarn-Cluster模式，必须设置Spark.yarn.app.id

## 创建过程

##### _jars  &&  _files

```scala
_jars = Utils.getUserJars(_conf)
_files = _conf.getOption("spark.files").map(_.split(",")).map(_.filter(_.nonEmpty))
  .toSeq.flatten
```

##### _eventLogDir

```scala
_eventLogDir =
  if (isEventLogEnabled) {
    val unresolvedDir =     conf.get("spark.eventLog.dir",EventLoggingListener.DEFAULT_LOG_DIR)
      .stripSuffix("/")
    Some(Utils.resolveURI(unresolvedDir))
  } else {
    None
  }
```

设置记录spark事件的根目录，在此根目录中，spark为每一个应用程序创建分目录，并将应用程序创建分目录，用于应用程序在完成后重构WebUI

##### _eventLogCodec

```Scala
_eventLogCodec = {
  val compress = _conf.getBoolean("spark.eventLog.compress", false)
  if (compress && isEventLogEnabled) {
    Some(CompressionCodec.getCodecName(_conf)).map(CompressionCodec.getShortName)
  } else {
    None
  }
}
```

设置Spark事件日志的压缩方式

##### _jobProgressListener

```scala
_jobProgressListener = new JobProgressListener(_conf)
listenerBus.addListener(jobProgressListener)
```

JobProgressListener是为了跟踪Job的状态并将其展示在UI上而存在的，JobProgressListener中维护了一些Job，stage等相关的数据结构，UI线程会轮训来读这些数据来更新界面。
listenerBus（LiveListenerBus类型）是spark事件一个异步的消息队列，用于监听事件，可以理解成为是“计算机的总线”角色。

##### _env

```scala
_env = createSparkEnv(_conf, isLocal, listenerBus)
SparkEnv.set(_env)
```

创建Spark的执行环境

##### _statusTracker

```scala
_statusTracker = new SparkStatusTracker(this)
```

SparkStatusTracker，从名字可以看出，它是一个底层的用于追踪Spark Job和Stage进程的工具。

##### _progressBar

```Scala
_progressBar =
  if (_conf.getBoolean("spark.ui.showConsoleProgress", true) && !log.isInfoEnabled) {
    Some(new ConsoleProgressBar(this))
  } else {
    None
  }
```

ConsoleProgressBar在控制台中显示stage的进程，它从statusTracker中获取数据

##### _ui

```scala
_ui =
  if (conf.getBoolean("spark.ui.enabled", true)) {
    Some(SparkUI.createLiveUI(this, _conf, listenerBus, _jobProgressListener,
      _env.securityManager, appName, startTime = startTime))
  } else {
    None
  }
_ui.foreach(_.bind())
```

创建SparkUI

##### _hadoopConfiguration

```scala
_hadoopConfiguration = SparkHadoopUtil.get.newConfiguration(_conf)
```

获取Hadoop的Config

##### 添加jar和file

```scala
if (jars != null) {
  jars.foreach(addJar)
}

if (files != null) {
  files.foreach(addFile)
}
```

##### 设置executor的内存

```scala
_executorMemory = _conf.getOption("spark.executor.memory")
  .orElse(Option(System.getenv("SPARK_EXECUTOR_MEMORY")))
  .orElse(Option(System.getenv("SPARK_MEM"))
  .map(warnSparkMem))
  .map(Utils.memoryStringToMb)
  .getOrElse(1024)
```

优先级，由上而下
Spark.executor.memory：SparkConf给出的内存大小
SPARK_EXECUTOR_MEMORY：Spark环境变量
SPARK_MEM：Spark环境变量
1024

#####executorEnvs

```scala
for { (envKey, propKey) <- Seq(("SPARK_TESTING", "spark.testing"))
  value <- Option(System.getenv(envKey)).orElse(Option(System.getProperty(propKey)))} {
  executorEnvs(envKey) = value
}
Option(System.getenv("SPARK_PREPEND_CLASSES")).foreach { v =>
  executorEnvs("SPARK_PREPEND_CLASSES") = v
}
executorEnvs("SPARK_EXECUTOR_MEMORY") = executorMemory + "m"
executorEnvs ++= _conf.getExecutorEnv
executorEnvs("SPARK_USER") = sparkUser
```

设置executor的环境变量

##### _heartbeatReceiver

```scala
_heartbeatReceiver = env.rpcEnv.setupEndpoint(
  HeartbeatReceiver.ENDPOINT_NAME, new HeartbeatReceiver(this))
```

在Spark中，driver和executor之间的交互用到了心跳机制。

> 心跳机制是定时发送一个自定义的结构体（心跳包），让对方知道自己还“活”着，以确保连接的有效性的机制。

Heartbeat是Spark内部的一种消息类型，在多个内部组件之间共享，传播执行中的任务的执行信息和活跃状态。HeartbeatReceiver在Driver端接收来自executor的心跳包，是一个线程安全的RpcEndPoint。同样在Executor端也会有HeartbeatReceiverRef，是一个RpcEndPointEnf，定时向driver发送心跳包。

```scala
val message = Heartbeat(executorId, accumUpdates.toArray, env.blockManager.blockManagerId)
val response = heartbeatReceiverRef.askWithRetry[HeartbeatResponse](message, RpcTimeout(conf, "spark.executor.heartbeatInterval", "10s")) 
```

##### 创建并启动TaskScheduler

```scala
val (sched, ts) = SparkContext.createTaskScheduler(this, master, deployMode)
```

调用createTaskScheduler，三个参数：
this，即SparkContext；master，master的url；deployMode，部署模式。
返回SchedulerBackend和TaskScheduler，用来进行资源调度。TaskScheduler负责Task级别的调度，将DAGScheduler给过来的TaskSet按照指定的调度策略分发到Executor上执行，调度过程中SchedulerBackend负责提供可用资源，SchedulerBackend有多种实现，分别对接不同的资源管理系统。

