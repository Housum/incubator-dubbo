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
package org.apache.dubbo.config;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.ClassHelper;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.invoker.DelegateProviderMetaDataInvoker;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.metadata.integration.MetadataReportService;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.ConfiguratorFactory;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ProviderModel;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.Constants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.utils.NetUtils.getAvailablePort;
import static org.apache.dubbo.common.utils.NetUtils.getLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidPort;

/**
 * ServiceConfig
 *
 * provider端暴露的服务
 *
 * @export
 */
public class ServiceConfig<T> extends AbstractServiceConfig {

    private static final long serialVersionUID = 3033787999037024738L;

    /**
     * The {@link Protocol} implementation with adaptive functionality,it will be different in different scenarios.
     * A particular {@link Protocol} implementation is determined by the protocol attribute in the {@link URL}.
     * For example:
     *
     * <li>when the url is registry://224.5.6.7:1234/org.apache.dubbo.registry.RegistryService?application=dubbo-sample,
     * then the protocol is <b>RegistryProtocol</b></li>
     *
     * <li>when the url is dubbo://224.5.6.7:1234/org.apache.dubbo.config.api.DemoService?application=dubbo-sample, then
     * the protocol is <b>DubboProtocol</b></li>
     * <p>
     * Actually，when the {@link ExtensionLoader} init the {@link Protocol} instants,it will automatically wraps two
     * layers, and eventually will get a <b>ProtocolFilterWrapper</b> or <b>ProtocolListenerWrapper</b>
     *
     * 这里初始化的是执行协议的门面类 具体的执行协议是通过传入org.apache.dubbo.rpc.Protocol#export(org.apache.dubbo.rpc.Invoker)
     * 或者org.apache.dubbo.rpc.Protocol#refer(java.lang.Class, org.apache.dubbo.common.URL)的URL参数中指定的协议(protocol),如果没有传入
     * 那么默认使用的是dubbo协议 在具体的Protocol上面 自动的装饰了两个类ProtocolFilterWrapper ProtocolListenerWrapper（在加载的时候从配置文件中加载进去的）
     */
    private static final Protocol protocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * A {@link ProxyFactory} implementation that will generate a exported service proxy,the JavassistProxyFactory is its
     * default implementation
     *
     * 生成暴露服务的代理类,默认使用的是JavassistProxyFactory
     */
    private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    /**
     * A random port cache, the different protocols who has no port specified have different random port
     * 对于不同的协议 如果没有指定端口的话，那么将会生成一个随机的端口
     *
     */
    private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();

    /**
     * A delayed exposure service timer
     * 对于延迟暴露服务的 通过延迟任务执行
     */
    private static final ScheduledExecutorService delayExportExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DubboServiceDelayExporter", true));

    /**
     * The urls of the services exported
     * 服务暴露的地址
     *
     */
    private final List<URL> urls = new ArrayList<URL>();

    /**
     * The exported services
     * 暴露的服务
     */
    private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();

    /**
     * The interface name of the exported service
     * 暴露服务的接口
     *
     */
    private String interfaceName;

    /**
     * The interface class of the exported service
     * 暴露服务的接口类
     */
    private Class<?> interfaceClass;

    /**
     * The reference of the interface implementation
     * 引用的实现
     */
    private T ref;

    /**
     * The service name
     * 服务的名字 名字一般都为类的全路径
     */
    private String path;

    /**
     * The method configuration
     * org.apache.dubbo.config.MethodConfig。同时该标签为 <dubbo:service> 或 <dubbo:reference> 的子标签
     * 如果为空的话 可以通过<dubbo:service> 或 <dubbo:reference>获取到
     */
    private List<MethodConfig> methods;

    /**
     * The provider configuration
     */
    private ProviderConfig provider;

    /**
     * The providerIds
     */
    private String providerIds;

    /**
     * Whether the provider has been exported
     */
    private transient volatile boolean exported;

    /**
     * The flag whether a service has unexported ,if the method unexported is invoked, the value is true
     */
    private transient volatile boolean unexported;

    /**
     * whether it is a GenericService
     */
    private volatile String generic;

    public ServiceConfig() {
    }

    public ServiceConfig(Service service) {
        appendAnnotation(Service.class, service);
        //构造方法
        setMethods(MethodConfig.constructMethodConfig(service.methods()));
    }

