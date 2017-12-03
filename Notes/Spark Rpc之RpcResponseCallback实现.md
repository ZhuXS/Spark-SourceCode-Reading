# Spark Rpc之RpcResponseCallback实现

单次Rpc执行结果回调的封装。

```scala
public interface RpcResponseCallback {
  void onSuccess(ByteBuffer response);
  void onFailure(Throwable e);
}
```

在onSuccess被调用后，rpc调用得到的response将会被回收，内容将会变得无效。