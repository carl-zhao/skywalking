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

package org.apache.skywalking.apm.commons.datacarrier.consumer;

import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.skywalking.apm.commons.datacarrier.buffer.*;

/**
 *
 * ConsumerDriver 实现中维护了固定数量的 ConsumerThread 线程（consumerThreads 字段，ConsumerThread[] 类型），
 * 它们共同消费一个 Channels 中的数据（channels 字段，Channels 类型）。
 *
 * ConsumerDriver 的核心逻辑是在其 begin() 方法中，它会根据 Channels 中的 Buffer 数量以及ConsumerThread 线程数进行分配：
 *
 * 1. 如果 Buffer 个数较多，则一个 ConsumerThread 线程需要处理多个 Buffer。
 * 2. 如果 ConsumerThread 线程数较多，则一个 Buffer 会被划分为多个区域，由不同的 ConsumerThread 线程进行消费，也就是前文介绍的，
 * 每个 ConsumerThread 线程负责消费一个 Buffer 的一个区域。
 * 3. 如果两者数量正好相同，则是一对一的消费关系。
 *
 * 消费的 Channels、ConsumerThread 线程数以及两者的绑定关系一旦确定，在整个 ConsumerDriver 的生命周期中不会再进行变更。
 *
 * BulkConsumePool 是 IDriver 接口的另一个实现，在其 allConsumers 字段（List类型）中维护了当前启动的 MultipleChannelsConsumer 线程。
 * BulkConsumePool 的核心实现在其 add() 方法，通过该方法向 BulkConsumePool 添加新 Channels 以及对应 IConsumer 时，会通过
 * getLowestPayload() 方法选择负载最低的 MultipleChannelsConsumer 线程进行处理（即当前处理 Group 最少的线程）。
 *
 * Pool of consumers <p> Created by wusheng on 2016/10/25.
 */
public class ConsumeDriver<T> implements IDriver {
    private boolean running;
    private ConsumerThread[] consumerThreads;
    private Channels<T> channels;
    private ReentrantLock lock;

    public ConsumeDriver(String name, Channels<T> channels, Class<? extends IConsumer<T>> consumerClass, int num,
        long consumeCycle) {
        this(channels, num);
        for (int i = 0; i < num; i++) {
            consumerThreads[i] = new ConsumerThread("DataCarrier." + name + ".Consumser." + i + ".Thread", getNewConsumerInstance(consumerClass), consumeCycle);
            consumerThreads[i].setDaemon(true);
        }
    }

    public ConsumeDriver(String name, Channels<T> channels, IConsumer<T> prototype, int num, long consumeCycle) {
        this(channels, num);
        prototype.init();
        for (int i = 0; i < num; i++) {
            consumerThreads[i] = new ConsumerThread("DataCarrier." + name + ".Consumser." + i + ".Thread", prototype, consumeCycle);
            consumerThreads[i].setDaemon(true);
        }

    }

    private ConsumeDriver(Channels<T> channels, int num) {
        running = false;
        this.channels = channels;
        consumerThreads = new ConsumerThread[num];
        lock = new ReentrantLock();
    }

    private IConsumer<T> getNewConsumerInstance(Class<? extends IConsumer<T>> consumerClass) {
        try {
            IConsumer<T> inst = consumerClass.newInstance();
            inst.init();
            return inst;
        } catch (InstantiationException e) {
            throw new ConsumerCannotBeCreatedException(e);
        } catch (IllegalAccessException e) {
            throw new ConsumerCannotBeCreatedException(e);
        }
    }

    @Override
    public void begin(Channels channels) {
        if (running) {
            return;
        }
        try {
            lock.lock();
            this.allocateBuffer2Thread();
            for (ConsumerThread consumerThread : consumerThreads) {
                consumerThread.start();
            }
            running = true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isRunning(Channels channels) {
        return running;
    }

    private void allocateBuffer2Thread() {
        int channelSize = this.channels.getChannelSize();
        if (channelSize < consumerThreads.length) {
            /**
             * if consumerThreads.length > channelSize
             * each channel will be process by several consumers.
             */
            ArrayList<Integer>[] threadAllocation = new ArrayList[channelSize];
            for (int threadIndex = 0; threadIndex < consumerThreads.length; threadIndex++) {
                int index = threadIndex % channelSize;
                if (threadAllocation[index] == null) {
                    threadAllocation[index] = new ArrayList<Integer>();
                }
                threadAllocation[index].add(threadIndex);
            }

            for (int channelIndex = 0; channelIndex < channelSize; channelIndex++) {
                ArrayList<Integer> threadAllocationPerChannel = threadAllocation[channelIndex];
                Buffer<T> channel = this.channels.getBuffer(channelIndex);
                int bufferSize = channel.getBufferSize();
                int step = bufferSize / threadAllocationPerChannel.size();
                for (int i = 0; i < threadAllocationPerChannel.size(); i++) {
                    int threadIndex = threadAllocationPerChannel.get(i);
                    int start = i * step;
                    int end = i == threadAllocationPerChannel.size() - 1 ? bufferSize : (i + 1) * step;
                    consumerThreads[threadIndex].addDataSource(channel, start, end);
                }
            }
        } else {
            /**
             * if consumerThreads.length < channelSize
             * each consumer will process several channels.
             *
             * if consumerThreads.length == channelSize
             * each consumer will process one channel.
             */
            for (int channelIndex = 0; channelIndex < channelSize; channelIndex++) {
                int consumerIndex = channelIndex % consumerThreads.length;
                consumerThreads[consumerIndex].addDataSource(channels.getBuffer(channelIndex));
            }
        }

    }

    @Override
    public void close(Channels channels) {
        try {
            lock.lock();
            this.running = false;
            for (ConsumerThread consumerThread : consumerThreads) {
                consumerThread.shutdown();
            }
        } finally {
            lock.unlock();
        }
    }
}
