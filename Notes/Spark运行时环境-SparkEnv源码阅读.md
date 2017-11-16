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