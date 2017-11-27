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
之后创建一个RpcEndpointVerifier的RpcEndpointRef，去检查是否有相应的endpoint，如果有则返回其endpointRef，没有则抛出异常。

##### override def stop(endpointRef: RpcEndpointRef): Unit

```scala
override def stop(endpointRef: RpcEndpointRef): Unit = {
  require(endpointRef.isInstanceOf[NettyRpcEndpointRef])
  dispatcher.stop(endpointRef)
}
```

停止某个endpointRef的工作

##### private def postToOutbox(receiver: NettyRpcEndpointRef, message: OutboxMessage): Unit

```scala
private def postToOutbox(receiver: NettyRpcEndpointRef, message: OutboxMessage): Unit = {
  if (receiver.client != null) {
    message.sendWith(receiver.client)
  } else {
    require(receiver.address != null,
    val targetOutbox = {
      val outbox = outboxes.get(receiver.address)
      if (outbox == null) {
        val newOutbox = new Outbox(this, receiver.address)
        val oldOutbox = outboxes.putIfAbsent(receiver.address, newOutbox)
        if (oldOutbox == null) {
          newOutbox
        } else {
          oldOutbox
        }
      } else {
        outbox
      }
    }
    if (stopped.get) {
      outboxes.remove(receiver.address)
      targetOutbox.stop()
    } else {
      targetOutbox.send(message)
    }
  }
}
```

将Message Post到outbox。
如果receiver已经注册了TransportClient，直接发送message。如果没有，则发送到相应的outbox。

##### private[netty] def send(message: RequestMessage): Unit

```scala
private[netty] def send(message: RequestMessage): Unit = {
  val remoteAddr = message.receiver.address
  if (remoteAddr == address) {
    try {
      dispatcher.postOneWayMessage(message)
    } catch {
      case e: RpcEnvStoppedException => logWarning(e.getMessage)
    }
  } else {
    postToOutbox(message.receiver, OneWayOutboxMessage(message.serialize(this)))
  }
}
```

区分Local的endPoint还是remote的endPoint。

##### private[netty] def ask [T: ClassTag] (message: RequestMessage, timeout: RpcTimeout): Future[T]

```scala
private[netty] def ask[T: ClassTag](message: RequestMessage, timeout: RpcTimeout): Future[T] = {
  val promise = Promise[Any]()
  val remoteAddr = message.receiver.address
  
  def onFailure(e: Throwable): Unit = {
    if (!promise.tryFailure(e)) {
      logWarning(s"Ignored failure: $e")
    }
  }

  def onSuccess(reply: Any): Unit = reply match {
    case RpcFailure(e) => onFailure(e)
    case rpcReply =>
      if (!promise.trySuccess(rpcReply)) {
        logWarning(s"Ignored message: $reply")
      }
  }

  try {
    if (remoteAddr == address) {
      val p = Promise[Any]()
      p.future.onComplete {
        case Success(response) => onSuccess(response)
        case Failure(e) => onFailure(e)
      }(ThreadUtils.sameThread)
      dispatcher.postLocalMessage(message, p)
    } else {
      val rpcMessage = RpcOutboxMessage(message.serialize(this),
        onFailure,
        (client, response) => onSuccess(deserialize[Any](client, response)))
      postToOutbox(message.receiver, rpcMessage)
      promise.future.onFailure {
        case _: TimeoutException => rpcMessage.onTimeout()
        case _ =>
      }(ThreadUtils.sameThread)
    }

    val timeoutCancelable = timeoutScheduler.schedule(new Runnable {
      override def run(): Unit = {
        onFailure(new TimeoutException(s"Cannot receive any reply from ${remoteAddr} " +
          s"in ${timeout.duration}"))
      }
    }, timeout.duration.toNanos, TimeUnit.NANOSECONDS)
    promise.future.onComplete { v =>
      timeoutCancelable.cancel(true)
    }(ThreadUtils.sameThread)
  } catch {
    case NonFatal(e) =>
      onFailure(e)
  }
  promise.future.mapTo[T].recover(timeout.addMessageIfTimeout)(ThreadUtils.sameThread)
}
```

