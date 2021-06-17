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

package org.apache.skywalking.apm.agent.core.context;

import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;

/**
 * Span could use these APIs to active and extend its lift cycle across thread.
 *
 * This is typical used in async plugin, especially RPC plugins.
 *
 * TraceSegment 是由多个 Span 构成的，AbstractSpan 抽象类是 SkyWalking 对 Span 概念的抽象
 *
 * @author wusheng
 */
public interface AsyncSpan {
    /**
     * The span finish at current tracing context, but the current span is still alive, until {@link #asyncFinish}
     * called.
     *
     * This method must be called
     *
     * 1. In original thread(tracing context).
     * 2. Current span is active span.
     *
     * During alive, tags, logs and attributes of the span could be changed, in any thread.
     *
     * The execution times of {@link #prepareForAsync} and {@link #asyncFinish()} must match.
     *
     * Span 在当前线程结束了，但是未被彻底关闭，依然是存活的。
     *
     * @return the current span
     */
    AbstractSpan prepareForAsync();

    /**
     * Notify the span, it could be finished.
     *
     * The execution times of {@link #prepareForAsync} and {@link #asyncFinish()} must match.
     *
     * 当前 Span 真正关闭。它与 prepareForAsync() 方法成对出现。
     *
     * @return the current span
     */
    AbstractSpan asyncFinish();
}
