# SparkContext源码阅读（一） 概述



SparkContext是Spark的入口，充当与Spark集群连接的角色，可以用于在集群上创建RDD，累加器和广播变量

每一个JVM只能跑一个SparkContext，在你创建一个新的之前，必须先终止当前active的SparkContext。

## 目录

##### SparkContext的创建



