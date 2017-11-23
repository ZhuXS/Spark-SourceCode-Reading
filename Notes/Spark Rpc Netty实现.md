# Spark Rpc Netty 实现

> Netty是一个NIO C/S框架，能够快速、简单的开发协议服务器和客户端等网络应用。它能够很大程度上简单化、流水线化开发网络应用，例如TCP/UDP socket服务器

## NettyRpcEnv源码阅读

NettyRpcEnv是RpcEnv基于Netty的实现

##### dispatcher

```scala
private val dispatcher: Dispatcher = new Dispatcher(this)
```

用于向rpc的终端分发消息

##### streamManager

```scala
private val streamManager = new NettyStreamManager(this)
```

##### transportContext

```scala
private val transportContext = new TransportContext(transportConf,
  new NettyRpcHandler(dispatcher, this, streamManager))
```

Netty的上下文环境，维护一个Context去创建TransportServer和TransportClientFactory，并且创建设置Netty Chanel。

##### private def createClientBootstraps(): java.util.List[TransportClientBootstrap]

```scala
private def createClientBootstraps(): java.util.List[TransportClientBootstrap] = {
  if (securityManager.isAuthenticationEnabled()) {
    java.util.Arrays.asList(new AuthClientBootstrap(transportConf,
      securityManager.getSaslUser(), securityManager))
  } else {
    java.util.Collections.emptyList[TransportClientBootstrap]
  }
}
```

TransportClientBootstrap是返回TransportClient给用户前要执行的引导程序。

##### clientFactory

```scala
private val clientFactory = transportContext.createClientFactory(createClientBootstraps())
```

调用transportContext的createClientFactory方法去创建TransportClientFactory。创建ClientFactory过程中，优先执行createClientBootStraps()。

##### fileDownloadFactory

```scala
@volatile private var fileDownloadFactory: TransportClientFactory = _
```

一个相对独立的TransportClientFactory用于文件下载。

##### timeoutScheduler

```scala
val timeoutScheduler = ThreadUtils.newDaemonSingleThreadScheduledExecutor("netty-rpc-env-timeout")
```

名为“netty-rpc-env-timeout”的检测请求是否超时的守护线程。

##### clientConnectionExecutor

```scala
private[netty] val clientConnectionExecutor = ThreadUtils.newDaemonCachedThreadPool(
  "netty-rpc-connection",
  conf.getInt("spark.rpc.connect.threads", 64))
```

TransportClientFactory在创建TransportClient的时候会被阻塞，所以我们从线程池中分配线程去执行这个操作，以求实现非阻塞的send和ask。

##### server

```scala
@volatile private var server: TransportServer = _
```

##### outboxes

```scala
private val outboxes = new ConcurrentHashMap[RpcAddress, Outbox]()
```

outboxes是维护RpcAddress和Outbox之间映射关系的CurrentHashMap。
与一个远端的RpcAddress建立连接之后，发送消息到其相对应的outbox，以实现非阻塞的send方法。

##### def startServer(bindAddress: String, port: Int): Unit

```scala
def startServer(bindAddress: String, port: Int): Unit = {
  val bootstraps: java.util.List[TransportServerBootstrap] =
    if (securityManager.isAuthenticationEnabled()) {
      java.util.Arrays.asList(new AuthServerBootstrap(transportConf, securityManager))
    } else {
      java.util.Collections.emptyList()
    }
  server = transportContext.createServer(bindAddress, port, bootstraps)
  dispatcher.registerRpcEndpoint(
    RpcEndpointVerifier.NAME, new RpcEndpointVerifier(this, dispatcher))
}
```

启动rpc server。
定义引导程序，执行引导程序创建server。
同时创建一个RpcEndpointVerifier，RpcEndpointVerifier同时也是一个RpcEndpoint，在相应dispatcher分发消息时，检查相应的endpoint是否存在。

##### override def setupEndpoint(name: String, endpoint: RpcEndpoint): RpcEndpointRef

```scala
override def setupEndpoint(name: String, endpoint: RpcEndpoint): RpcEndpointRef = {
  dispatcher.registerRpcEndpoint(name, endpoint)
}
```

注册endpoint，即在dispatcher上注册。

##### def asyncSetupEndpointRefByURI(uri: String): Future[RpcEndpointRef]

```scala
def asyncSetupEndpointRefByURI(uri: String): Future[RpcEndpointRef] = {
  val addr = RpcEndpointAddress(uri)
  val endpointRef = new NettyRpcEndpointRef(conf, addr, this)
  val verifier = new NettyRpcEndpointRef(
    conf, RpcEndpointAddress(addr.rpcAddress, RpcEndpointVerifier.NAME), this)
  verifier.ask[Boolean](RpcEndpointVerifier.CheckExistence(endpointRef.name)).flatMap { find =>
    if (find) {
      Future.successful(endpointRef)
    } else {
      Future.failed(new RpcEndpointNotFoundException(uri))
    }
  }(ThreadUtils.sameThread)
}
```

根据给定的uri异步的去注册一个RpcEndpoint。
首先根据给定的uri封装成为一个RpcEndpointAddress，作为创建RpcEndpointRef的参数。


