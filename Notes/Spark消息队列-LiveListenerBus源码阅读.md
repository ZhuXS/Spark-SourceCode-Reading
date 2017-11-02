# Spark消息队列-LiveListenerBus源码阅读

## 概述

```scala
private[spark] val listenerBus = new LiveListenerBus(this)
```

在Spark的创建过程中，会创建一个LiveListenerBus的实例。主要功能是

- 消息缓存
- 消息分发

LiveListenerBus继承自SparkListenerBus，SparkListenerBus继承自ListenerBus。

## ListenerBus源码阅读

> An event bus which posts events to its listeners

```scala
private[spark] trait ListenerBus[L <: AnyRef, E] extends Logging
```

ListenerBus是一个泛型特征类

> scala中的泛型称为类型参数化，使用[]表示类型

ListenerBus接受两个类型参数

- L，上界为AnyRef，listener类型
- E，event类型

