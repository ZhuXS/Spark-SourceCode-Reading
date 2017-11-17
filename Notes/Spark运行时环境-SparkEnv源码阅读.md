# Spark运行时环境-SparkEnv源码阅读

## 概述

SparkEnv持有Spark所有的运行时对象，包括serializer, RpcEnv, block manager, map output tracker。
所有的线程可以通过全局变量的方式并发的访问同一个SparkEnv。

## 运行时对象

**executorId**

**rpcEnv**

**serializer**

**closureSerializer**

**serializerManager**

**mapOutputTracker**

**shuffleManager**

**broadcastManager**

**blockManager**

**securityManager**

**metricsSystem**

**memoryManager**

**outputCommitCoordinator**

**conf**

## 伴随对象

SparkEnv的单例、全局对象

##### get&&set

设置和获取SparkEnv

##### createDriverEnv

为driver创建SparkEnv，将传入的参数和conf中的参数整合，作为create方法的参数，并调用create方法得到一个SparkEnv实例，然后调用set方法。

##### createExecutorEnv

为executor创建SparkEnv，将传入的参数和conf中的参数整合，作为create方法的参数，并调用create方法得到一个SparkEnv实例，然后调用set方法。

#####create

私有方法，帮助driver或者executor创建SparkEnv实例。

```scala
 val isDriver = executorId == SparkContext.DRIVER_IDENTIFIER
```

创建过程会因为是driver还是executor而有所区分。

```scala
if (isDriver) {
  assert(listenerBus != null, "Attempted to create driver SparkEnv with null listener bus!")
}
```

ListenerBus只在driver端被创建。

```scala
val securityManager = new SecurityManager(conf, ioEncryptionKey)
ioEncryptionKey.foreach { _ =>
  if (!securityManager.isEncryptionEnabled()) {
    logWarning("I/O encryption enabled without RPC encryption: keys will be visible on the " +
      "wire.")
  }
}
```

创建SecurityManager。

```scala
val rpcEnv = RpcEnv.create(systemName, bindAddress, advertiseAddress, port, conf,
  securityManager, clientMode = !isDriver)
```

创建Rpc

```scala
val rpcEnv = RpcEnv.create(systemName, bindAddress, advertiseAddress, port, conf,
  securityManager, clientMode = !isDriver)
```

更新Spark driver和execuotr端口的配置信息。

```scala
if (isDriver) {
  conf.set("spark.driver.port", rpcEnv.address.port.toString)
} else if (rpcEnv.address != null) {
  conf.set("spark.executor.port", rpcEnv.address.port.toString)
  logInfo(s"Setting spark.executor.port to: ${rpcEnv.address.port.toString}")
}
```

从conf中获取serializer的类名（spark.serializer），通过反射方式，创建Serializer。如果没有进行配置，默认值为org.apache.spark.serializer.JavaSerializer。

创建SerializerManager

```scala
val serializerManager = new SerializerManager(serializer, conf, ioEncryptionKey)
```

管理Spark中出现的压缩、序列化、加密等操作。

创建closureSerializer

```scala
val closureSerializer = new JavaSerializer(conf)
```

closureSerializer是一个JavaSerializer的实例，

创建BroadCastManager

```scala
val broadcastManager = new BroadcastManager(isDriver, conf, securityManager)
```

用于广播变量，BroadcastManager依赖于securityManager。

创建MapOutputTracker

```scala
val mapOutputTracker = if (isDriver) {
  new MapOutputTrackerMaster(conf, broadcastManager, isLocal)
} else {
  new MapOutputTrackerWorker(conf)
}
```

mapOutputTracker用于维护每一个stage输出的map，创建时根据driver和executor的区别会有所区分，因为两者使用不同的hashmap去存储中间数据。

为mapOutputTracker注册trackerEndpoint

```scala
mapOutputTracker.trackerEndpoint = registerOrLookupEndpoint(MapOutputTracker.ENDPOINT_NAME,
  new MapOutputTrackerMasterEndpoint(
    rpcEnv, mapOutputTracker.asInstanceOf[MapOutputTrackerMaster], conf))
```

创建ShuffleManager

```scala
val shuffleManager = instantiateClass[ShuffleManager](shuffleMgrClass)
```

根据用户配置的类名创建。

创建MemoryManager

```scala
val memoryManager: MemoryManager =
  if (useLegacyMemoryManager) {
    new StaticMemoryManager(conf, numUsableCores)
  } else {
    UnifiedMemoryManager(conf, numUsableCores)
  }
```

用于决定多少内存用于计算，多少内存用于存储。

创建BlockManager

```scala
val blockManager = new BlockManager(executorId, rpcEnv, blockManagerMaster,
  serializerManager, conf, memoryManager, mapOutputTracker, shuffleManager,
  blockTransferService, securityManager, numUsableCores)
```

BlockManager 在一个 spark 应用中作为一个本地缓存运行在所有的节点上， 包括所有 driver 和 executor上。BlockManager 对本地和远程提供一致的 get 和set 数据块接口，BlockManager 本身使用不同的存储方式来存储这些数据， 包括 memory, disk, off-heap。

创建MetricsSystem

```scala
val metricsSystem = if (isDriver) {
  MetricsSystem.createMetricsSystem("driver", conf, securityManager)
} else {
  conf.set("spark.executor.id", executorId)
  val ms = MetricsSystem.createMetricsSystem("executor", conf, securityManager)
  ms.start()
  ms
}
```

MetricsSystem是Spark的性能度量系统。

创建OutputCommitCoordinator，并为其注册coordinatorRef。

```scala
val outputCommitCoordinator = mockOutputCommitCoordinator.getOrElse {
  new OutputCommitCoordinator(conf, isDriver)
}
val outputCommitCoordinatorRef = registerOrLookupEndpoint("OutputCommitCoordinator",
  new OutputCommitCoordinatorEndpoint(rpcEnv, outputCommitCoordinator))
outputCommitCoordinator.coordinatorRef = Some(outputCommitCoordinatorRef)
```

最后创建SparkEnv并返回。