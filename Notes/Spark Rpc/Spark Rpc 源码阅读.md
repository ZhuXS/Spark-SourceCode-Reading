

# Spark Rpc 源码阅读

Spark RPC用于在节点之间进行点对点的通信。

## RpcEnv伴随对象

在RpcEnv的伴随对象中，定义了RpcEnv的create方法。最终通过调用工厂类NettyRpcEnvFactory的工厂方法来创建。

```scala
new NettyRpcEnvFactory().create(config)
```

## 抽象类RpcEnv

##### 查询超时时间

```
private[spark] val defaultLookupTimeout = RpcUtils.lookupRpcTimeout(conf)
```

优先级 spark.rpc.askTimeout > spark.network.timeout > 120s

##### 返回相应RpcEndpoint的RpcEndpointRef

```scala
private[rpc] def endpointRef(endpoint: RpcEndpoint): RpcEndpointRef
```

##### 返回RpcEnv的address

```scala
def address: RpcAddress
```

##### 注册RpcEndpoint并返回其Ref

```scala
def setupEndpoint(name: String, endpoint: RpcEndpoint): RpcEndpointRef
```

并不保证线程安全

##### 根据RpcEndpoint的uri异步取得其Ref

```scala
def asyncSetupEndpointRefByURI(uri: String): Future[RpcEndpointRef]
```

##### 根据RpcEndpoint的uri取得其Ref，该方法同步阻塞

```scala
def setupEndpointRefByURI(uri: String): RpcEndpointRef = {
  defaultLookupTimeout.awaitResult(asyncSetupEndpointRefByURI(uri))
}
```

##### 根据address和endpointName来取得其Ref

```scala
def setupEndpointRef(address: RpcAddress, endpointName: String): RpcEndpointRef = {
  setupEndpointRefByURI(RpcEndpointAddress(address, endpointName).toString)
}
```

##### 停止某个特定RpcEndpoint的RpcEndpointRef

```scala
def stop(endpoint: RpcEndpointRef): Unit
```

##### 关闭RpcEnv，异步

```scala
def shutdown(): Unit
```

##### 返回文件服务器

```scala
def fileServer: RpcEnvFileServer
```

返回一个ReadableByteChannel，下载给定uri所在位置的文件。