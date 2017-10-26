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

