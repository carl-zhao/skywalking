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
 *
 */

package org.apache.skywalking.apm.agent.core.remote;

import io.grpc.Channel;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.boot.DefaultImplementor;
import org.apache.skywalking.apm.agent.core.boot.DefaultNamedThreadFactory;
import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.conf.RemoteDownstreamConfig;
import org.apache.skywalking.apm.agent.core.dictionary.DictionaryUtil;
import org.apache.skywalking.apm.agent.core.dictionary.EndpointNameDictionary;
import org.apache.skywalking.apm.agent.core.dictionary.NetworkAddressDictionary;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.os.OSUtil;
import org.apache.skywalking.apm.network.common.KeyIntValuePair;
import org.apache.skywalking.apm.network.register.v2.RegisterGrpc;
import org.apache.skywalking.apm.network.register.v2.Service;
import org.apache.skywalking.apm.network.register.v2.ServiceInstance;
import org.apache.skywalking.apm.network.register.v2.ServiceInstancePingGrpc;
import org.apache.skywalking.apm.network.register.v2.ServiceInstancePingPkg;
import org.apache.skywalking.apm.network.register.v2.ServiceInstanceRegisterMapping;
import org.apache.skywalking.apm.network.register.v2.ServiceInstances;
import org.apache.skywalking.apm.network.register.v2.ServiceRegisterMapping;
import org.apache.skywalking.apm.network.register.v2.Services;
import org.apache.skywalking.apm.util.RunnableWithExceptionProtection;
import org.apache.skywalking.apm.util.StringUtil;

/**
 *
 * ServiceAndEndpointRegisterClient
 *
 * 1. 注册功能：其中包括服务注册和服务实例注册两次请求。
 * 2. 定期发送心跳请求：与后端 OAP 集群维持定期探活，让后端 OAP 集群知道该 Agent 正常在线。
 * 3. 定期同步 Endpoint 名称以及网络地址：维护当前 Agent 中字符串与数字编号的映射关系，减少后续 Trace 数据传输的网络压力，提高请求的有效负载。
 *
 * @author wusheng
 */
@DefaultImplementor
public class ServiceAndEndpointRegisterClient implements BootService, Runnable, GRPCChannelListener {
    private static final ILog logger = LogManager.getLogger(ServiceAndEndpointRegisterClient.class);
    private static String INSTANCE_UUID;

    private volatile GRPCChannelStatus status = GRPCChannelStatus.DISCONNECT;
    private volatile RegisterGrpc.RegisterBlockingStub registerBlockingStub;
    private volatile ServiceInstancePingGrpc.ServiceInstancePingBlockingStub serviceInstancePingStub;
    private volatile ScheduledFuture<?> applicationRegisterFuture;

    @Override
    public void statusChanged(GRPCChannelStatus status) {
        if (GRPCChannelStatus.CONNECTED.equals(status)) {
            // 网络连接创建成功时，会依赖该连接创建两个stub客户端
            Channel channel = ServiceManager.INSTANCE.findService(GRPCChannelManager.class).getChannel();
            registerBlockingStub = RegisterGrpc.newBlockingStub(channel);
            serviceInstancePingStub = ServiceInstancePingGrpc.newBlockingStub(channel);
        } else {
            // 网络连接断开时，更新两个stub字段（它们都是volatile修饰）
            registerBlockingStub = null;
            serviceInstancePingStub = null;
        }
        // 更新status字段，记录网络状态
        this.status = status;
    }

    @Override
    public void prepare() throws Throwable {
        // 查找 GRPCChannelManager实例(前面介的ServiceManager.bootedServices集合会按照类型维护BootService实例，查找也是查找该集合)，
        // 然后将 ServiceAndEndpointRegisterClient注册成Listener
        ServiceManager.INSTANCE.findService(GRPCChannelManager.class).addChannelListener(this);

        // 确定INSTANCE_UUID，优先使用gent.config文件中配置的INSTANCE_UUID若未配置则随机生成
        INSTANCE_UUID = StringUtil.isEmpty(Config.Agent.INSTANCE_UUID) ? UUID.randomUUID().toString()
            .replaceAll("-", "") : Config.Agent.INSTANCE_UUID;
    }