在方法体中，首先定义了两个内部的回调方法，分别用于成功和失败情况下的回调。
如果消息的目的地在本地，则直接调用dispatcher的postLocalMessage方法；如果目的地在远端，则构造一个RpcOutboxMessage，其构造函数中包括了content以及Failure和Success下的回调方法，然后调用postToOutbox方法，将消息发送到ref端的outbox。
将结果打包成为T类型，包装在一个新的Future中返回。

##### private def downloadClient(host: String, port: Int): TransportClient

```scala
private def downloadClient(host: String, port: Int): TransportClient = {
  if (fileDownloadFactory == null) synchronized {
    if (fileDownloadFactory == null) {
      val module = "files"
      val prefix = "spark.rpc.io."
      val clone = conf.clone()
      conf.getAll.foreach { case (key, value) =>
        if (key.startsWith(prefix)) {
          val opt = key.substring(prefix.length())
          clone.setIfMissing(s"spark.$module.io.$opt", value)
        }
      }

      val ioThreads = clone.getInt("spark.files.io.threads", 1)
      val downloadConf = SparkTransportConf.fromSparkConf(clone, module, ioThreads)
      val downloadContext = new TransportContext(downloadConf, new NoOpRpcHandler(), true)
      fileDownloadFactory = downloadContext.createClientFactory(createClientBootstraps())
    }
  }
  fileDownloadFactory.createClient(host, port)
}
```

创建一个用于文件下载的TransportClient

##### override def openChannel(uri: String): ReadableByteChannel

```scala
override def openChannel(uri: String): ReadableByteChannel = {
  val parsedUri = new URI(uri)
  require(parsedUri.getHost() != null, "Host name must be defined.")
  require(parsedUri.getPort() > 0, "Port must be defined.")
  require(parsedUri.getPath() != null && parsedUri.getPath().nonEmpty, "Path must be defined.")

  val pipe = Pipe.open()
  val source = new FileDownloadChannel(pipe.source())
  try {
    val client = downloadClient(parsedUri.getHost(), parsedUri.getPort())
    val callback = new FileDownloadCallback(pipe.sink(), source, client)
    client.stream(parsedUri.getPath(), callback)
  } catch {
    case e: Exception =>
      pipe.sink().close()
      source.close()
      throw e
  }

  source
}
```

根据给定的uri打开一个chanel，通过Java的NIO来实现。
创建一个FileDownloadChannel（继承自ReadableByteChannel），作为数据输出的通道。然后创建一个transportClient，从其stream中获得数据，输出到source。
该过程是异步的。

最后返回source。

##### NettyRpcEnvFactory

```scala
private[rpc] class NettyRpcEnvFactory extends RpcEnvFactory with Logging {

  def create(config: RpcEnvConfig): RpcEnv = {
    val sparkConf = config.conf
    val javaSerializerInstance =
      new JavaSerializer(sparkConf).newInstance().asInstanceOf[JavaSerializerInstance]
    val nettyEnv =
      new NettyRpcEnv(sparkConf, javaSerializerInstance, config.advertiseAddress,
        config.securityManager)
    if (!config.clientMode) {
      val startNettyRpcEnv: Int => (NettyRpcEnv, Int) = { actualPort =>
        nettyEnv.startServer(config.bindAddress, actualPort)
        (nettyEnv, nettyEnv.address.port)
      }
      try {
        Utils.startServiceOnPort(config.port, startNettyRpcEnv, sparkConf, config.name)._1
      } catch {
        case NonFatal(e) =>
          nettyEnv.shutdown()
          throw e
      }
    }
    nettyEnv
  }
}
```

RpcEnv的创建必须通过RpcEnvFactory实现。
创建一个NettyRpcEnv，并调用其startServer()方法。



