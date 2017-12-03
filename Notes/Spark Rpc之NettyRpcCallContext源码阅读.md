# Spark Rpc之NettyRpcCallContext实现

RpcCallContext用于RpcEndpoint回复消息或失败的回调

## 抽象类 NettyRpcCallContext

```scala
private[netty] abstract class NettyRpcCallContext(override val senderAddress: RpcAddress)
  extends RpcCallContext with Logging {

  protected def send(message: Any): Unit

  override def reply(response: Any): Unit = {
    send(response)
  }

  override def sendFailure(e: Throwable): Unit = {
    send(RpcFailure(e))
  }

}
```

继承自RpcCallContext。

## LocalNettyRpcCallContext

```scala
private[netty] class LocalNettyRpcCallContext(
    senderAddress: RpcAddress,
    p: Promise[Any])
  extends NettyRpcCallContext(senderAddress) {
  override protected def send(message: Any): Unit = {
    p.success(message)
  }
}
```

sender和reciever在同一个进程，可以通过Promise进行异步地通信。

## RemoteNettyRpcCallContext

```scala
private[netty] class RemoteNettyRpcCallContext(
    nettyEnv: NettyRpcEnv,
    callback: RpcResponseCallback,
    senderAddress: RpcAddress)
  extends NettyRpcCallContext(senderAddress) {

  override protected def send(message: Any): Unit = {
    val reply = nettyEnv.serialize(message)
    callback.onSuccess(reply)
  }
}
```

使用RpcResponseCallback来发送调用结果。