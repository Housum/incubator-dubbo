/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;

/**
 * Protocol. (API/SPI, Singleton, ThreadSafe)
 * <p>
 * SPI("dubbo")表示默认的协议是dubbo
 * <p>
 * 如果在调用使用过Adaptive注解的方法的时候传入的参数中属性URL
 * 中org.apache.dubbo.common.URL#protocol为空的时候那么使用默认的dubbo协议
 * 否则的话,那么使用指定的协议 而协议配置则是通过SPI的方式进行在/META-INF/services
 * 或者/META-INF/dubbo/internal中进行配置的
 * 例如：
 * registry=com.alibaba.dubbo.registry.integration.RegistryProtocol
 * dubbo=com.alibaba.dubbo.rpc.protocol.dubbo.DubboProtocol
 * filter=com.alibaba.dubbo.rpc.protocol.ProtocolFilterWrapper
 * listener=com.alibaba.dubbo.rpc.protocol.ProtocolListenerWrapper
 * mock=com.alibaba.dubbo.rpc.support.MockProtocol
 * injvm=com.alibaba.dubbo.rpc.protocol.injvm.InjvmProtocol
 * rmi=com.alibaba.dubbo.rpc.protocol.rmi.RmiProtocol
 * hessian=com.alibaba.dubbo.rpc.protocol.hessian.HessianProtocol
 * com.alibaba.dubbo.rpc.protocol.http.HttpProtocol
 * com.alibaba.dubbo.rpc.protocol.webservice.WebServiceProtocol
 * thrift=com.alibaba.dubbo.rpc.protocol.thrift.ThriftProtocol
 * nova=com.youzan.nova.rpc.protocol.nova.NovaProtocol
 * memcached=com.alibaba.dubbo.rpc.protocol.memcached.MemcachedProtocol
 * redis=com.alibaba.dubbo.rpc.protocol.redis.RedisProtocol
 * rest=com.alibaba.dubbo.rpc.protocol.rest.RestProtocol
 * <p>
 * 其中的key就是在org.apache.dubbo.common.URL#protocol指定的,这个SPI中的
 * dubbo指的就是dubbo=com.alibaba.dubbo.rpc.protocol.dubbo.DubboProtocol中的value
 */
@SPI("dubbo")
public interface Protocol {

    /**
     * Get default port when user doesn't config the port.
     * 获取默认的端口
     *
     * @return default port
     */
    int getDefaultPort();

    /**
     * Export service for remote invocation: <br>
     * 1. Protocol should record request source address after receive a request:
     * RpcContext.getContext().setRemoteAddress();<br>
     * 2. export() must be idempotent, that is, there's no difference between invoking once and invoking twice when
     * export the same URL<br>
     * 3. Invoker instance is passed in by the framework, protocol needs not to care <br>
     *
     * 暴露一个本地的服务,必须支持幂等
     *
     * @param <T>     Service type
     * @param invoker Service invoker
     * @return exporter reference for exported service, useful for unexport the service later
     * @throws RpcException thrown when error occurs during export the service, for example: port is occupied
     */
    @Adaptive
    <T> Exporter<T> export(Invoker<T> invoker) throws RpcException;

    /**
     * Refer a remote service: <br>
     * 1. When user calls `invoke()` method of `Invoker` object which's returned from `refer()` call, the protocol
     * needs to correspondingly execute `invoke()` method of `Invoker` object <br>
     * 2. It's protocol's responsibility to implement `Invoker` which's returned from `refer()`. Generally speaking,
     * protocol sends remote request in the `Invoker` implementation. <br>
     * 3. When there's check=false set in URL, the implementation must not throw exception but try to recover when
     * connection fails.
     *
     * 引用远程的服务，当 check=false的时候,实现不会抛出异常当连接失败的时候(在调用此方法的时候,并不代表真是调用Invoker的时候不抛异常)
     * @param <T>  Service type
     * @param type Service class
     * @param url  URL address for the remote service
     * @return invoker service's local proxy
     * @throws RpcException when there's any error while connecting to the service provider
     */
    @Adaptive
    <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException;

    /**
     * Destroy protocol: <br>
     * 1. Cancel all services this protocol exports and refers <br>
     * 2. Release all occupied resources, for example: connection, port, etc. <br>
     * 3. Protocol can continue to export and refer new service even after it's destroyed.
     *
     * 关闭暴露的服务和引用的服务.服务关闭之后还能够继续暴露和引用服务
     *
     */
    void destroy();

}