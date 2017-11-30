# Spark Rpc之Inbox源码阅读

Inbox用于暂存消息并向RpcEndpoint发送消息，Inbox的所有方法都是线程安全的，持有其相对应的NettyRpcEndpointRef和RpcEndpoint。

##### messages

```scala
@GuardedBy("this")
protected val messages = new java.util.LinkedList[InboxMessage]()
```

用于存储消息。

##### enableConcurrent

```scala
@GuardedBy("this")
private var enableConcurrent = false
```

是否允许并发的处理消息

##### numActiveThreads

```scala
@GuardedBy("this")
private var numActiveThreads = 0
```

同时处理消息的最大线程数目

```scala
inbox.synchronized {
  messages.add(OnStart)
}
```

OnStart是第一个要被处理的消息。

##### private def safelyCall(endpoint: RpcEndpoint)(action: => Unit): Unit

```scala
private def safelyCall(endpoint: RpcEndpoint)(action: => Unit): Unit = {
  try action catch {
    case NonFatal(e) =>
      try endpoint.onError(e) catch {
        case NonFatal(ee) => logError(s"Ignoring error", ee)
      }
  }
}
```

调用方法的闭包，定义异常处理部分，调用RpcEndpoint的onError()方法。

##### def process(dispatcher: Dispatcher): Unit

```scala
def process(dispatcher: Dispatcher): Unit = {
  var message: InboxMessage = null
  inbox.synchronized {
    if (!enableConcurrent && numActiveThreads != 0) {
      return
    }
    message = messages.poll()
    if (message != null) {
      numActiveThreads += 1
    } else {
      return
    }
  }
  while (true) {
    safelyCall(endpoint) {
      message match {
        case RpcMessage(_sender, content, context) =>
          try {
            endpoint.receiveAndReply(context).applyOrElse[Any, Unit](content, { msg =>
              throw new SparkException(s"Unsupported message $message from ${_sender}")
            })
          } catch {
            case NonFatal(e) =>
              context.sendFailure(e)
              throw e
          }

        case OneWayMessage(_sender, content) =>
          endpoint.receive.applyOrElse[Any, Unit](content, { msg =>
            throw new SparkException(s"Unsupported message $message from ${_sender}")
          })

        case OnStart =>
          endpoint.onStart()
          if (!endpoint.isInstanceOf[ThreadSafeRpcEndpoint]) {
            inbox.synchronized {
              if (!stopped) {
                enableConcurrent = true
              }
            }
          }

        case OnStop =>
          val activeThreads = inbox.synchronized { inbox.numActiveThreads }
          assert(activeThreads == 1,
            s"There should be only a single active thread but found $activeThreads threads.")
          dispatcher.removeRpcEndpointRef(endpoint)
          endpoint.onStop()
          assert(isEmpty, "OnStop should be the last message")

        case RemoteProcessConnected(remoteAddress) =>
          endpoint.onConnected(remoteAddress)

        case RemoteProcessDisconnected(remoteAddress) =>
          endpoint.onDisconnected(remoteAddress)

        case RemoteProcessConnectionError(cause, remoteAddress) =>
          endpoint.onNetworkError(cause, remoteAddress)
      }
    }

    inbox.synchronized {
      if (!enableConcurrent && numActiveThreads != 1) {
        numActiveThreads -= 1
        return
      }
      message = messages.poll()
      if (message == null) {
        numActiveThreads -= 1
        return
      }
    }
  }
}
```

处理暂存的消息。

在不允许并发处理消息的配置下，最多只能有一个线程在处理消息；
从存储message的LinkedList中取出一个inboxMessage，并将numActiveThreads+1。
如果message为null，即已经没有消息要处理，则return。

对message进行模式匹配，InboxMessage一共派生出了七种message类型，可以分为三类。
**第一类：**RpcMessage和OneWayMessage，触发RpcEndpoint端的方法。RpcMessage有回复，双向；OneWayMessage无回复。

**第二类： **OnStart和OnStop，触发endpoint的开始和结束事件。
OnStart，调用endpoint的start()方法，并将enableConcurrent设为true；OnStop，activeThread为1时才能执行成功，调用dispatcher的removeRpcEndpointRef方法，然后调用endpoint的stop()方法。

**第三类：**RemoteProcessConnected、RemoteProcessDisconnected和RemoteProcessConnectionError。和网络、连接相关，分别触发相应的方法。

如果处理过Onstop事件，或者是存储message的list为空，即没有message需要被处理，则return；否则则取出一个message，继续循环。