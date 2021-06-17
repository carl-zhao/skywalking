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
 * The <code>AbstractTracerContext</code> represents the tracer context manager.
 *
 * @author wusheng
 */
public interface AbstractTracerContext {
    /**
     * Prepare for the cross-process propagation. How to initialize the carrier, depends on the implementation.
     *
     * 在跨进程调用之前，调用方会通过 inject() 方法将当前 Context 上下文记录的全部信息注入到 ContextCarrier 参数中，
     * Agent 后续会将 ContextCarrier 序列化并随远程调用进行传播。ContextCarrier 的具体实现在后面会详细分析。
     *
     * @param carrier to carry the context for crossing process.
     */
    void inject(ContextCarrier carrier);

    /**
     * Build the reference between this segment and a cross-process segment. How to build, depends on the
     * implementation.
     *
     * 跨进程调用的接收方会反序列化得到 ContextCarrier 对象，然后通过 extract() 方法从 ContextCarrier 中读取上游传递下来的
     * Trace 信息并记录到当前的 Context 上下文中。
     *
     * @param carrier carried the context from a cross-process segment.
     */
    void extract(ContextCarrier carrier);

    /**
     * Capture a snapshot for cross-thread propagation. It's a similar concept with ActiveSpan.Continuation in
     * OpenTracing-java How to build, depends on the implementation.
     *
     * 在跨线程调用之前，SkyWalking Agent 会通过 capture() 方法将当前 Context 进行快照，然后将快照传递给其他线程。
     *
     * @return the {@link ContextSnapshot} , which includes the reference context.
     */
    ContextSnapshot capture();

    /**
     * Build the reference between this segment and a cross-thread segment. How to build, depends on the
     * implementation.
     *
     * 跨线程调用的接收方会从收到的 ContextSnapshot 中读取 Trace 信息并填充到当前 Context 上下文中。
     *
     * @param snapshot from {@link #capture()} in the parent thread.
     */
    void continued(ContextSnapshot snapshot);

    /**
     * Get the global trace id, if needEnhance. How to build, depends on the implementation.
     *
     * 用于获取当前 Context 关联的 TraceId。
     *
     * @return the string represents the id.
     */
    String getReadableGlobalTraceId();

    /**
     * Create an entry span
     *
     * 用于创建 Span。
     *
     * @param operationName most likely a service name
     * @return the span represents an entry point of this segment.
     */
    AbstractSpan createEntrySpan(String operationName);

    /**
     * Create a local span
     *
     * 用于创建 Span。
     *
     * @param operationName most likely a local method signature, or business name.
     * @return the span represents a local logic block.
     */
    AbstractSpan createLocalSpan(String operationName);

    /**
     * Create an exit span
     *
     * 用于创建 Span。
     *
     * @param operationName most likely a service name of remote
     * @param remotePeer the network id(ip:port, hostname:port or ip1:port1,ip2,port, etc.)
     * @return the span represent an exit point of this segment.
     */
    AbstractSpan createExitSpan(String operationName, String remotePeer);

    /**
     * @return the active span of current tracing context(stack)
     *
     * 用于获得当前活跃的 Span。在 TraceSegment 中，Span 也是按照栈的方式进行维护的，
     * 因为 Span 的生命周期符合栈的特性，即：先创建的 Span 后结束。
     *
     */
    AbstractSpan activeSpan();

    /**
     * Finish the given span, and the given span should be the active span of current tracing context(stack)
     *
     * 用于停止指定 Span。
     *
     * @param span to finish
     * @return true when context should be clear.
     */
    boolean stopSpan(AbstractSpan span);

    /**
     * Notify this context, current span is going to be finished async in another thread.
     *
     * @return The current context
     */
    AbstractTracerContext awaitFinishAsync();

    /**
     * The given span could be stopped officially.
     *
     * @param span to be stopped.
     */
    void asyncStop(AsyncSpan span);
}
