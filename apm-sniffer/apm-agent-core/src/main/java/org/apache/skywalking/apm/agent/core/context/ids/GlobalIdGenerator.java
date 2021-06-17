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

package org.apache.skywalking.apm.agent.core.context.ids;

import java.util.Random;
import org.apache.skywalking.apm.agent.core.conf.RemoteDownstreamConfig;
import org.apache.skywalking.apm.agent.core.dictionary.DictionaryUtil;

/**
 * GlobalIdGenerator 不仅用于生成 Trace ID ，其他需要唯一 ID 的地方也会通过其 nextSeq() 方法生成。
 */
public final class GlobalIdGenerator {
    private static final ThreadLocal<IDContext> THREAD_ID_SEQUENCE = new ThreadLocal<IDContext>() {
        @Override
        protected IDContext initialValue() {
            return new IDContext(System.currentTimeMillis(), (short)0);
        }
    };

    private GlobalIdGenerator() {
    }

    /**
     * Generate a new id, combined by three long numbers.
     *
     * The first one represents application instance id. (most likely just an integer value, would be helpful in
     * protobuf)
     *
     * The second one represents thread id. (most likely just an integer value, would be helpful in protobuf)
     *
     * The third one also has two parts,
     * 1) a timestamp, measured in milliseconds
     * 2) a seq, in current thread, between 0(included) and 9999(included)
     *
     * Notice, a long costs 8 bytes, three longs cost 24 bytes. And at the same time, a char costs 2 bytes. So
     * sky-walking's old global and segment id like this: "S.1490097253214.-866187727.57515.1.1" which costs at least 72
     * bytes.
     *
     * @return an array contains three long numbers, which represents a unique id.
     */
    public static ID generate() {
        if (RemoteDownstreamConfig.Agent.SERVICE_INSTANCE_ID == DictionaryUtil.nullValue()) {
            throw new IllegalStateException();
        }
        // THREAD_ID_SEQUENCE是 ThreadLocal<IDContext>类型，即每个线程维护一个 IDContext对象
        IDContext context = THREAD_ID_SEQUENCE.get();

        return new ID(
            RemoteDownstreamConfig.Agent.SERVICE_INSTANCE_ID, // service_intance_id
            Thread.currentThread().getId(), // 当前线程的ID
            context.nextSeq() // 线程内生成的序列号
        );
    }

    private static class IDContext {
        private long lastTimestamp;
        private short threadSeq;

        // Just for considering time-shift-back only.
        private long runRandomTimestamp;
        private int lastRandomValue;
        private Random random;

        private IDContext(long lastTimestamp, short threadSeq) {
            this.lastTimestamp = lastTimestamp;
            this.threadSeq = threadSeq;
        }

        /**
         * IDContext.nextSeq() 方法的实现如下，其中 timestamp() 方法在返回时间戳的时候，
         * 会处理时间回拨的场景（使用 Random 随机生成一个时间戳），nextThreadSeq() 方法的返回值在 [0 , 9999] 这个范围内循环
         * @return
         */
        private long nextSeq() {
            return timestamp() * 10000 + nextThreadSeq();
        }

        private long timestamp() {
            long currentTimeMillis = System.currentTimeMillis();

            if (currentTimeMillis < lastTimestamp) {
                // Just for considering time-shift-back by Ops or OS. @hanahmily 's suggestion.
                if (random == null) {
                    random = new Random();
                }
                if (runRandomTimestamp != currentTimeMillis) {
                    lastRandomValue = random.nextInt();
                    runRandomTimestamp = currentTimeMillis;
                }
                return lastRandomValue;
            } else {
                lastTimestamp = currentTimeMillis;
                return lastTimestamp;
            }
        }

        private short nextThreadSeq() {
            if (threadSeq == 10000) {
                threadSeq = 0;
            }
            return threadSeq++;
        }
    }
}
