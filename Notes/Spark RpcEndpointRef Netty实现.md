# Spark RpcEndpointRef Netty实现

NettyRpcEndpointRef是NettyRpcEnv下的RpcEndpointRef版本。

这个类会因为它被创建的地方不同而呈现出不同的表现或者说是行为。如果endpoint和其ref在同一端，ref只是RpcEndpointAddress的简单封装。

而在其他的机器节点上，则会收到此类的一个序列化版本。Rpc的实例会通过TransportClient保持与远程机器节点的通信，之后的消息通信也将依赖于TransportClient，而不是新建一个新的连接。

##### ask&send方法的实现

```scala
override def ask[T: ClassTag](message: Any, timeout: RpcTimeout): Future[T] = {
  nettyEnv.ask(new RequestMessage(nettyEnv.address, this, message), timeout)
}

override def send(message: Any): Unit = {
  require(message != null, "Message is null")
  nettyEnv.send(new RequestMessage(nettyEnv.address, this, message))
}
```

通过调用nettyEnv的send和ask的方法实现。



# RequestMessage 实现

RequestMessage即endpoint和其ref之间交互消息的具体实现。

## RequestMessage

##### val senderAddress: RpcAddress, 发送者

##### val receiver: NettyRpcEndpointRef, 接受者

##### val content: Any, 消息内容

*****

##### def serialize(nettyEnv: NettyRpcEnv): ByteBuffer

```scala
def serialize(nettyEnv: NettyRpcEnv): ByteBuffer = {
  val bos = new ByteBufferOutputStream()
  val out = new DataOutputStream(bos)
  try {
    writeRpcAddress(out, senderAddress)
    writeRpcAddress(out, receiver.address)
    out.writeUTF(receiver.name)
    val s = nettyEnv.serializeStream(out)
    try {
      s.writeObject(content)
    } finally {
      s.close()
    }
  } finally {
    out.close()
  }
  bos.toByteBuffer
}
```

序列化RequestMessge对象。

##### private def writeRpcAddress(out: DataOutputStream, rpcAddress: RpcAddress): Unit

```scala
private def writeRpcAddress(out: DataOutputStream, rpcAddress: RpcAddress): Unit = {
  if (rpcAddress == null) {
    out.writeBoolean(false)
  } else {
    out.writeBoolean(true)
    out.writeUTF(rpcAddress.host)
    out.writeInt(rpcAddress.port)
  }
}
```

向out中写入rpcAddress。

## 伴随对象

##### private def readRpcAddress(in: DataInputStream): RpcAddress

```scala
private def readRpcAddress(in: DataInputStream): RpcAddress = {
  val hasRpcAddress = in.readBoolean()
  if (hasRpcAddress) {
    RpcAddress(in.readUTF(), in.readInt())
  } else {
    null
  }
}
```

从数据流中读出一个bool值，标识数据中是否包含RpcAddress。
如果有，则从RpcAddress中读出host和port。

##### def apply(nettyEnv: NettyRpcEnv, client: TransportClient, bytes: ByteBuffer): RequestMessage

```scala
def apply(nettyEnv: NettyRpcEnv, client: TransportClient, bytes: ByteBuffer): RequestMessage = {
  val bis = new ByteBufferInputStream(bytes)
  val in = new DataInputStream(bis)
  try {
    val senderAddress = readRpcAddress(in)
    val endpointAddress = RpcEndpointAddress(readRpcAddress(in), in.readUTF())
    val ref = new NettyRpcEndpointRef(nettyEnv.conf, endpointAddress, nettyEnv)
    ref.client = client
    new RequestMessage(
      senderAddress,
      ref,
      nettyEnv.deserialize(client, bytes))
  } finally {
    in.close()
  }
}
```

构造一个RequestMessage。
从参数给定的ByteBuffer中，读出senderAddress；构造NettyRpcEndpointRef；从bytes中反序列化出content；作为参数构造RequestMessage。

