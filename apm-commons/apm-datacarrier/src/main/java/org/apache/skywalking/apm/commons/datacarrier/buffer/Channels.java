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

package org.apache.skywalking.apm.commons.datacarrier.buffer;

import org.apache.skywalking.apm.commons.datacarrier.callback.QueueBlockingCallback;
import org.apache.skywalking.apm.commons.datacarrier.partition.IDataPartitioner;

/**
 *
 * Channels 底层管理了多个 Buffer 对象，提供了 IDataPartitioner 选择器用于确定一个数据元素写入到底层的哪个 Buffer 对象中。
 * 如果你了解 Kafka 可能知道，Kafka Producer 在发送数据时也会有相应的分区策略，IDataPartitioner 与之类似。当数据并行写入的时候，
 * 由 IDataPartitioner 选择器根据一定的均衡策略将数据分散到不同的 Buffer 中写入，这样就可以有效减少并发导致的自旋锁等待时间，
 * 降低整个 Channels 的写入压力，提高写入效率。IDataPartitioner 接口有两个实现
 *
 * Channels of Buffer It contains all buffer data which belongs to this channel. It supports several strategy when buffer
 * is full. The Default is BLOCKING <p> Created by wusheng on 2016/10/25.
 */
public class Channels<T> {
    private final Buffer<T>[] bufferChannels;
    private IDataPartitioner<T> dataPartitioner;

    private BufferStrategy strategy;
    private final long size;

    /**
     *
     * @param channelSize 指定 Channels 底层 Buffer 的数量，合理的 Buffer 数量搭配合理的分区选择器，
     *                    可以让整个 Channels 写入无竞争或很少出现竞争。
     * @param bufferSize 指定每个 Buffer 的大小，合理的 Buffer 大小可以在满足缓冲能力的同时占用合理的内存大小。
     * @param partitioner
     * @param strategy
     */
    public Channels(int channelSize, int bufferSize, IDataPartitioner<T> partitioner, BufferStrategy strategy) {
        this.dataPartitioner = partitioner;
        this.strategy = strategy;
        bufferChannels = new Buffer[channelSize];
        for (int i = 0; i < channelSize; i++) {
            bufferChannels[i] = new Buffer<T>(bufferSize, strategy);
        }
        size = channelSize * bufferSize;
    }

    public boolean save(T data) {
        int index = dataPartitioner.partition(bufferChannels.length, data);
        int retryCountDown = 1;
        if (BufferStrategy.IF_POSSIBLE.equals(strategy)) {
            int maxRetryCount = dataPartitioner.maxRetryCount();
            if (maxRetryCount > 1) {
                retryCountDown = maxRetryCount;
            }
        }
        for (; retryCountDown > 0; retryCountDown--) {
            if (bufferChannels[index].save(data)) {
                return true;
            }
        }
        return false;
    }

    public void setPartitioner(IDataPartitioner<T> dataPartitioner) {
        this.dataPartitioner = dataPartitioner;
    }

    /**
     * override the strategy at runtime. Notice, this will override several channels one by one. So, when running
     * setStrategy, each channel may use different BufferStrategy
     *
     * @param strategy
     */
    public void setStrategy(BufferStrategy strategy) {
        for (Buffer<T> buffer : bufferChannels) {
            buffer.setStrategy(strategy);
        }
    }

    /**
     * get channelSize
     *
     * @return
     */
    public int getChannelSize() {
        return this.bufferChannels.length;
    }

    public long size() {
        return size;
    }

    public Buffer<T> getBuffer(int index) {
        return this.bufferChannels[index];
    }

    public void addCallback(QueueBlockingCallback<T> callback) {
        for (Buffer<T> channel : bufferChannels) {
            channel.addCallback(callback);
        }
    }
}
