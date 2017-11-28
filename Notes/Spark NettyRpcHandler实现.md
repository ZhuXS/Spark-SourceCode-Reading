# Spark NettyRpcHandler实现

RpcHandler分发消息到已经注册的endpoint。

RpcHandler维护了所有与其保持保持通信的TransportClient的信息，所以handler知道应该发送rpc到哪个客户终端上。

##### private val remoteAddresses = new ConcurrentHashMap [RpcAddress, RpcAddress] ()

维护了所有远端RpcEnv的地址。

##### private def internalReceive(client: TransportClient, message: ByteBuffer): RequestMessage

```scala
private def internalReceive(client: TransportClient, message: ByteBuffer): RequestMessage = {
  val addr = client.getChannel().remoteAddress().asInstanceOf[InetSocketAddress]
  assert(addr != null)
  val clientAddr = RpcAddress(addr.getHostString, addr.getPort)
  val requestMessage = RequestMessage(nettyEnv, client, message)
  if (requestMessage.senderAddress == null) {
    new RequestMessage(clientAddr, requestMessage.receiver, requestMessage.content)
  } else {
    val remoteEnvAddress = requestMessage.senderAddress
    if (remoteAddresses.putIfAbsent(clientAddr, remoteEnvAddress) == null) {
      dispatcher.postToAll(RemoteProcessConnected(remoteEnvAddress))
    }
    requestMessage
  }
}
```

##### recieve方法

```scala
override def receive(
    client: TransportClient,
    message: ByteBuffer,
    callback: RpcResponseCallback): Unit = {
  val messageToDispatch = internalReceive(client, message)
  dispatcher.postRemoteMessage(messageToDispatch, callback)
}
```

有回调函数的版本。