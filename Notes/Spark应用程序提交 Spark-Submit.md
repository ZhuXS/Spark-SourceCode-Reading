# Spark 应用程序提交 Spark-Submit

## Spark 通过 Spark-Submit命令提交任务

通过命令的方式可以向Spark的集群提交任务

` spark-submit --master spark://hadoop3:7077 --deploy-mode client --class org.apache.spark.examples.SparkPi ../lib/spark-examples-1.3.0-hadoop2.3.0.jar `

部分参数说明

- —class	*job的Main Class*

- —conf  

- —deploy-mode   *Client or cluster*

- —driver-class-path   *driver程序的类路径*

- —driver-cores   *driver程序使用的CPU个数，仅限于Stand Alone模式*

- —driver-java-options   *driver程序的jvm参数*

- —driver-library-path   *driver程序的库路径*

- —driver-memory   *driver程序使用的内存大小*

- —executor-memory  *executor的内存大小*

- —files  *放在每一个executor工作目录的文件列表*

- —jar  *driver依赖的第三方jar包*

- —master  *master地址*

- —name  *Application name*

- —properties-file  *设置应用程序属性的文件路径*


## 提交任务的过程

##### SparkSubmit区分三种类型

``` scala
private[deploy] object SparkSubmitAction extends Enumeration {  //scope 为 deploy package，枚举类型
  type SparkSubmitAction = Value //将Value的类型暴露给外界使用
  val SUBMIT, KILL, REQUEST_STATUS = Value  //action of an application，Submit、Kill和Request the status of an application
                                            //定义具体的枚举实例
}
```

- submit 提交任务
- kill 终止任务
- request status 获取任务状态

##### 解析、填充、验证 参数

``` scala
val appArgs = new SparkSubmitArguments(args) 
```

SparkSubmitArguments对输入参数进行解析，合法性的验证，对于缺失值也要进行补充

##### 根据Ation区分不同的操作

```scala
case SparkSubmitAction.SUBMIT => submit(appArgs)  //提交
case SparkSubmitAction.KILL => kill(appArgs)  //终止
case SparkSubmitAction.REQUEST_STATUS => requestStatus(appArgs)  //获取状态
```

##### Submit

首先要准备submit的环境

```scala
val (childArgs, childClasspath, sysProps, childMainClass) = prepareSubmitEnvironment(args)
```

主要是加载依赖和封装参数，然后运行Main Class的Main方法

```scala
runMain(childArgs, childClasspath, sysProps, childMainClass, args.verbose)
```

使用反射的方式

```scala
try {
      mainMethod.invoke(null, childArgs.toArray)  //执行方法
    } catch {
      case t: Throwable =>
        findCause(t) match {
          case SparkUserAppException(exitCode) =>
            System.exit(exitCode)

          case t: Throwable =>
            throw t
        }
    }
```

##### Kill && Request_Status

新建一个RestSubmissionClient，向集群发送一个Rest风格的请求，并返回一个SubmitRestProtocolResponse

以Kill为例

首先构造请求的url

```scala
val url = getKillUrl(m, submissionId)
```

发送请求，捕捉response和exception并解析

```scala
try {
        response = post(url)
        response match {
          case k: KillSubmissionResponse =>
            if (!Utils.responseFromBackup(k.message)) {
              handleRestResponse(k)
              handled = true
            }
          case unexpected =>
            handleUnexpectedRestResponse(unexpected)
        }
      } catch {
        case e: SubmitRestConnectionException =>
          if (handleConnectionException(m)) {
            throw new SubmitRestConnectionException("Unable to connect to server", e)
          }
      }
```





## 源码清单

- *org.apache.spark.launcher.SparkSubmitOptionParser*

- *org.apache.spark.launcher.SparkSubmitArgumentsParser*

- *org.apache.spark.launcher.SparkSubmitArguments*

- *org.apache.spark.deploy.SparkSubmit*

- *org.apache.spark.deploy.rest* 

  ​