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

import org.apache.skywalking.apm.commons.datacarrier.buffer.Channels;

/**
 *
 * IDriver 接口会将前文介绍的 IConsumer 消费者以及 ConsumerThread 线程或 MultipleChannelsConsumer 线程按照一定的消费模式集成到一起，
 * 提供更加简单易用的 API。
 *
 * IDriver 接口的继承关系如下图所示，其中依赖 ConsumerThread 的实现是 ConsumerDriver ，依赖 MultipleChannelsConsumer
 * 的实现是 BulkConsumerPool
 *
 * The driver of consumer.
 *
 * @author wusheng
 */
public interface IDriver {

    /**
     * 检测当前IDriver是否正在运行
     * @param channels
     */
    boolean isRunning(Channels channels);

    /**
     * 关闭消费线程
     * @param channels
     */
    void close(Channels channels);

    /**
     * 启动消费线程
     * @param channels
     * @return
     */
    void begin(Channels channels);
}
