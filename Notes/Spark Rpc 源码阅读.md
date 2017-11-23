 

 # Spark Rpc 源码阅读 

 Spark RPC用于在节点之间进行点对点的通信。

 ## RpcEnv伴随对象

 在RpcEnv的伴随对象中，定义了RpcEnv的create方法。最终通过调用工厂类NettyRpcEnvFactory的工厂方法来创建。

 ```scala
new NettyRpcEnvFactory().create(config)
 ```

 ## 抽象类RpcEnv

 ##### 查询超时时间

 ```scala
private[spark] val defaultLookupTimeout = RpcUtils.lookupRpcTimeout(conf)
 ```

优先级 spark.rpc.askTimeout > spark.network.timeout > 120

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

##### 返回一个ReadableByteChannel，下载给定uri所在位置的文件。

```scala
def openChannel(uri: String): ReadableByteChannel
```

##### 同时在RpcEnv中定义了一个内部特征类

```scala
private[spark] trait RpcEnvFileServer
```

用于RPC Env，用于暂存文件？

##### 特征类RpcEnvConfig存储配置信息

## RpcEndPoint

rpc的终端，定义了可以被触发去发送消息的方法

##### rpvEnv

```Scala
val rpcEnv: RpcEnv
```

PpcEndPoint所注册在的RpcENV

##### self()

```scala
final def self: RpcEndpointRef = {
  require(rpcEnv != null, "rpcEnv has not been initialized")
  rpcEnv.endpointRef(this)
}
```

返回该rpcEndPoint的ref

##### def receive: PartialFunction[Any, Unit]

```scala
def receive: PartialFunction[Any, Unit] = {
  case _ => throw new SparkException(self + " does not implement 'receive'")
}
```

处理来自RpcEndPointRef.send和RpcCallContext.reply的消息

##### def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit]

```scala
def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
  case _ => context.sendFailure(new SparkException(self + " won't reply anything"))
}
```

处理并回复来自RpcEndpointRef.ask的消息。

##### Invoke Method

RpcEndPoint定义了一些Invoke Method，在一些事件（error,connected,disconnected,networkError,start,stop… ...）发生时被触发(onError,onStart… ...)。

## RpcEndpointRef

##### 关于失败重试和超时

```scala
private[this] val maxRetries = RpcUtils.numRetries(conf)
private[this] val retryWaitMs = RpcUtils.retryWaitMs(conf)
private[this] val defaultAskTimeout = RpcUtils.askRpcTimeout(conf)
```

三个变量分别定义了最多尝试次数、重试的等待间隔时间、ask后等待的最长等待时间。

##### def send(message: Any): Unit

发送一个单向的异步消息

##### def ask [T: ClassTag] (message: Any, timeout: RpcTimeout): Future[T]

发送消息并等待RpcEndPoint的recieveAndReply()的响应，该方法只尝试一次，超时返回（不传入timeout参数则使用default值）。

同时ask方法也有同步版本。

```scala
def askSync[T: ClassTag](message: Any, timeout: RpcTimeout): T = {
  val future = ask[T](message, timeout)
  timeout.awaitResult(future)
}
```



## RpcCallContext

被RpcEndPoint用于去回复消息或是发送失败消息（异常），线程安全。

```scala
def reply(response: Any): Unit
def sendFailure(e: Throwable): Unit
def senderAddress: RpcAddress
```



## RpcAddress && RpcEndpointAddress

封装相关的host、port、uri等地址信息。