    @Override
    public void boot() throws Throwable {
        applicationRegisterFuture = Executors
            .newSingleThreadScheduledExecutor(new DefaultNamedThreadFactory("ServiceAndEndpointRegisterClient"))
            .scheduleAtFixedRate(new RunnableWithExceptionProtection(this, new RunnableWithExceptionProtection.CallbackWhenException() {
                @Override
                public void handle(Throwable t) {
                    logger.error("unexpected exception.", t);
                }
            }), 0, Config.Collector.APP_AND_SERVICE_REGISTER_CHECK_INTERVAL, TimeUnit.SECONDS);
    }

    @Override
    public void onComplete() throws Throwable {
    }

    @Override
    public void shutdown() throws Throwable {
        applicationRegisterFuture.cancel(true);
    }

    @Override
    public void run() {
        logger.debug("ServiceAndEndpointRegisterClient running, status:{}.", status);
        boolean shouldTry = true;
        while (GRPCChannelStatus.CONNECTED.equals(status) && shouldTry) {
            shouldTry = false;
            try {
                // 检测当前Agent是否已完成了Service注册
                if (RemoteDownstreamConfig.Agent.SERVICE_ID == DictionaryUtil.nullValue()) {
                    if (registerBlockingStub != null) { // 第二次检查网络状态
                        // 通过doServiceRegister()接口进行Service注册
                        ServiceRegisterMapping serviceRegisterMapping = registerBlockingStub.doServiceRegister(
                            Services.newBuilder().addServices(Service.newBuilder().setServiceName(Config.Agent.SERVICE_NAME)).build());
                        if (serviceRegisterMapping != null) {
                            for (KeyIntValuePair registered : serviceRegisterMapping.getServicesList()) {
                                if (Config.Agent.SERVICE_NAME.equals(registered.getKey())) {
                                    RemoteDownstreamConfig.Agent.SERVICE_ID = registered.getValue();
                                    // 设置shouldTry，紧跟着会执行服务实例注册
                                    shouldTry = true;
                                }
                            }
                        }
                    }
                } else {
                    // 后续会执行服务实例注册以及心跳操作
                    if (registerBlockingStub != null) {
                        if (RemoteDownstreamConfig.Agent.SERVICE_INSTANCE_ID == DictionaryUtil.nullValue()) {
                            // 调用 doServiceInstanceRegister()接口，用serviceId和INSTANCE_UUID换取SERVICE_INSTANCE_ID
                            ServiceInstanceRegisterMapping instanceMapping = registerBlockingStub.doServiceInstanceRegister(ServiceInstances.newBuilder()
                                .addInstances(
                                    ServiceInstance.newBuilder()
                                        .setServiceId(RemoteDownstreamConfig.Agent.SERVICE_ID)
                                        // 除了serviceId，还会传递uuid、时间戳以及系统信息之类的
                                        .setInstanceUUID(INSTANCE_UUID)
                                        .setTime(System.currentTimeMillis())
                                        .addAllProperties(OSUtil.buildOSInfo())
                                ).build());
                            for (KeyIntValuePair serviceInstance : instanceMapping.getServiceInstancesList()) {
                                if (INSTANCE_UUID.equals(serviceInstance.getKey())) {
                                    int serviceInstanceId = serviceInstance.getValue();
                                    if (serviceInstanceId != DictionaryUtil.nullValue()) {
                                        // 记录serviceIntanceId
                                        RemoteDownstreamConfig.Agent.SERVICE_INSTANCE_ID = serviceInstanceId;
                                    }
                                }
                            }
                        } else {
                            // 并没有对心跳请求的响应做处理
                            serviceInstancePingStub.doPing(ServiceInstancePingPkg.newBuilder()
                                .setServiceInstanceId(RemoteDownstreamConfig.Agent.SERVICE_INSTANCE_ID)
                                .setTime(System.currentTimeMillis())
                                .setServiceInstanceUUID(INSTANCE_UUID)
                                .build());

                            NetworkAddressDictionary.INSTANCE.syncRemoteDictionary(registerBlockingStub);
                            EndpointNameDictionary.INSTANCE.syncRemoteDictionary(registerBlockingStub);
                        }
                    }
                }
            } catch (Throwable t) {
                logger.error(t, "ServiceAndEndpointRegisterClient execute fail.");
                ServiceManager.INSTANCE.findService(GRPCChannelManager.class).reportError(t);
            }
        }
    }
}
