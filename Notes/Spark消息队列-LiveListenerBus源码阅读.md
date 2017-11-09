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

SparkListenerInterface是一个特征类，所有的listener都继承自SparkListenerInterface。
SparkLIstenerInterface中定义了listener在监听到事件后所触发的方法，这些方法都是抽象方法。

## LiveListenerBus

LiveListenerBus继承自SparkLiveListenerBus。

LiveListenerBus有两个延迟初始化的变量

- 事件队列的大小，从当前SparkContext持有的SparkConf中读出，不得小于0

- 事件队列，用于存储SparkListenerEvent的LinkedBlockingQueue

  > LinkedBlockingQueue是基于链表实现的阻塞队列，生产者端和消费者端分别采用了独立的锁来控制同步，因此生产数据和消费数据可以并发地进行。
  > 当生产者往队列中放入一个数据时，队列从生产者手中获取数据，并缓存在队列内部，而生产者立即返回；只有当队列缓冲区达到最大值缓存容量时，才会阻塞生产者线程，直到消费者从队列中消费到一份数据，生产者线程才会被唤醒；对于消费者也是如此。

##### 用Boolean类型的原子变量标记LiveListenerBus是否启动、是否停止

```scala
private val started = new AtomicBoolean(false)
private val stopped = new AtomicBoolean(false)
```

##### 用Long类型的原子变量标记被丢弃的事件数量

```scala
private val droppedEventsCounter = new AtomicLong(0L)
```

在将值记录到log中后，会重置此值，默认值为0。
同时也会记录上次log的时间。

##### 用Boolean类型的原子变量标识是否log被丢弃的事件

```scala
private val logDroppedEvent = new AtomicBoolean(false)
```

##### processingEvent

```scala
private var processingEvent = false
```

表明是否正在处理事件。

##### listenerThread

listenerThread是一个守护线程。

```scala
override def run(): Unit = Utils.tryOrStopSparkContext(sparkContext) {
      LiveListenerBus.withinListenerThread.withValue(true) {
        while (true) {
          eventLock.acquire()
          self.synchronized {
            processingEvent = true
          }
          try {
            val event = eventQueue.poll
            if (event == null) {
              if (!stopped.get) {
                throw new IllegalStateException("Polling `null` from eventQueue means" +
                  " the listener bus has been stopped. So `stopped` must be true")
              }
              return
            }
            postToAll(event)
          } finally {
            self.synchronized {
              processingEvent = false
            }
          }
        }
      }
    }
```

它的任务是不停的从eventQueue中获取事件，如果时间合法，并且ListenerBus没有被关停，就将它推送给所有已经注册的监听器。

运行时首先要获取信号量，即eventLock。eventLock的初始值为0，每有一个事件进入消息队列，eventLock便释放一次。此时阻塞在这里的线程就会被唤醒，去处理事件。

withinListenerThread是一个Boolean类型的DynamicVariable，在LiveListenerBus的伴随对象中被定义，这个变量可以允许Context去检查是否在调用Stop方法的时候，listenerThread仍然在运行。
它的初始值为false，在调用withValue时，值变为true，在执行完代码块中的逻辑之后，值恢复成false。这样Context就可以去检查listenerThread的运行状态

##### post(event: SparkListenerEvent): Unit

这个方法用于向消息队列中添加事件，是一个生产者方法。

```scala
def post(event: SparkListenerEvent): Unit = {
  if (stopped.get) {
    logError(s"$name has already stopped! Dropping event $event")
    return
  }
  val eventAdded = eventQueue.offer(event)
  if (eventAdded) {
    eventLock.release()
  } else {
    onDropEvent(event)
    droppedEventsCounter.incrementAndGet()
  }

  val droppedEvents = droppedEventsCounter.get
  if (droppedEvents > 0) {
    if (System.currentTimeMillis() - lastReportTimestamp >= 60 * 1000) {
      if (droppedEventsCounter.compareAndSet(droppedEvents, 0)) {
        val prevLastReportTimestamp = lastReportTimestamp
        lastReportTimestamp = System.currentTimeMillis()
        logWarning(s"Dropped $droppedEvents SparkListenerEvents since " +
          new java.util.Date(prevLastReportTimestamp))
      }
    }
  }
}
```

如果LiveListenerBus已经被关停，直接返回。
如果LiveListenerBus尚在运行，则向消息队列中添加事件，如果添加成功(eventAdded为true)，则释放一次信号量(eventLock)；如果添加失败(eventAdded为false)，即消息队列已满，则忽略、丢弃该事件，，并在log中记录。
同时也会每隔60秒去log一次被丢弃事件的数量。

##### start()

```scala
def start(): Unit = {
  if (started.compareAndSet(false, true)) {
    listenerThread.start()
  } else {
    throw new IllegalStateException(s"$name already started!")
  }
}
```

检查LiveListenerBus是否已经启动，如果还没有，则启动，即启动listenerThread。

##### stop()

```scala
def stop(): Unit = {
  if (!started.get()) {
    throw new IllegalStateException(s"Attempted to stop $name that has not yet started!")
  }
  if (stopped.compareAndSet(false, true)) {
    eventLock.release()
    listenerThread.join()
  } else {
    // Keep quiet
  }
}
```

释放eventLock信号量，并调用listenerThread的join方法，在listenerThread线程终止之后返回。

这里解释一下为什么这里要释放信号量
在listenerThread的run()方法中，它的返回条件是，从eventQueue中取出null，即eventQueue已经没有事件可以推送，此时run()函数返回，线程终止。
然而如果没有调用stop()方法，eventQueue为空时，eventLock的值也会为0，意味着run()方法将在尝试获取信号量时阻塞，等待新的事件被生产，如此run()方法无法返回，listenerThread也就无法终止。
因此，stop()方法中释放eventLock信号量，当eventQueue即事件队列为空时，仍能获取到信号量，run()方法返回，listenerThread终止，这就是stop()方法的原理。



