# Spark消息队列-LiveListenerBus源码阅读

## 概述

```scala
private[spark] val listenerBus = new LiveListenerBus(this)
```

在Spark的创建过程中，会创建一个LiveListenerBus的实例。主要功能是

- 消息缓存
- 消息分发

是Spark的监听器总线，需要发送事件消息的组件将发生的事件消息提交到总线，然后总线将事件消息转发给一个个注册在它上面的监听器，最后监听器对事件进行响应。
LiveListenerBus继承自SparkListenerBus，SparkListenerBus继承自ListenerBus。

## ListenerBus

> An event bus which posts events to its listeners

```scala
private[spark] trait ListenerBus[L <: AnyRef, E] extends Logging
```

ListenerBus是一个泛型特征类

> scala中的泛型称为类型参数化，使用[]表示类型

ListenerBus接受两个类型参数

- L，上界为AnyRef，listener类型
- E，event类型

##### listeners

```scala
private[spark] val listeners = new CopyOnWriteArrayList[L]
```

listeners是一个L（listener）类型的CopyOnWriteArrayList，以保证我们可以对listeners这个ArrayList进行并发的读，而不需要加锁。   

> CopyOnWriteArrayList是一种COW容器，COW即写时复制的容器。通俗的理解是当我们往一个容器添加元素的时候，不直接往当前容器添加，而是将当前容器进行Copy，复制一个新的容器，然后新的容器里添加元素，添加完元素之后，再将原容器的引用指向新的容器。这样做的好处是我们可以对CopyOnWrite容器进行并发的读，而不需要加锁，因为当前容器不会添加任何元素。所以CopyOnWrite容器是一种读写分离的思想，读和写不同的容器。

##### final def addListener(listener: L): Unit

```scala
final def addListener(listener: L): Unit = {
  listeners.add(listener)
}
```

注册一个新的Listener，该方法是线程安全的。

##### final def removeListener(listener: L): Unit

```scala
final def removeListener(listener: L): Unit = {
  listeners.remove(listener)
}
```

移除一个listener，该方法同样也是线程安全的。

##### protected def doPostEvent(listener: L, event: E): Unit

该方法是一个抽象方法，其具体实现要由继承自ListenerBus的类负责，用于将一个event推送到一个特定的listener。

##### def postToAll(event: E): Unit

```scala
def postToAll(event: E): Unit = {
  val iter = listeners.iterator
  while (iter.hasNext) {
    val listener = iter.next()
    try {
      doPostEvent(listener, event)
    } catch {
      case NonFatal(e) =>
        logError(s"Listener ${Utils.getFormattedClassName(listener)} threw an exception", e)
    }
  }
}
```

将事件推送给所有已经注册到总线的监听器。用迭代器遍历listener，调用doPostEvent(listener: L, event: E)，逐个将消息推送。

## SparkListenerBus

SparkListenerBus集成了ListenerBus

```scala
private[spark] trait SparkListenerBus
  extends ListenerBus[SparkListenerInterface, SparkListenerEvent]
```

在继承ListenerBus的时候指定了具体的类型参数，Listener类型为SparkListenerInterface，Event类型为SparkListenerEvent。

SparkListenerBus同时实现了doPostEvent()方法。

SparkListenerEvent是一个特征类，它衍生了很多case class，以标示不同的事件模式。这些事件模式会在doPostEvent的时候被识别出来，以触发不同的事件。

## LiveListenerBus

LiveListenerBus继承自SparkLiveListenerBus。

LiveListenerBus有两个延迟初始化的变量

- 事件队列的大小，从当前SparkContext持有的SparkConf中读出，不得小于0
- 事件队列，用于存储SparkListenerEvent的LinkedBlockingQueue