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

import java.util.*;
import org.apache.skywalking.apm.commons.datacarrier.callback.QueueBlockingCallback;
import org.apache.skywalking.apm.commons.datacarrier.common.AtomicRangeInteger;

/**
 * Buffer 可以指定下面三种写入策略，这些策略只在 Buffer 写满的情况下才生效：
 * BLOCKING 策略（默认）：写入线程阻塞等待，直到 Buffer 有空闲空间为止。如果选择了 BLOCKING 策略，我们可以向 Buffer 中注册
 * Callback 回调，当发生阻塞时 Callback 会收到相应的事件。
 * OVERRIDE 策略：覆盖旧数据，会导致缓存在 Buffer 中的旧数据丢失。
 * IF_POSSIBLE 策略：如果无法写入则直接返回 false，由上层应用判断如何处理。
 *
 * Created by wusheng on 2016/10/25.
 */
public class Buffer<T> {
    private final Object[] buffer;
    private BufferStrategy strategy;
    private AtomicRangeInteger index;
    private List<QueueBlockingCallback<T>> callbacks;

    Buffer(int bufferSize, BufferStrategy strategy) {
        buffer = new Object[bufferSize];
        this.strategy = strategy;
        index = new AtomicRangeInteger(0, bufferSize);
        callbacks = new LinkedList<QueueBlockingCallback<T>>();
    }

    void setStrategy(BufferStrategy strategy) {
        this.strategy = strategy;
    }

    void addCallback(QueueBlockingCallback<T> callback) {
        callbacks.add(callback);
    }

    boolean save(T data) {
        // AtomicRangeInteger已经处理了并发问题，这里i对应的位置只有当前线程操作
        int i = index.getAndIncrement();
        // 如果当前位置空闲，可以直接填充即可，否则需要按照策略进行处理
        if (buffer[i] != null) {
            switch (strategy) {
                // BLOCKING策略
                case BLOCKING:
                    boolean isFirstTimeBlocking = true;
                    // 自旋等待下标为i的位置被释放
                    while (buffer[i] != null) {
                        if (isFirstTimeBlocking) {
                            // 阻塞当前线程，并通知所有Callback
                            isFirstTimeBlocking = false;
                            for (QueueBlockingCallback<T> callback : callbacks) {
                                callback.notify(data);
                            }
                        }
                        try {
                            // sleep
                            Thread.sleep(1L);
                        } catch (InterruptedException e) {
                        }
                    }
                    break;
                // IF_POSSIBLE策略直接返回false
                case IF_POSSIBLE:
                    return false;
                // OVERRIDE策略直接走下面的赋值逻辑，覆盖旧数据
                case OVERRIDE:
                default:
            }
        }
        // 向下标为i的位置填充数据
        buffer[i] = data;
        return true;
    }

    public int getBufferSize() {
        return buffer.length;
    }

    /**
     * obtain() 方法提供了一次性读取（并清理） Buffer 中全部数据的功能，同时也提供了部分读取的重载
     * @return
     */
    public LinkedList<T> obtain() {
        return this.obtain(0, buffer.length);
    }

    public LinkedList<T> obtain(int start, int end) {
        LinkedList<T> result = new LinkedList<T>();
        // 将 start~end 之间的元素返回，消费者消费这个result集合就行了
        for (int i = start; i < end; i++) {
            if (buffer[i] != null) {
                result.add((T)buffer[i]);
                buffer[i] = null;
            }
        }
        return result;
    }

}
