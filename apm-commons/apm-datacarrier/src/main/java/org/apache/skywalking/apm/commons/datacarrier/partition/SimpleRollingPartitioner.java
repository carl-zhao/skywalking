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


package org.apache.skywalking.apm.commons.datacarrier.partition;

/**
 *
 * 简单循环自增选择器，使用无锁整型（volatile 修饰）的自增并取模，选择要写入的 Buffer 。
 * 当然，在高负载时会产生批量连续写入一个 Buffer 的情况，但在中低负载情况下，可以很好的避免不同线程写
 * 入数据量不均衡的问题，从而提供较好性能。
 * use normal int to rolling.
 *
 *
 * Created by wusheng on 2016/10/25.
 */
public class SimpleRollingPartitioner<T> implements IDataPartitioner<T> {
    private volatile int i = 0;

    @Override
    public int partition(int total, T data) {
        return Math.abs(i++ % total);
    }

    @Override
    public int maxRetryCount() {
        return 3;
    }
}
