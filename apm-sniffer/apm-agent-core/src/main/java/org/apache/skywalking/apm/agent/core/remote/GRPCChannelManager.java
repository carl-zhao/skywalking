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

import io.grpc.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.skywalking.apm.agent.core.boot.*;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.logging.api.*;
import org.apache.skywalking.apm.util.RunnableWithExceptionProtection;

/**
 * 负责管理 Agent 到 OAP 集群的网络连接，并实时的通知注册的 Listener
 *
 * ManagedChanne l：它是 gRPC 客户端的核心类之一，它逻辑上表示一个 Channel，底层持有一个 TCP 链接，并负责维护此连接的活性。也就是说，
 * 在 RPC 调用的任何时机，如果检测到底层连接处于关闭状态（terminated），将会尝试重建连接。通常情况下，我们不需要在 RPC 调用结束后就关闭 Channel
 * ，该 Channel 可以被一直重用，直到整个客户端程序关闭。当然，我们可以在客户端内以连接池的方式使用多个 ManagedChannel ，在每次 RPC
 * 请求时选择使用轮训或是随机等算法选择一个可用的 Channel，这样可以提高客户端整体的并发能力。
 *
 * ManagedChannelBuilder：**它负责创建客户端 Channel，ManagedChannelBuilder 使用了 provider 机制，具体是创建了哪种 Channel
 * 由 provider 决定，常用的 ManagedChannelBuilder 有三种：NettyChannelBuilder、OkHttpChannelBuilder、InProcessChannelBuilder
 *
 * 在 SkyWalking Agent 中用的是 NettyChannelBuilder，其创建的 Channel 底层是基于 Netty 实现的。OkHttpChannelBuilder 创建的
 * Channel 底层是基于 OkHttp 库实现的。InProcessChannelBuilder 用于创建进程内通信使用的 Channel。
 *
 * SkyWalking 在 ManagedChannel 的基础上封装了自己的 Channel 实现 —— GRPCChannel ，可以添加一些装饰器，目前就只有一个验证权限的修饰器，
 * 实现比较简单，这里就不展开分析了。
 *
 * 介绍完基础知识之后，回到 GRPCChannelManager，其中维护了一个 GRPCChannel 连接以及注册在其上的 Listener 监听，另外还会维护一个后台
 * 线程定期检测该 GRPCChannel 的连接状态，如果发现连接断开，则会进行重连。
 *
 * @author wusheng, zhang xin
 */
@DefaultImplementor
public class GRPCChannelManager implements BootService, Runnable {
    private static final ILog logger = LogManager.getLogger(GRPCChannelManager.class);

    // 封装了上面介绍的gRPC Channel
    private volatile GRPCChannel managedChannel = null;
    // 定时检查 GRPCChannel的连接状态重连gRPC Server的定时任务
    private volatile ScheduledFuture<?> connectCheckFuture;
    // 是否重连。当 GRPCChannel断开时会标记 reconnect为 true，后台线程会根据该标识决定是否进行重连
    private volatile boolean reconnect = true;
    private Random random = new Random();
    // 加在 Channel上的监听器，主要是监听 Channel的状态变化
    private List<GRPCChannelListener> listeners = Collections.synchronizedList(new LinkedList<GRPCChannelListener>());
    // 可选的 gRPC Server集合，即后端OAP集群中各个OAP实例的地址
    private volatile List<String> grpcServers;
    private volatile int selectedIdx = -1;

    @Override
    public void prepare() throws Throwable {

    }

    @Override
    public void boot() throws Throwable {
        if (Config.Collector.BACKEND_SERVICE.trim().length() == 0) {
            logger.error("Collector server addresses are not set.");
            logger.error("Agent will not uplink any data.");
            return;
        }
        grpcServers = Arrays.asList(Config.Collector.BACKEND_SERVICE.split(","));
        connectCheckFuture = Executors
            .newSingleThreadScheduledExecutor(new DefaultNamedThreadFactory("GRPCChannelManager"))
            .scheduleAtFixedRate(new RunnableWithExceptionProtection(this, new RunnableWithExceptionProtection.CallbackWhenException() {
                @Override
                public void handle(Throwable t) {
                    logger.error("unexpected exception.", t);
                }
            }), 0, Config.Collector.GRPC_CHANNEL_CHECK_INTERVAL, TimeUnit.SECONDS);
    }

    @Override
    public void onComplete() throws Throwable {

    }

    @Override
    public void shutdown() throws Throwable {
        if (connectCheckFuture != null) {
            connectCheckFuture.cancel(true);
        }
        if (managedChannel != null) {
            managedChannel.shutdownNow();
        }
        logger.debug("Selected collector grpc service shutdown.");
    }

    @Override
    public void run() {
        logger.debug("Selected collector grpc service running, reconnect:{}.", reconnect);
        if (reconnect) {
            if (grpcServers.size() > 0) {
                String server = "";
                try {
                    int index = Math.abs(random.nextInt()) % grpcServers.size();
                    if (index != selectedIdx) {
                        selectedIdx = index;

                        server = grpcServers.get(index);
                        String[] ipAndPort = server.split(":");

                        if (managedChannel != null) {
                            managedChannel.shutdownNow();
                        }
                        // 根据配置，连接指定OAP实例的IP和端口
                        managedChannel = GRPCChannel.newBuilder(ipAndPort[0], Integer.parseInt(ipAndPort[1]))
                            .addManagedChannelBuilder(new StandardChannelBuilder())
                            .addManagedChannelBuilder(new TLSChannelBuilder())
                            .addChannelDecorator(new AuthenticationDecorator())
                            .build();
                        // notify()方法会循环调用所有注册在当前连接上的GRPCChannelListener实例(记录在listeners集合中)的
                        // statusChanged()方法，通知它们连接创建成功的事件
                        notify(GRPCChannelStatus.CONNECTED);
                    }

                    // 设置 reconnect字段为false，暂时不会再重建连接了
                    reconnect = false;
                    return;
                } catch (Throwable t) {
                    logger.error(t, "Create channel to {} fail.", server);
                }
            }

            logger.debug("Selected collector grpc service is not available. Wait {} seconds to retry", Config.Collector.GRPC_CHANNEL_CHECK_INTERVAL);
        }
    }

    public void addChannelListener(GRPCChannelListener listener) {
        listeners.add(listener);
    }

    public Channel getChannel() {
        return managedChannel.getChannel();
    }

    /**
     * If the given expcetion is triggered by network problem, connect in background.
     *
     * @param throwable
     */
    public void reportError(Throwable throwable) {
        if (isNetworkError(throwable)) {
            reconnect = true;
        }
    }

    private void notify(GRPCChannelStatus status) {
        for (GRPCChannelListener listener : listeners) {
            try {
                listener.statusChanged(status);
            } catch (Throwable t) {
                logger.error(t, "Fail to notify {} about channel connected.", listener.getClass().getName());
            }
        }
    }

    private boolean isNetworkError(Throwable throwable) {
        if (throwable instanceof StatusRuntimeException) {
            StatusRuntimeException statusRuntimeException = (StatusRuntimeException)throwable;
            return statusEquals(statusRuntimeException.getStatus(),
                Status.UNAVAILABLE,
                Status.PERMISSION_DENIED,
                Status.UNAUTHENTICATED,
                Status.RESOURCE_EXHAUSTED,
                Status.UNKNOWN
            );
        }
        return false;
    }

    private boolean statusEquals(Status sourceStatus, Status... potentialStatus) {
        for (Status status : potentialStatus) {
            if (sourceStatus.getCode() == status.getCode()) {
                return true;
            }
        }
        return false;
    }
}
