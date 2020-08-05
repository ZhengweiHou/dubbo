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

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.invoker.DelegateProviderMetaDataInvoker;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.metadata.integration.MetadataReportService;
import org.apache.dubbo.remoting.Constants;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
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

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;
import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.REVISION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_IP_TO_BIND;
import static org.apache.dubbo.config.Constants.DUBBO_IP_TO_REGISTRY;
import static org.apache.dubbo.config.Constants.DUBBO_PORT_TO_BIND;
import static org.apache.dubbo.config.Constants.DUBBO_PORT_TO_REGISTRY;
import static org.apache.dubbo.rpc.cluster.Constants.EXPORT_KEY;
import static org.apache.dubbo.config.Constants.MULTICAST;
import static org.apache.dubbo.config.Constants.PROTOCOLS_SUFFIX;
import static org.apache.dubbo.rpc.Constants.SCOPE_KEY;
import static org.apache.dubbo.rpc.Constants.SCOPE_LOCAL;
import static org.apache.dubbo.config.Constants.SCOPE_NONE;
import static org.apache.dubbo.rpc.Constants.SCOPE_REMOTE;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;
import static org.apache.dubbo.rpc.Constants.LOCAL_PROTOCOL;
import static org.apache.dubbo.rpc.Constants.PROXY_KEY;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;
import static org.apache.dubbo.common.utils.NetUtils.getAvailablePort;
import static org.apache.dubbo.common.utils.NetUtils.getLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidPort;

