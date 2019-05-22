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

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;

/**
 * ProxyFactory. (API/SPI, Singleton, ThreadSafe)
 * stub=org.apache.dubbo.rpc.proxy.wrapper.StubProxyFactoryWrapper
 * jdk=org.apache.dubbo.rpc.proxy.jdk.JdkProxyFactory
 * javassist=org.apache.dubbo.rpc.proxy.javassist.JavassistProxyFactory
 *
 * 默认使用的是 org.apache.dubbo.rpc.proxy.javassist.JavassistProxyFactory
 */
@SPI("javassist")
public interface ProxyFactory {

    /**
     * create proxy.
     *
     * 根据invoker创建一个代理类 其中操作的方式都是调用了invoker的invoke方法
     * 在消费者方使用其为接口生成一个代理类,其中的invoker调用的是远程的方法
     * @param invoker
     * @return proxy
     */
    @Adaptive({Constants.PROXY_KEY})
    <T> T getProxy(Invoker<T> invoker) throws RpcException;

    /**
     * create proxy.
     *
     * @param invoker
     * @param generic 是否是范型服务
     * @return proxy
     *
     */
    @Adaptive({Constants.PROXY_KEY})
    <T> T getProxy(Invoker<T> invoker, boolean generic) throws RpcException;

    /**
     * create invoker.
     *
     * 根据对象实例创建一个Invoker,invoker调用的方法其实就是执行的对象上面的方法
     * 在服务提供者方使用,和getProxy刚好是相反的
     * @param <T>
     * @param proxy
     * @param type
     * @param url
     * @return invoker
     */
    @Adaptive({Constants.PROXY_KEY})
    <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) throws RpcException;

}