    @Deprecated
    private static List<ProtocolConfig> convertProviderToProtocol(List<ProviderConfig> providers) {
        if (CollectionUtils.isEmpty(providers)) {
            return null;
        }
        List<ProtocolConfig> protocols = new ArrayList<ProtocolConfig>(providers.size());
        for (ProviderConfig provider : providers) {
            protocols.add(convertProviderToProtocol(provider));
        }
        return protocols;
    }

    @Deprecated
    private static List<ProviderConfig> convertProtocolToProvider(List<ProtocolConfig> protocols) {
        if (CollectionUtils.isEmpty(protocols)) {
            return null;
        }
        List<ProviderConfig> providers = new ArrayList<ProviderConfig>(protocols.size());
        for (ProtocolConfig provider : protocols) {
            providers.add(convertProtocolToProvider(provider));
        }
        return providers;
    }

    @Deprecated
    private static ProtocolConfig convertProviderToProtocol(ProviderConfig provider) {
        ProtocolConfig protocol = new ProtocolConfig();
        protocol.setName(provider.getProtocol().getName());
        protocol.setServer(provider.getServer());
        protocol.setClient(provider.getClient());
        protocol.setCodec(provider.getCodec());
        protocol.setHost(provider.getHost());
        protocol.setPort(provider.getPort());
        protocol.setPath(provider.getPath());
        protocol.setPayload(provider.getPayload());
        protocol.setThreads(provider.getThreads());
        protocol.setParameters(provider.getParameters());
        return protocol;
    }

    @Deprecated
    private static ProviderConfig convertProtocolToProvider(ProtocolConfig protocol) {
        ProviderConfig provider = new ProviderConfig();
        provider.setProtocol(protocol);
        provider.setServer(protocol.getServer());
        provider.setClient(protocol.getClient());
        provider.setCodec(protocol.getCodec());
        provider.setHost(protocol.getHost());
        provider.setPort(protocol.getPort());
        provider.setPath(protocol.getPath());
        provider.setPayload(protocol.getPayload());
        provider.setThreads(protocol.getThreads());
        provider.setParameters(protocol.getParameters());
        return provider;
    }

    private static Integer getRandomPort(String protocol) {
        protocol = protocol.toLowerCase();
        return RANDOM_PORT_MAP.getOrDefault(protocol, Integer.MIN_VALUE);
    }

    private static void putRandomPort(String protocol, Integer port) {
        protocol = protocol.toLowerCase();
        if (!RANDOM_PORT_MAP.containsKey(protocol)) {
            RANDOM_PORT_MAP.put(protocol, port);
            logger.warn("Use random available port(" + port + ") for protocol " + protocol);
        }
    }

    public URL toUrl() {
        return urls.isEmpty() ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }

    @Parameter(excluded = true)
    public boolean isExported() {
        return exported;
    }

    @Parameter(excluded = true)
    public boolean isUnexported() {
        return unexported;
    }

    public void checkAndUpdateSubConfigs() {
        // Use default configs defined explicitly on global configs
        completeCompoundConfigs();
        // Config Center should always being started first.
        //将配置中心进行初始化
        startConfigCenter();
        //检查参数 start

        //检查 provider
        checkDefault();
        //检查application
        checkApplication();
        //检查registry
        checkRegistry();
        //检查protocol
        checkProtocol();
        //检查参数 end
        this.refresh();
        checkMetadataReport();

        //<dubbo:service> 必须存在 interface
        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
        }

        //GenericService 范化服务 @link https://www.cnblogs.com/notlate/p/10127942.html
        if (ref instanceof GenericService) {
            interfaceClass = GenericService.class;
            if (StringUtils.isEmpty(generic)) {
                generic = Boolean.TRUE.toString();
            }
        } else {
            try {
                //获取到接口的class
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            //检查该接口的方法
            checkInterfaceAndMethods(interfaceClass, methods);
            //检查ref是否合法
            checkRef();
            generic = Boolean.FALSE.toString();
        }
        //local 已废弃，请使用stub
        if (local != null) {
            if ("true".equals(local)) {
                local = interfaceName + "Local";
            }
            Class<?> localClass;
            try {
                localClass = ClassHelper.forNameWithThreadContextClassLoader(local);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceName);
            }
        }
        //设为true，表示使用缺省代理类名，即：接口名 + Stub后缀，服务接口客户端本地代理类名，用于在客户端执行本地逻辑，如本地缓存等，
        //该本地代理类的构造函数必须允许传入远程代理对象，构造函数如：public XxxServiceStub(XxxService xxxService)
        //https://www.cnblogs.com/hzhuxin/p/8250602.html 该类类似于在远程RPC上面做了一层Proxy
        if (stub != null) {
            if ("true".equals(stub)) {
                stub = interfaceName + "Stub";
            }
            Class<?> stubClass;
            try {
                //还在 后缀必须是Stub
                stubClass = ClassHelper.forNameWithThreadContextClassLoader(stub);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(stubClass)) {
                throw new IllegalStateException("The stub implementation class " + stubClass.getName() + " not implement interface " + interfaceName);
            }
        }
        checkStubAndLocal(interfaceClass);