/**
 * ServiceConfig
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
     */
    private static final Protocol protocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * A {@link ProxyFactory} implementation that will generate a exported service proxy,the JavassistProxyFactory is its
     * default implementation
     */
    private static final ProxyFactory PROXY_FACTORY = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    /**
     * A random port cache, the different protocols who has no port specified have different random port
     */
    private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();

    /**
     * A delayed exposure service timer
     */
    private static final ScheduledExecutorService DELAY_EXPORT_EXECUTOR = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DubboServiceDelayExporter", true));

    /**
     * The urls of the services exported
     */
    private final List<URL> urls = new ArrayList<URL>();

    /**
     * The exported services
     */
    private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();

    /**
     * The interface name of the exported service
     */
    private String interfaceName;

    /**
     * The interface class of the exported service
     */
    private Class<?> interfaceClass;

    /**
     * The reference of the interface implementation
     */
    private T ref;

    /**
     * The service name
     */
    private String path;

    /**
     * The method configuration
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

    /**
     简单总结：（TODO 后续仔细分析）
     检测 <dubbo:service> 标签的 interface 属性合法性，不合法则抛出异常
     检测 ProviderConfig、ApplicationConfig 等核心配置类对象是否为空，若为空，则尝试从其他配置类对象中获取相应的实例。
     检测并处理泛化服务和普通服务类
     检测本地存根配置，并进行相应的处理
     对 ApplicationConfig、RegistryConfig 等配置类进行检测，为空则尝试创建，若无法创建则抛出异常
     */
    public void checkAndUpdateSubConfigs() {
        // Use default configs defined explicitly on global configs
        completeCompoundConfigs();	// 初始化默认配置
        // Config Center should always being started first.
        startConfigCenter();	// 通过configCenter更新配置，如果有配置configCenter的话
        checkDefault();
        checkProtocol();
        checkApplication();
        // if protocol is not injvm checkRegistry
        if (!isOnlyInJvm()) {
            checkRegistry();
        }
        this.refresh();
        checkMetadataReport();

        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
        }

        if (ref instanceof GenericService) {
            interfaceClass = GenericService.class;
            if (StringUtils.isEmpty(generic)) {
                generic = Boolean.TRUE.toString();
            }
        } else {
            try {
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            checkInterfaceAndMethods(interfaceClass, methods);
            checkRef();
            generic = Boolean.FALSE.toString();
        }
        if (local != null) {
            if ("true".equals(local)) {
                local = interfaceName + "Local";
            }
            Class<?> localClass;
            try {
                localClass = ClassUtils.forNameWithThreadContextClassLoader(local);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceName);
            }
        }
        if (stub != null) {
            if ("true".equals(stub)) {
                stub = interfaceName + "Stub";
            }
            Class<?> stubClass;
            try {
                stubClass = ClassUtils.forNameWithThreadContextClassLoader(stub);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(stubClass)) {
                throw new IllegalStateException("The stub implementation class " + stubClass.getName() + " not implement interface " + interfaceName);
            }
        }
        checkStubAndLocal(interfaceClass);
        checkMock(interfaceClass);
    }

    /**
     * Determine if it is injvm
     *
     * @return
     */
    private boolean isOnlyInJvm() {
        return getProtocols().size() == 1 && LOCAL_PROTOCOL.equalsIgnoreCase(getProtocols().get(0).getName());
    }

    public synchronized void export() {
    	// 检查并更新一些配置
        checkAndUpdateSubConfigs();

        // 如果Export为false，则不导出服务，一般用于本地测试时不希望本地启动的服务暴露出去给别人
        if (!shouldExport()) {
            return;
        }

        // 是否延时导出
        if (shouldDelay()) {
            // 延时导出服务
            DELAY_EXPORT_EXECUTOR.schedule(this::doExport, getDelay(), TimeUnit.MILLISECONDS);
        } else {
            // 立刻执行导出
            doExport();
        }
    }

    private boolean shouldExport() {
        Boolean export = getExport();   // <dubbo:provider export="false" />
        // default value is true
        return export == null ? true : export;
    }

    @Override
    public Boolean getExport() {
        return (export == null && provider != null) ? provider.getExport() : export;
    }

    private boolean shouldDelay() {
        Integer delay = getDelay();
        return delay != null && delay > 0;
    }

    @Override
    public Integer getDelay() {
        return (delay == null && provider != null) ? provider.getDelay() : delay;
    }

    protected synchronized void doExport() {
        if (unexported) {
            throw new IllegalStateException("The service " + interfaceClass.getName() + " has already unexported!");
        }
        if (exported) {
            return;
        }
        exported = true;    // 是否已导出置为true

        if (StringUtils.isEmpty(path)) {
            path = interfaceName;   // path值默认为服务接口名
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
        // 加载注册中心链接。  简单理解就是RegistryConfig 转换 成URL
        List<URL> registryURLs = loadRegistries(true);
        // 遍历所有协议，并在每个协议下导出服务
        for (ProtocolConfig protocolConfig : protocols) {

            // TODO pathKey是什么东西？
            String pathKey = URL.buildKey(getContextPath(protocolConfig).map(p -> p + "/" + path).orElse(path), group, version);
            ProviderModel providerModel = new ProviderModel(pathKey, ref, interfaceClass);
            ApplicationModel.initProviderModel(pathKey, providerModel); // 记录下已注册的提供者 TODO 有什么用？

            // 用某个协议注册URL。根据配置，以及其他一些信息组装 UR
            doExportUrlsFor1Protocol(protocolConfig, registryURLs);
        }
    }

    private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs) {
        String name = protocolConfig.getName();

        // 如果协议名为空，或空串，则将协议名变量设置为 dubbo
        if (StringUtils.isEmpty(name)) {
            name = DUBBO;
        }

        Map<String, String> map = new HashMap<String, String>();
        map.put(SIDE_KEY, PROVIDER_SIDE);

        appendRuntimeParameters(map);

        appendParameters(map, metrics); // TODO metrics是什么配置？ metrics为微服务的监控提供数据基础；是一个标准度量库，对监控对象的数据采集进行了统一封装。
        appendParameters(map, application);
        appendParameters(map, module);
        // remove 'default.' prefix for configs from ProviderConfig
        // appendParameters(map, provider, Constants.DEFAULT_KEY);
        appendParameters(map, provider);
        appendParameters(map, protocolConfig);
        appendParameters(map, this);

        // FIXME 方法配置的处理 MethodConfig    TODO 重载方法处理是不是有问题
        // 检测 <dubbo:method> 标签中的配置信息，并将相关配置添加到 map 中
        // methods 为 MethodConfig 集合，MethodConfig 中存储了 <dubbo:method> 标签的配置信息
        if (CollectionUtils.isNotEmpty(methods)) {
            for (MethodConfig method : methods) {
                // 添加 MethodConfig 对象的字段信息到 map 中，键 = 方法名.属性名。
                // 比如存储 <dubbo:method name="sayHello" retries="2"> 对应的 MethodConfig，
                // 键 = sayHello.retries，map = {"sayHello.retries": 2, "xxx": "yyy"}
                appendParameters(map, method, method.getName());

                // retry=false 转化成 retries=0
                String retryKey = method.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    // 检测 MethodConfig retry 是否为 false，若是，则设置重试次数为0
                    if ("false".equals(retryValue)) {
                        map.put(method.getName() + ".retries", "0");
                    }
                }

                // FIXME 方法参数配置处理  ArgumentConfig
                List<ArgumentConfig> arguments = method.getArguments();
                if (CollectionUtils.isNotEmpty(arguments)) {
                    for (ArgumentConfig argument : arguments) {
                        // 转换参数类型
                        // 检测 type 属性是否为空，或者空串
                        if (argument.getType() != null && argument.getType().length() > 0) {
                            // （FIXME 分支1 ⭐️）  指定了参数类型
                            // 获取目标接口方法列表（对比方法参数配置进行校验）
                            Method[] methods = interfaceClass.getMethods();
                            // visit all methods
                            if (methods != null && methods.length > 0) {
                                for (int i = 0; i < methods.length; i++) {
                                    String methodName = methods[i].getName();
                                    // target the method, and get its signature
                                    // 对比方法名，查找目标方法
                                    if (methodName.equals(method.getName())) {
                                        Class<?>[] argtypes = methods[i].getParameterTypes();
                                        // one callback in the method
                                        if (argument.getIndex() != -1) {
                                            // 检测 ArgumentConfig 中的 type 属性与方法参数列表中的参数名称是否一致，不一致则抛出异常
                                            // (FIXME 分支2 ⭐️)
                                            if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {
                                                // 添加 ArgumentConfig 字段信息到 map 中，
                                                // 键前缀 = 方法名.index，比如:
                                                // map = {"sayHello.3": true}
                                                appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                                            } else {
                                                throw new IllegalArgumentException("Argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                            }
                                        } else {
                                            // FIXME 分支3 ⭐️
                                            // multiple callbacks in the method
                                            for (int j = 0; j < argtypes.length; j++) {
                                                Class<?> argclazz = argtypes[j];
                                                // 从参数类型列表中查找类型名称为 argument.type 的参数
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
                            // FIXME 分支4 ⭐️  没指定参数类型，但指定了index
                            appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                        } else {
                            throw new IllegalArgumentException("Argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
                        }

                    }
                }
            } // end of methods for
        }

        // 检测 generic 是否为 "true"，并根据检测结果向 map 中添加不同的信息。 TODO generic是什么意思？通用的？
        if (ProtocolUtils.isGeneric(generic)) {
            map.put(GENERIC_KEY, generic);
            map.put(METHODS_KEY, ANY_VALUE);
        } else {
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                map.put(REVISION_KEY, revision);
            }

            // 为接口生成包裹类 Wrapper，Wrapper 中包含了接口的详细信息，比如接口方法名数组，字段信息等
            // TODO 这个包装类有什么用，这里的methods是包装类中的方法？
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();

            // 添加方法名到 map 中，如果包含多个方法名，则用逗号隔开，比如 method = init,destroy
            if (methods.length == 0) {
                logger.warn("No method found in service interface " + interfaceClass.getName());
                map.put(METHODS_KEY, ANY_VALUE);
            } else {
                // 将逗号作为分隔符连接方法名，并将连接后的字符串放入 map 中
                map.put(METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }

        // 添加 token 到 map 中
        // TODO token 是什么了？
        if (!ConfigUtils.isEmpty(token)) {
            if (ConfigUtils.isDefault(token)) {
                // 随机生成 token
                map.put(TOKEN_KEY, UUID.randomUUID().toString());
            } else {
                map.put(TOKEN_KEY, token);
            }
        }


        // export service
        // 获取 host 和 port
        String host = this.findConfigedHosts(protocolConfig, registryURLs, map);
        Integer port = this.findConfigedPorts(protocolConfig, name, map);

        // 组装 URL    org.apache.dubbo.common.URL
        URL url = new URL(
                name,
                host,
                port,
                // 上下文路径 + path     方法路径
                getContextPath(protocolConfig).map(p -> p + "/" + path).orElse(path),
                map);

        // ===至此，URL主要内容组装完毕====
        /** URL eg:
        dubbo://xxx.xxx.xxx.xxx:20881/com.hzw.learn.springboot.dubbo.hello.provider.Hi
                ?anyhost=true
                &application=provider_api
                &bind.ip=10.252.12.24
                &bind.port=20881
                &deprecated=false
                &dubbo=2.0.2
                &dubbo.tag=xxx
                &dynamic=true
                &generic=false
                &group=2222
                &interface=com.hzw.learn.springboot.dubbo.hello.provider.Hi
                &loadbalance=roundrobin
                &methods=async_sayhi,sayhi
                &pid=139888
                &qos.accept.foreign.ip=false
                &register=true
                &release=
                &revision=1.0.0
                &side=provider
                &telnet=cd,ps,select,log,ls,clear,count,invoke,exit,help,trace,pwd,shutdown,status
                &timestamp=1595294798037
                &version=1.0.0
         */



        // ====================导出Dubbo服务=========================
        // 若存在目标protocol的配置工厂扩展，则通过该工厂扩展生产出protocal对应的configuretor，并以url为参数调用配置方法 TODO 这能用来干啥呢？
        if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .hasExtension(url.getProtocol())) {
            url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                    .getExtension(url.getProtocol()).getConfigurator(url).configure(url);
        }

        // TODO 这个SCOPE的值是从哪来的
        String scope = url.getParameter(SCOPE_KEY);

//        这段代码是源码的原始逻辑
//        don't export when none is configured
//        if (!SCOPE_NONE.equalsIgnoreCase(scope)) {
//
//            // export to local if the config is not remote (export to remote only when config is remote)
//            if (!SCOPE_REMOTE.equalsIgnoreCase(scope)) {
//                exportLocal(url);
//            }
//            // export to remote if the config is not local (export to local only when config is local)
//            if (!SCOPE_LOCAL.equalsIgnoreCase(scope)) {
//                if (!isOnlyInJvm() && logger.isInfoEnabled()) {
//                    logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
//                }
//                if (CollectionUtils.isNotEmpty(registryURLs)) {
//                    for (URL registryURL : registryURLs) {
//                        //if protocol is only injvm ,not register
//                        if (LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
//                            continue;
//                        }
//                        url = url.addParameterIfAbsent(DYNAMIC_KEY, registryURL.getParameter(DYNAMIC_KEY));
//                        URL monitorUrl = loadMonitor(registryURL);
//                        if (monitorUrl != null) {
//                            url = url.addParameterAndEncoded(MONITOR_KEY, monitorUrl.toFullString());
//                        }
//                        if (logger.isInfoEnabled()) {
//                            logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " + registryURL);
//                        }
//
//                        // For providers, this is used to enable custom proxy to generate invoker
//                        String proxy = url.getParameter(PROXY_KEY);
//                        if (StringUtils.isNotEmpty(proxy)) {
//                            registryURL = registryURL.addParameter(PROXY_KEY, proxy);
//                        }
//
//                        Invoker<?> invoker = PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, registryURL.addParameterAndEncoded(EXPORT_KEY, url.toFullString()));
//                        DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);
//
//                        Exporter<?> exporter = protocol.export(wrapperInvoker);
//                        exporters.add(exporter);
//                    }
//                } else {
//                    Invoker<?> invoker = PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, url);
//                    DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);
//
//                    Exporter<?> exporter = protocol.export(wrapperInvoker);
//                    exporters.add(exporter);
//                }
//                /**
//                 * @since 2.7.0
//                 * ServiceData Store
//                 */
//                MetadataReportService metadataReportService = null;
//                if ((metadataReportService = getMetadataReportService()) != null) {
//                    metadataReportService.publishProvider(url);
//                }
//            }
//        }

        // refact up
               // don't export when none is configured
        if (!SCOPE_NONE.equalsIgnoreCase(scope)) {
            // export to local if the config is not remote (export to remote only when config is remote)
            if (!SCOPE_REMOTE.equalsIgnoreCase(scope)) {
                // 导出到本地
                exportLocal(url);
            }
            // export to remote if the config is not local (export to local only when config is local)
            if (!SCOPE_LOCAL.equalsIgnoreCase(scope)) {
                // 导出到远程
                // refact by hzw
                url = exportRemote(registryURLs, url);
            }
        }

        // FIXME 不能如下重构，原逻辑若scope为null则同时注册local和remote，当且仅当scope=none时不进行任何导出。下列逻辑不对
//        if (SCOPE_NONE.equalsIgnoreCase(scope)){
//            // do nothing
//        }else{
//            if(SCOPE_LOCAL.equalsIgnoreCase(scope)){
//                // 导出到本地
//                exportLocal(url);
//            }
//            if(SCOPE_REMOTE.equalsIgnoreCase(scope)){
//                // 导出到远程
//                // refact by hzw
//                url = exportRemote(registryURLs, url);
//            }
//        }

        this.urls.add(url);
    }

    // refact by hzw
    private URL exportRemote(List<URL> registryURLs, URL url) {
        if (!isOnlyInJvm() && logger.isInfoEnabled()) {
            logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
        }
        if (CollectionUtils.isNotEmpty(registryURLs)) {
            for (URL registryURL : registryURLs) {
                // 遍历注册中心URL列表

                //if protocol is only injvm ,not register
                if (LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
                    // protocal=injvm 则不进行注册 TODO　为什么这个判断不移到循环体外
                    continue;
                }
                url = url.addParameterIfAbsent(DYNAMIC_KEY, registryURL.getParameter(DYNAMIC_KEY));

                // 加载监视器链接
                URL monitorUrl = loadMonitor(registryURL);
                if (monitorUrl != null) {
                    // 将监视器链接作为参数加入到url中
                    url = url.addParameterAndEncoded(MONITOR_KEY, monitorUrl.toFullString());
                }


                if (logger.isInfoEnabled()) {
                    logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " + registryURL);
                }

                // For providers, this is used to enable custom proxy to generate invoker
                // 配置了proxy，启用自定义代理来生成调用程序 TODO 怎么使用呢？
                String proxy = url.getParameter(PROXY_KEY);
                if (StringUtils.isNotEmpty(proxy)) {
                    registryURL = registryURL.addParameter(PROXY_KEY, proxy);
                }


                /*
                FIXME 什么是Invoker ？
                Invoker 是实体域，它是 Dubbo 的核心模型，其它模型都向它靠扰，
                或转换成它，它代表一个可执行体，可向它发起 invoke 调用，它有可能
                是一个本地的实现，也可能是一个远程的实现，也可能一个集群实现。
                */
                // 为服务提供实现类（ref）生成Invoker
                /**这里的PROXY_FACTORY是ProxyFactory.class的自适应扩展，其默认实现是JavassistProxyFactory
                 * @see org.apache.dubbo.rpc.proxy.javassist.JavassistProxyFactory#getInvoker(Object, Class, URL)
                 */
                Invoker<?> invoker = PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, registryURL.addParameterAndEncoded(EXPORT_KEY, url.toFullString())); // registryURL.export = url

                // 委托提供者元数据调用者 用于持有 Invoker 和 ServiceConfig
                DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                // 导出服务，并生成 Export
                // FIXME 怎么实现不同的protocol调用不同的导出扩展类？
                //  注意这里的protocol是一个自适应扩展，真正实现类会通过invoker中的url中的protocol参数值作为扩展类name去获取扩展
                /**
                 * 这里的protocol会自适应调用
                 * @see org.apache.dubbo.registry.integration.RegistryProtocol#export(Invoker)
                 */
                // url.protocol  ==>  registry=org.apache.dubbo.registry.integration.RegistryProtocol
                Exporter<?> exporter = protocol.export(wrapperInvoker);
                exporters.add(exporter);
            }
        } else {
            // TODO 若没有配置注册中心，这里是导出到哪？
            Invoker<?> invoker = PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, url);
            DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

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
        return url;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    /**
     * always export injvm
     */
    private void exportLocal(URL url) {
        URL local = URLBuilder.from(url)
                .setProtocol(LOCAL_PROTOCOL)  // 设置协议头为 injvm
                .setHost(LOCALHOST_VALUE)
                .setPort(0)
                .build();

        // 创建invoker 并通过protocol导出服务。FIXME 注意这里的protocol是一个自适应扩展，真正实现类会通过invoker中的url中的protocol参数值作为扩展类name去获取扩展
        /**
         * 这里的protocol会自适应调用 (FIXME 同级对比DubboProtocol的export动作)
         * @see org.apache.dubbo.rpc.protocol.injvm.InjvmProtocol
         */
        Exporter<?> exporter = protocol.export(
                PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, local));
        exporters.add(exporter);
        logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry url : " + local);
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
     * @param protocolConfig
     * @param registryURLs
     * @param map
     * @return
     */
    private String findConfigedHosts(ProtocolConfig protocolConfig, List<URL> registryURLs, Map<String, String> map) {
        boolean anyhost = false;

        String hostToBind = getValueFromConfig(protocolConfig, DUBBO_IP_TO_BIND);
        if (hostToBind != null && hostToBind.length() > 0 && isInvalidLocalHost(hostToBind)) {
            throw new IllegalArgumentException("Specified invalid bind ip from property:" + DUBBO_IP_TO_BIND + ", value:" + hostToBind);
        }

        // if bind ip is not found in environment, keep looking up
        if (StringUtils.isEmpty(hostToBind)) {
            hostToBind = protocolConfig.getHost();
            if (provider != null && StringUtils.isEmpty(hostToBind)) {
                hostToBind = provider.getHost();
            }
            if (isInvalidLocalHost(hostToBind)) {
                anyhost = true;
                try {
                    hostToBind = InetAddress.getLocalHost().getHostAddress();
                } catch (UnknownHostException e) {
                    logger.warn(e.getMessage(), e);
                }
                if (isInvalidLocalHost(hostToBind)) {
                    if (CollectionUtils.isNotEmpty(registryURLs)) {
                        for (URL registryURL : registryURLs) {
                            if (MULTICAST.equalsIgnoreCase(registryURL.getParameter("registry"))) {
                                // skip multicast registry since we cannot connect to it via Socket
                                continue;
                            }
                            try (Socket socket = new Socket()) {
                                SocketAddress addr = new InetSocketAddress(registryURL.getHost(), registryURL.getPort());
                                socket.connect(addr, 1000);
                                hostToBind = socket.getLocalAddress().getHostAddress();
                                break;
                            } catch (Exception e) {
                                logger.warn(e.getMessage(), e);
                            }
                        }
                    }
                    if (isInvalidLocalHost(hostToBind)) {
                        hostToBind = getLocalHost();
                    }
                }
            }
        }

        map.put(Constants.BIND_IP_KEY, hostToBind);

        // registry ip is not used for bind ip by default
        String hostToRegistry = getValueFromConfig(protocolConfig, DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry != null && hostToRegistry.length() > 0 && isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        } else if (StringUtils.isEmpty(hostToRegistry)) {
            // bind ip is used as registry ip by default
            hostToRegistry = hostToBind;
        }

        map.put(ANYHOST_KEY, String.valueOf(anyhost));

        return hostToRegistry;
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
        String port = getValueFromConfig(protocolConfig, DUBBO_PORT_TO_BIND);
        portToBind = parsePort(port);

        // if there's no bind port found from environment, keep looking up.
        if (portToBind == null) {
            portToBind = protocolConfig.getPort();
            if (provider != null && (portToBind == null || portToBind == 0)) {
                portToBind = provider.getPort();
            }
            final int defaultPort = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(name).getDefaultPort();
            if (portToBind == null || portToBind == 0) {
                portToBind = defaultPort;
            }
            if (portToBind == null || portToBind <= 0) {
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
        String portToRegistryStr = getValueFromConfig(protocolConfig, DUBBO_PORT_TO_REGISTRY);
        Integer portToRegistry = parsePort(portToRegistryStr);
        if (portToRegistry == null) {
            portToRegistry = portToBind;
        }

        return portToRegistry;
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

    private String getValueFromConfig(ProtocolConfig protocolConfig, String key) {
        String protocolPrefix = protocolConfig.getName().toUpperCase() + "_";
        String port = ConfigUtils.getSystemProperty(protocolPrefix + key);
        if (StringUtils.isEmpty(port)) {
            port = ConfigUtils.getSystemProperty(key);
        }
        return port;
    }

    private void completeCompoundConfigs() {
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
        setProvider(
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
                    .getExternalConfigurationMap(), PROTOCOLS_SUFFIX));
            configedProtocols.addAll(getSubProperties(Environment.getInstance()
                    .getAppExternalConfigurationMap(), PROTOCOLS_SUFFIX));

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
            String[] arr = COMMA_SPLIT_PATTERN.split(protocolIds);
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
        checkPathName(PATH_KEY, path);
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
        return DUBBO + ".service." + interfaceName;
    }
}
