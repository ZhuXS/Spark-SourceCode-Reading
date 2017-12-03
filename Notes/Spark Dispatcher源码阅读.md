# Spark Dispatcher 源码阅读

##### EndpointData

```scala
private class EndpointData(
    val name: String,
    val endpoint: RpcEndpoint,
    val ref: NettyRpcEndpointRef) {
  val inbox = new Inbox(ref, endpoint)
}
```

对RpcEndpoint等的封装。
包括其name、RpcEndpoint及其ref，然后根据ref和endpoint创建一个Inbox。

##### private val endpoints: ConcurrentMap[String, EndpointData]

维护所有注册在dispatcher上的RpcEndpoint。

##### private val endpointRefs: ConcurrentMap[RpcEndpoint, RpcEndpointRef]

维护了所有注册在dispatcher上的endpoint和其ref之间的映射关系。

##### private val receivers = new LinkedBlockingQueue[EndpointData]

维护一些inbox里可能包含Message的endpoint。

##### def registerRpcEndpoint(name: String, endpoint: RpcEndpoint): NettyRpcEndpointRef 

```scala
def registerRpcEndpoint(name: String, endpoint: RpcEndpoint): NettyRpcEndpointRef = {
  val addr = RpcEndpointAddress(nettyEnv.address, name)
  val endpointRef = new NettyRpcEndpointRef(nettyEnv.conf, addr, nettyEnv)
  synchronized {
    if (stopped) {
      throw new IllegalStateException("RpcEnv has been stopped")
    }
    if (endpoints.putIfAbsent(name, new EndpointData(name, endpoint, endpointRef)) != null) {
      throw new IllegalArgumentException(s"There is already an RpcEndpoint called $name")
    }
    val data = endpoints.get(name)
    endpointRefs.put(data.endpoint, data.ref)
    receivers.offer(data)  // for the OnStart message
  }
  endpointRef
}
```

用于在dispatcher上注册RpcEndpoint。

根据env的地址和endpoint的name构建RpcEndpointAddress，然后据此构建NettyRpcEndpiontRef。
检测dispatch是否停止、是否有重名的endpoint。

通过检测，则将endpoint及其ref和name打包成为EndpointData放到endponts和reciever中；同时将endpoint和其ref作为键值对放到endpointRef中。必须保证这一部分操作的原子性。

> 为什么在使用线程安全的数据结构之后仍然要使用synchronized关键字来做同步的限制？
>
> 线程安全的数据结构只能够保证其内部操作的原子性。而对于多次操作数据结构的原子性则不能保证其原子性。

##### private def unregisterRpcEndpoint(name: String): Unit

```scala
private def unregisterRpcEndpoint(name: String): Unit = {
  val data = endpoints.remove(name)
  if (data != null) {
    data.inbox.stop()
    receivers.offer(data)  // for the OnStop message
  }
}
```

解除endpoint在dispatcher上的注册，调用inbox的stop()方法，并将相应的EndpointData放到recievers的队列中。
不要在此处清理endpointRef，因为可能仍有消息在处理。
这个方法应该满足幂等性。

> 一次请求和多次请求对系统具有相同的副作用。

##### def stop(rpcEndpointRef: RpcEndpointRef): Unit

```scala
def stop(rpcEndpointRef: RpcEndpointRef): Unit = {
  synchronized {
    if (stopped) {
      // This endpoint will be stopped by Dispatcher.stop() method.
      return
    }
    unregisterRpcEndpoint(rpcEndpointRef.name)
  }
}
```

停止某个RpcEndpointRef的工作。

##### def postToAll(message: InboxMessage): Unit

```scala
def postToAll(message: InboxMessage): Unit = {
  val iter = endpoints.keySet().iterator()
  while (iter.hasNext) {
    val name = iter.next
    postMessage(name, message, (e) => logWarning(s"Message $message dropped. ${e.getMessage}"))
  }
}
```

将消息发送给所有已经注册在dispatcher上的RpcEndpoint。
对于已经注册的RpcEndpoint，逐个调用postMessage方法。

##### postMessage

```scala
private def postMessage(
    endpointName: String,
    message: InboxMessage,
    callbackIfStopped: (Exception) => Unit): Unit = {
  val error = synchronized {
    val data = endpoints.get(endpointName)
    if (stopped) {
      Some(new RpcEnvStoppedException())
    } else if (data == null) {
      Some(new SparkException(s"Could not find $endpointName."))
    } else {
      data.inbox.post(message)
      receivers.offer(data)
      None
    }
  }
  error.foreach(callbackIfStopped)
}
```

将message推送到endpoint的Inbox，并且将相应的EndpointData存储到receivers。

##### def postRemoteMessage(message: RequestMessage, callback: RpcResponseCallback): Unit

```scala
def postRemoteMessage(message: RequestMessage, callback: RpcResponseCallback): Unit = {
  val rpcCallContext =
    new RemoteNettyRpcCallContext(nettyEnv, callback, message.senderAddress)
  val rpcMessage = RpcMessage(message.senderAddress, message.content, rpcCallContext)
  postMessage(message.receiver.name, rpcMessage, (e) => callback.onFailure(e))
}
```

推送一个远程终端发送过来的消息。