        //mock service https://www.jianshu.com/p/ce8de35986cf
        checkMock(interfaceClass);
    }

    public synchronized void export() {

        //检查以及更新配置
        checkAndUpdateSubConfigs();

        if (!shouldExport()) {
            return;
        }

        if (shouldDelay()) {
            delayExportExecutor.schedule(this::doExport, delay, TimeUnit.MILLISECONDS);
        } else {
            doExport();
        }
    }

    private boolean shouldExport() {
        Boolean shouldExport = getExport();
        if (shouldExport == null && provider != null) {
            shouldExport = provider.getExport();
        }

        // default value is true
        if (shouldExport == null) {
            return true;
        }

        return shouldExport;
    }

    private boolean shouldDelay() {
        Integer delay = getDelay();
        if (delay == null && provider != null) {
            delay = provider.getDelay();
        }
        return delay != null && delay > 0;
    }

    /**
     * 开始暴露服务
     */
    protected synchronized void doExport() {
        if (unexported) {
            throw new IllegalStateException("The service " + interfaceClass.getName() + " has already unexported!");
        }
        if (exported) {
            return;
        }
        exported = true;

        if (StringUtils.isEmpty(path)) {
            //path设置为接口名
            path = interfaceName;
        }
        doExportUrls();
    }

    private void checkRef() {
        // reference should not be null, and is the implementation of the given interface
        if (ref == null) {
            throw new IllegalStateException("ref not allow null!");
        }
        if (!interfaceClass.isInstance(ref)) {
            throw new IllegalStateException("The class "
                    + ref.getClass().getName() + " unimplemented interface "
                    + interfaceClass + "!");
        }
    }

    public synchronized void unexport() {
        if (!exported) {
            return;
        }
        if (unexported) {
            return;
        }
        if (!exporters.isEmpty()) {
            for (Exporter<?> exporter : exporters) {
                try {
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn("Unexpected error occured when unexport " + exporter, t);
                }
            }
            exporters.clear();
        }
        unexported = true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doExportUrls() {
        //将register解析成URL 这里返回的都是注册中心的地址
        List<URL> registryURLs = loadRegistries(true);
        //当有多个协议的时候，针对于每一个协议都注册一个服务
        for (ProtocolConfig protocolConfig : protocols) {
            //这里的"path" 是提供者上下文路径，为服务path的前缀,默认的"path"为interfaceName
            //这里的pathKey: group/path:version
            String pathKey = URL.buildKey(getContextPath(protocolConfig).map(p -> p + "/" + path).orElse(path), group, version);
            //服务提供者模型 使用组+接口名称+版本号 表示唯一的一个暴露服务
            ProviderModel providerModel = new ProviderModel(pathKey, ref, interfaceClass);
            //将需要暴露的服务放入到ApplicationModel中
            ApplicationModel.initProviderModel(pathKey, providerModel);
            //对于有多个协议的情况 每种协议都需要暴露  注册到所有的注册中心
            doExportUrlsFor1Protocol(protocolConfig, registryURLs);
        }
    }

    @SuppressWarnings("unchecked")
    private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs) {
        //协议 这里就影响到暴露服务的方式 @see org.apache.dubbo.registry.integration.RegistryProtocol
        String name = protocolConfig.getName();
        if (StringUtils.isEmpty(name)) {
            name = Constants.DUBBO;
        }

        Map<String, String> map = new HashMap<String, String>();

        //将能够获取到的参数都获取到map中,即参数注解了"Parameter"的getter方法 这部分的参数都将放到暴露服务的参数部分"?"后面
        map.put(Constants.SIDE_KEY, Constants.PROVIDER_SIDE);
        //获取动态参数 dubbo版本 软件版本 时间戳 pid
        appendRuntimeParameters(map);
        appendParameters(map, metrics);
        //dubbo:application
        appendParameters(map, application);
        //dubbo:module
        appendParameters(map, module);
        //dubbo:provider 这部分的参数为 default.key
        appendParameters(map, provider, Constants.DEFAULT_KEY);
        //dubbo:protocol
        appendParameters(map, protocolConfig);
        //dubbo:service
        appendParameters(map, this);

        //如果配置了method 那么这部分的方法也将会被加入到注册路径中去
        if (CollectionUtils.isNotEmpty(methods)) {
            for (MethodConfig method : methods) {
                //这部分的属性都加上方法名称作为前缀
                appendParameters(map, method, method.getName());
                //设置method的重试次数 url中的全路径:	<methodName>.retries
                String retryKey = method.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        map.put(method.getName() + ".retries", "0");
                    }
                }
                //参数配置 如果参数配置存在的话 那么也将其加入到服务url中去
                List<ArgumentConfig> arguments = method.getArguments();
                if (CollectionUtils.isNotEmpty(arguments)) {
                    for (ArgumentConfig argument : arguments) {
                        // convert argument type
                        if (argument.getType() != null && argument.getType().length() > 0) {
                            Method[] methods = interfaceClass.getMethods();
                            // visit all methods
                            //遍历所有的方法 找到配置的参数所归属的方法 进而对其进行校验
                            if (methods != null && methods.length > 0) {
                                for (int i = 0; i < methods.length; i++) {
                                    String methodName = methods[i].getName();
                                    // target the method, and get its signature
                                    if (methodName.equals(method.getName())) {
                                        Class<?>[] argtypes = methods[i].getParameterTypes();
                                        // one callback in the method
                                        //参数的配置主要的作用就是为了配置回调 @link http://dubbo.apache.org/zh-cn/docs/user/references/xml/dubbo-argument.html

                                        //这里的index必须要和type能够匹配的上 否则的话 将会报错
                                        if (argument.getIndex() != -1) {
                                            //TODO index和type只能二选一
                                            if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {
                                                //其中的参数是key的前缀是 "methodName.argumentIndex"
                                                appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                                            } else {
                                                throw new IllegalArgumentException("Argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                            }
                                        } else {
                                            //如果没有index配置的话 那么将type转化为index
                                            // multiple callbacks in the method
                                            for (int j = 0; j < argtypes.length; j++) {
                                                Class<?> argclazz = argtypes[j];
                                                if (argclazz.getName().equals(argument.getType())) {
                                                    appendParameters(map, argument, method.getName() + "." + j);
                                                    if (argument.getIndex() != -1 && argument.getIndex() != j) {
                                                        throw new IllegalArgumentException("Argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (argument.getIndex() != -1) {
                            appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                        } else {
                            throw new IllegalArgumentException("Argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
                        }

                    }
                }
            } // end of methods for
        }

        //如果是范型服务 https://www.cnblogs.com/notlate/p/10127942.html
        //@see org.apache.dubbo.rpc.filter.GenericImplFilter
        if (ProtocolUtils.isGeneric(generic)) {
            map.put(Constants.GENERIC_KEY, generic);
            map.put(Constants.METHODS_KEY, Constants.ANY_VALUE);
        } else {
            //获取版本信息
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                map.put(Constants.REVISION_KEY, revision);
            }

            //返回一个被代理的对象 对象被调用只能通过 org.apache.dubbo.common.bytecode.Wrapper.invokeMethod 指定对象,方法名,参数类型,参数列表
            //可以做到接口的实现类的调用方法的收敛和统一调用 这里就需要关注具体的类型了
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {
                logger.warn("No method found in service interface " + interfaceClass.getName());
                map.put(Constants.METHODS_KEY, Constants.ANY_VALUE);
            } else {
                map.put(Constants.METHODS_KEY, StringUtils.join(new HashSet<>(Arrays.asList(methods)), ","));
            }
        }
        //令牌 如果存在的话 那么进行设置
        if (!ConfigUtils.isEmpty(token)) {
            if (ConfigUtils.isDefault(token)) {
                map.put(Constants.TOKEN_KEY, UUID.randomUUID().toString());
            } else {
                map.put(Constants.TOKEN_KEY, token);
            }
        }
        // export service
        //服务暴露的主机ip 当前宿主机的ip
        String host = this.findConfigedHosts(protocolConfig, registryURLs, map);
        //服务暴露的端口
        Integer port = this.findConfigedPorts(protocolConfig, name, map);

        //这里构建的URL就是provider的地址,后面将就在本地打开这个地址
        //name= 应用层协议 host 为服务地址 port为服务端口 path为接口名 map中是包含了所有的配置属性
        //protocol://ip:port/interfaceName?key0=val0&key1=val1&keyn=valn 后面的参数都为map中记录的
        URL url = new URL(name, host, port, getContextPath(protocolConfig).map(p -> p + "/" + path).orElse(path), map);

        //override=org.apache.dubbo.rpc.cluster.configurator.override.OverrideConfiguratorFactory
        //absent=org.apache.dubbo.rpc.cluster.configurator.absent.AbsentConfiguratorFactory
        if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .hasExtension(url.getProtocol())) {
            url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                    .getExtension(url.getProtocol()).getConfigurator(url).configure(url);
        }

        //暴露服务的范围 本地还是远程
        String scope = url.getParameter(Constants.SCOPE_KEY);
        // don't export when none is configured
        if (!Constants.SCOPE_NONE.equalsIgnoreCase(scope)) {

            // export to local if the config is not remote (export to remote only when config is remote)
            //如果没有配置scope为remote那么将会暴露给本地和远程
            if (!Constants.SCOPE_REMOTE.equalsIgnoreCase(scope)) {
                exportLocal(url);
            }
            // export to remote if the config is not local (export to local only when config is local)
            if (!Constants.SCOPE_LOCAL.equalsIgnoreCase(scope)) {
                if (logger.isInfoEnabled()) {
                    logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                }
                //注册中心地址 这里将服务给暴露到服务注册中心 即可以将一个服务
                //设置给多个注册中心
                if (CollectionUtils.isNotEmpty(registryURLs)) {
                    for (URL registryURL : registryURLs) {
                        //服务是否动态注册，如果设为false，注册后将显示为disable状态，需人工启用，并且服务提供者停止时，也不会自动取消注册，需人工禁用。默认为true
                        url = url.addParameterIfAbsent(Constants.DYNAMIC_KEY, registryURL.getParameter(Constants.DYNAMIC_KEY));
                        //加载监控中心的地址 将其加入到暴露服务中去，在调用的时候 数据将会被收集
                        //@see org.apache.dubbo.monitor.support.MonitorFilter.collect
                        URL monitorUrl = loadMonitor(registryURL);
                        if (monitorUrl != null) {
                            //将监控中心加入到暴露服务的地址中去
                            url = url.addParameterAndEncoded(Constants.MONITOR_KEY, monitorUrl.toFullString());
                        }
                        if (logger.isInfoEnabled()) {
                            logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " + registryURL);
                        }

                        // For providers, this is used to enable custom proxy to generate invoker
                        String proxy = url.getParameter(Constants.PROXY_KEY);
                        if (StringUtils.isNotEmpty(proxy)) {
                            registryURL = registryURL.addParameter(Constants.PROXY_KEY, proxy);
                        }

                        //到这里已经将服务暴露的全部路径给拼接全了 接下来准备将其注册到注册中心
                        //这里是通过参数"proxy"指定执行的proxyFactory,如果没有设置的话 那么默认使用的是org.apache.dubbo.rpc.proxy.javassist.JavassistProxyFactory。
                        //在上一步做的操作 "Constants.EXPORT_KEY " 的值即为使用的SPI扩展实现, 目前提供的有javassist和jdk的实现方式 invoker是一个可以执行的实体域 执行服务器上面的方法
                        //这里的invoker是一个实体域 是一个可以调用的服务 其中就是调用了配置的ref方法
                        //这里的做法其实就是将各种不同的服务封装成统一的服务 便于后面统一调用 这里的设计不可谓不好！！！
                        Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, registryURL.addParameterAndEncoded(Constants.EXPORT_KEY, url.toFullString()));
                        //其中保存了ServiceConfig中的元数据 封装了一个元数据信息 后面可能需要使用
                        DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                        //见 org.apache.dubbo.config.ServiceConfig.protocol解释 其中的URL参数的protocol 决定了使用哪个协议去执行
                        //具体提供的协议：@see org.apache.dubbo.rpc.Protocol
                        //因为注册中心使用的协议是register 所以这里是com.alibaba.dubbo.registry.integration.RegistryProtocol @see org.apache.dubbo.common.URLBuilder.setProtocol
                        //这里将服务进行暴露
                        Exporter<?> exporter = protocol.export(wrapperInvoker);
                        exporters.add(exporter);
                    }
                } else {
                    //如果没有注册中心的话 那么直接暴露 其他服务能够直接通过ip的方式进行访问
                    Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, url);
                    DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);
                    //因为这里的invoke的provider的url,所以这里使用的协议是啥就调用具体的protocol进行处理
                    //比如provider为：dubbo://  那么调用的将是org.apache.dubbo.rpc.protocol.dubbo.DubboProtocols
                    Exporter<?> exporter = protocol.export(wrapperInvoker);
                    exporters.add(exporter);
                }
                /**
                 * @since 2.7.0
                 * ServiceData Store
                 */
                MetadataReportService metadataReportService = null;
                if ((metadataReportService = getMetadataReportService()) != null) {
                    metadataReportService.publishProvider(url);
                }
            }
        }
        this.urls.add(url);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void exportLocal(URL url) {
        if (!Constants.LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
            URL local = URLBuilder.from(url)
                    .setProtocol(Constants.LOCAL_PROTOCOL)
                    .setHost(LOCALHOST_VALUE)
                    .setPort(0)
                    .build();
            //这里将其暴露在本地 org.apache.dubbo.rpc.protocol.injvm.InjvmProtocol
            Exporter<?> exporter = protocol.export(
                    proxyFactory.getInvoker(ref, (Class) interfaceClass, local));
            exporters.add(exporter);
            logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry");
        }
    }

    private Optional<String> getContextPath(ProtocolConfig protocolConfig) {
        String contextPath = protocolConfig.getContextpath();
        if (StringUtils.isEmpty(contextPath) && provider != null) {
            contextPath = provider.getContextpath();
        }
        return Optional.ofNullable(contextPath);
    }

    protected Class getServiceClass(T ref) {
        return ref.getClass();
    }

    /**
     * Register & bind IP address for service provider, can be configured separately.
     * Configuration priority: environment variables -> java system properties -> host property in config file ->
     * /etc/hosts -> default network address -> first available network address
     *
     * 前面的优先级从高到低
     */
    private String findConfigedHosts(ProtocolConfig protocolConfig, List<URL> registryURLs, Map<String, String> map) {
        boolean anyhost = false;

        String hostToBind = getValueFromConfig(protocolConfig, Constants.DUBBO_IP_TO_BIND);

        // if bind ip is not found in environment, keep looking up
        if (StringUtils.isEmpty(hostToBind)) {
            hostToBind = protocolConfig.getHost();
            if (provider != null && StringUtils.isEmpty(hostToBind)) {
                hostToBind = provider.getHost();
            }

            if (StringUtils.isEmpty(hostToBind)) {
                anyhost = true;
                hostToBind = getLocalHost();

                if (StringUtils.isEmpty(hostToBind)) {
                    hostToBind = findHostToBindByConnectRegistries(registryURLs);
                }
            }
        }

        //服务绑定的ip地址
        map.put(Constants.BIND_IP_KEY, hostToBind);

        //服务注册的IP
        // registry ip is not used for bind ip by default
        //PROTOCOL_DUBBO_IP_TO_REGISTRY 或者DUBBO_IP_TO_REGISTRY

        //这里的绑定地址和注册地址指的都是启动服务的地址 和注册中心的地址没有关系
        String hostToRegistry = getValueFromConfig(protocolConfig, Constants.DUBBO_IP_TO_REGISTRY);
        if (StringUtils.isEmpty(hostToRegistry)) {
            // bind ip is used as registry ip by default
            hostToRegistry = hostToBind;
        }

        map.put(Constants.ANYHOST_KEY, String.valueOf(anyhost));

        return hostToRegistry;
    }

    private String findHostToBindByConnectRegistries(List<URL> registryURLs) {
        if (CollectionUtils.isNotEmpty(registryURLs)) {
            for (URL registryURL : registryURLs) {
                if (Constants.MULTICAST.equalsIgnoreCase(registryURL.getParameter(Constants.REGISTRY_KEY))) {
                    // skip multicast registry since we cannot connect to it via Socket
                    continue;
                }
                try (Socket socket = new Socket()) {
                    SocketAddress addr = new InetSocketAddress(registryURL.getHost(), registryURL.getPort());
                    socket.connect(addr, 1000);
                    return socket.getLocalAddress().getHostAddress();
                } catch (Exception e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        return null;
    }

    /**
     * Register port and bind port for the provider, can be configured separately
     * Configuration priority: environment variable -> java system properties -> port property in protocol config file
     * -> protocol default port
     *
     * @param protocolConfig
     * @param name
     * @return
     */
    private Integer findConfigedPorts(ProtocolConfig protocolConfig, String name, Map<String, String> map) {
        Integer portToBind = null;

        // parse bind port from environment
        String port = getValueFromConfig(protocolConfig, Constants.DUBBO_PORT_TO_BIND);
        portToBind = parsePort(port);

        // if there's no bind port found from environment, keep looking up.
        if (portToBind == null) {
            portToBind = protocolConfig.getPort();
            if (provider != null && (portToBind == null || portToBind == 0)) {
                portToBind = provider.getPort();
            }
            //获取协议默认的端口  dubbo 默认20880
            final int defaultPort = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(name).getDefaultPort();
            if (portToBind == null || portToBind == 0) {
                portToBind = defaultPort;
            }

            //如果端口小于0 选择一个随机的ip
            if (portToBind <= 0) {
                portToBind = getRandomPort(name);
                if (portToBind == null || portToBind < 0) {
                    portToBind = getAvailablePort(defaultPort);
                    putRandomPort(name, portToBind);
                }
            }
        }
        // save bind port, used as url's key later
        map.put(Constants.BIND_PORT_KEY, String.valueOf(portToBind));

        // registry port, not used as bind port by default
        String portToRegistryStr = getValueFromConfig(protocolConfig, Constants.DUBBO_PORT_TO_REGISTRY);
        Integer portToRegistry = parsePort(portToRegistryStr);
        if (portToRegistry == null) {
            portToRegistry = portToBind;
        }

        return portToRegistry;
    }

    private String getValueFromConfig(ProtocolConfig protocolConfig, String key) {

        //PROTOCOL_key 在环境变量中查询
        String protocolPrefix = protocolConfig.getName().toUpperCase() + "_";
        String port = ConfigUtils.getSystemProperty(protocolPrefix + key);
        if (StringUtils.isEmpty(port)) {
            //key 在环境变量中查询
            port = ConfigUtils.getSystemProperty(key);
        }
        return port;
    }

    private Integer parsePort(String configPort) {
        Integer port = null;
        if (configPort != null && configPort.length() > 0) {
            try {
                Integer intPort = Integer.parseInt(configPort);
                if (isInvalidPort(intPort)) {
                    throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
                }
                port = intPort;
            } catch (Exception e) {
                throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
            }
        }
        return port;
    }

    private void completeCompoundConfigs() {

        //如果配置为空的话 可以从多个配置中进行加载
        if (provider != null) {
            if (application == null) {
                setApplication(provider.getApplication());
            }
            if (module == null) {
                setModule(provider.getModule());
            }
            if (registries == null) {
                setRegistries(provider.getRegistries());
            }
            if (monitor == null) {
                setMonitor(provider.getMonitor());
            }
            if (protocols == null) {
                setProtocols(provider.getProtocols());
            }
            if (configCenter == null) {
                setConfigCenter(provider.getConfigCenter());
            }
        }
        if (module != null) {
            if (registries == null) {
                setRegistries(module.getRegistries());
            }
            if (monitor == null) {
                setMonitor(module.getMonitor());
            }
        }
        if (application != null) {
            if (registries == null) {
                setRegistries(application.getRegistries());
            }
            if (monitor == null) {
                setMonitor(application.getMonitor());
            }
        }
    }

    private void checkDefault() {
        createProviderIfAbsent();
    }

    private void createProviderIfAbsent() {
        if (provider != null) {
            return;
        }
        setProvider (
                ConfigManager.getInstance()
                        .getDefaultProvider()
                        .orElseGet(() -> {
                            ProviderConfig providerConfig = new ProviderConfig();
                            providerConfig.refresh();
                            return providerConfig;
                        })
        );
    }

    private void checkProtocol() {
        if (CollectionUtils.isEmpty(protocols) && provider != null) {
            setProtocols(provider.getProtocols());
        }
        convertProtocolIdsToProtocols();
    }

    private void convertProtocolIdsToProtocols() {
        if (StringUtils.isEmpty(protocolIds) && CollectionUtils.isEmpty(protocols)) {
            List<String> configedProtocols = new ArrayList<>();
            configedProtocols.addAll(getSubProperties(Environment.getInstance()
                    .getExternalConfigurationMap(), Constants.PROTOCOLS_SUFFIX));
            configedProtocols.addAll(getSubProperties(Environment.getInstance()
                    .getAppExternalConfigurationMap(), Constants.PROTOCOLS_SUFFIX));

            protocolIds = String.join(",", configedProtocols);
        }

        if (StringUtils.isEmpty(protocolIds)) {
            if (CollectionUtils.isEmpty(protocols)) {
               setProtocols(
                       ConfigManager.getInstance().getDefaultProtocols()
                        .filter(CollectionUtils::isNotEmpty)
                        .orElseGet(() -> {
                            ProtocolConfig protocolConfig = new ProtocolConfig();
                            protocolConfig.refresh();
                            return new ArrayList<>(Arrays.asList(protocolConfig));
                        })
               );
            }
        } else {
            String[] arr = Constants.COMMA_SPLIT_PATTERN.split(protocolIds);
            List<ProtocolConfig> tmpProtocols = CollectionUtils.isNotEmpty(protocols) ? protocols : new ArrayList<>();
            Arrays.stream(arr).forEach(id -> {
                if (tmpProtocols.stream().noneMatch(prot -> prot.getId().equals(id))) {
                    tmpProtocols.add(ConfigManager.getInstance().getProtocol(id).orElseGet(() -> {
                        ProtocolConfig protocolConfig = new ProtocolConfig();
                        protocolConfig.setId(id);
                        protocolConfig.refresh();
                        return protocolConfig;
                    }));
                }
            });
            if (tmpProtocols.size() > arr.length) {
                throw new IllegalStateException("Too much protocols found, the protocols comply to this service are :" + protocolIds + " but got " + protocols
                        .size() + " registries!");
            }
            setProtocols(tmpProtocols);
        }
    }

    public Class<?> getInterfaceClass() {
        if (interfaceClass != null) {
            return interfaceClass;
        }
        if (ref instanceof GenericService) {
            return GenericService.class;
        }
        try {
            if (interfaceName != null && interfaceName.length() > 0) {
                this.interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            }
        } catch (ClassNotFoundException t) {
            throw new IllegalStateException(t.getMessage(), t);
        }
        return interfaceClass;
    }

    /**
     * @param interfaceClass
     * @see #setInterface(Class)
     * @deprecated
     */
    public void setInterfaceClass(Class<?> interfaceClass) {
        setInterface(interfaceClass);
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(Class<?> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
        setInterface(interfaceClass == null ? null : interfaceClass.getName());
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
        if (StringUtils.isEmpty(id)) {
            id = interfaceName;
        }
    }

    public T getRef() {
        return ref;
    }

    public void setRef(T ref) {
        this.ref = ref;
    }

    @Parameter(excluded = true)
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        checkPathName(Constants.PATH_KEY, path);
        this.path = path;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    // ======== Deprecated ========

    @SuppressWarnings("unchecked")
    public void setMethods(List<? extends MethodConfig> methods) {
        this.methods = (List<MethodConfig>) methods;
    }

    public ProviderConfig getProvider() {
        return provider;
    }


    /**
     * 设置provider
     * @param provider
     */
    public void setProvider(ProviderConfig provider) {
        ConfigManager.getInstance().addProvider(provider);
        this.provider = provider;
    }

    @Parameter(excluded = true)
    public String getProviderIds() {
        return providerIds;
    }

    public void setProviderIds(String providerIds) {
        this.providerIds = providerIds;
    }

    public String getGeneric() {
        return generic;
    }

    public void setGeneric(String generic) {
        if (StringUtils.isEmpty(generic)) {
            return;
        }
        if (ProtocolUtils.isGeneric(generic)) {
            this.generic = generic;
        } else {
            throw new IllegalArgumentException("Unsupported generic type " + generic);
        }
    }

    @Override
    public void setMock(Boolean mock) {
        throw new IllegalArgumentException("mock doesn't support on provider side");
    }

    @Override
    public void setMock(String mock) {
        throw new IllegalArgumentException("mock doesn't support on provider side");
    }

    public List<URL> getExportedUrls() {
        return urls;
    }

    /**
     * @deprecated Replace to getProtocols()
     */
    @Deprecated
    public List<ProviderConfig> getProviders() {
        return convertProtocolToProvider(protocols);
    }

    /**
     * @deprecated Replace to setProtocols()
     */
    @Deprecated
    public void setProviders(List<ProviderConfig> providers) {
        this.protocols = convertProviderToProtocol(providers);
    }

    @Override
    @Parameter(excluded = true)
    public String getPrefix() {
        return Constants.DUBBO + ".service." + interfaceName;
    }
}
