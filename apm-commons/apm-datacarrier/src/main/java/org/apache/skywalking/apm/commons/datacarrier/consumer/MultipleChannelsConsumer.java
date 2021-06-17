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

import java.util.*;
import org.apache.skywalking.apm.commons.datacarrier.buffer.*;

/**
 *
 * MultipleChannelsConsumer 可以同时消费多个 Group，每个 Group 中的 IConsumer 对象包含了消费逻辑，Channels 对象包含了待消费的数据，
 * 需要注意的是，一旦 Channels 被添加到 MultipleChannelsConsumer 中，将会被一个 MultipleChannelsConsumer 完全消费，不会像
 * ConsumerThread 那样分区域部分消费。
 *
 * 在 run() 方法中，MultipleChannelsConsumer 线程会定时循环遍历其消费的全部 Group，一旦发现可消费的数据，就会循环调用
 * consume() 方法处理每个 Group。
 *
 * MultipleChannelsConsumer 底层通过一个 ArrayList 维护 Group 集合（consumeTargets 字段），MultipleChannelsConsumer
 * 通过 Copy-on-Write 的方式保证线程安全，即在调用 addNewTarget() 方法向 consumeTargets 集合添加 Group 时，会创建一个新的
 * ArrayList 集合并拷贝原集合内容，然后向新集合中添加数据，待新集合添加完成之后，直接替换原有集合。之所以这样做是因为在添加的过程中，
 * MultipleChannelsConsumer 线程可能正在循环处理 consumeTargets 集合，这也是 consumeTargets 用 volatile 修饰的原因。
 *
 * MultipleChannelsConsumer represent a single consumer thread, but support multiple channels with their {@link
 * IConsumer}s
 *
 * @author wusheng
 */
public class MultipleChannelsConsumer extends Thread {
    private volatile boolean running;
    private volatile ArrayList<Group> consumeTargets;
    private volatile long size;
    private final long consumeCycle;

    public MultipleChannelsConsumer(String threadName, long consumeCycle) {
        super(threadName);
        this.consumeTargets = new ArrayList<Group>();
        this.consumeCycle = consumeCycle;
    }

    @Override
    public void run() {
        running = true;

        while (running) {
            boolean hasData = false;
            for (Group target : consumeTargets) {
                hasData = hasData || consume(target);
            }

            if (!hasData) {
                try {
                    Thread.sleep(consumeCycle);
                } catch (InterruptedException e) {
                }
            }

        }

        // consumer thread is going to stop
        // consume the last time
        for (Group target : consumeTargets) {
            consume(target);

            target.consumer.onExit();
        }
    }

    private boolean consume(Group target) {
        boolean hasData;
        LinkedList consumeList = new LinkedList();
        for (int i = 0; i < target.channels.getChannelSize(); i++) {
            Buffer buffer = target.channels.getBuffer(i);
            // 将该Group中Channels全部可消费的数据都导出到consumeList列表
            consumeList.addAll(buffer.obtain());
        }

        if (hasData = consumeList.size() > 0) {
            try {
                // 通过该Group中相应的IConsumer消费数据
                target.consumer.consume(consumeList);
            } catch (Throwable t) {
                // 消费过程的异常处理
                target.consumer.onError(consumeList, t);
            }
        }
        return hasData;
    }

    /**
     * Add a new target channels.
     *
     * @param channels
     * @param consumer
     */
    public void addNewTarget(Channels channels, IConsumer consumer) {
        Group group = new Group(channels, consumer);
        // Recreate the new list to avoid change list while the list is used in consuming.
        ArrayList<Group> newList = new ArrayList<Group>();
        for (Group target : consumeTargets) {
            newList.add(target);
        }
        newList.add(group);
        consumeTargets = newList;
        size += channels.size();
    }

    public long size() {
        return size;
    }

    void shutdown() {
        running = false;
    }

    private class Group {
        private Channels channels;
        private IConsumer consumer;

        public Group(Channels channels, IConsumer consumer) {
            this.channels = channels;
            this.consumer = consumer;
        }
    }
}
