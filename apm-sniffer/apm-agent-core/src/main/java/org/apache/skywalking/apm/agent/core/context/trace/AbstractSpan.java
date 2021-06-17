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

package org.apache.skywalking.apm.agent.core.context.trace;

import java.util.Map;
import org.apache.skywalking.apm.agent.core.context.AsyncSpan;
import org.apache.skywalking.apm.agent.core.context.tag.AbstractTag;
import org.apache.skywalking.apm.network.trace.component.Component;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;

/**
 * The <code>AbstractSpan</code> represents the span's skeleton, which contains all open methods.
 *
 * @author wusheng
 */
public interface AbstractSpan extends AsyncSpan {
    /**
     * Set the component id, which defines in {@link ComponentsDefine}
     *
     * 用于设置组件类型。它有两个重载，在 AbstractTracingSpan 实现中，有 componentId 和 componentName 两个字段，
     * 两个重载分别用于设置这两个字段。在 ComponentsDefine 中可以找到 SkyWalking 目前支持的组件类型。
     *
     * @param component
     * @return the span for chaining.
     */
    AbstractSpan setComponent(Component component);

    /**
     * Only use this method in explicit instrumentation, like opentracing-skywalking-bridge. It is highly recommended
     * not to use this method for performance reasons.
     *
     * @param componentName
     * @return the span for chaining.
     */
    AbstractSpan setComponent(String componentName);

    /**
     * 用于设置 SpanLayer，也就是当前 Span 所处的位置。SpanLayer 是个枚举，可选项有 DB、RPC_FRAMEWORK、HTTP、MQ、CACHE。
     * @param layer
     * @return
     */
    AbstractSpan setLayer(SpanLayer layer);

    /**
     * Set a key:value tag on the Span.
     *
     * @return this Span instance, for chaining
     */
    @Deprecated
    AbstractSpan tag(String key, String value);

    /**
     *
     * 用于为当前 Span 添加键值对的 Tags。一个 Span 可以有多个 Tags。AbstractTag 中不仅包含了 String 类型的
     * Key 值，还包含了 Tag 的 ID 以及 canOverwrite 标识。AbstractTracingSpan 实现通过维护一个  
     * List<TagValuePair> 集合（tags 字段）来记录 Tag 信息，TagValuePair 中则封装了 AbstractTag 类型的 Key
     * 以及 String 类型的 Value。
     *
     * @param tag
     * @param value
     * @return
     */
    AbstractSpan tag(AbstractTag tag, String value);

    /**
     * Record an exception event of the current walltime timestamp.
     *
     * 用于向当前 Span 中添加 Log，一个 Span 可以包含多条日志。在 AbstractTracingSpan 实现中通过维护一个 List<LogDataEntity>
     * 集合（logs 字段）来记录 Log。LogDataEntity 会记录日志的时间戳以及 KV 信息，以异常日志为例，其中就会包含一个 Key 为“stack”的 KV，
     * 其 value 为异常堆栈。
     *
     * @param t any subclass of {@link Throwable}, which occurs in this span.
     * @return the Span, for chaining
     */
    AbstractSpan log(Throwable t);

    AbstractSpan errorOccurred();

    /**
     * @return true if the actual span is an entry span.
     *
     * 判断当前是否是 EntrySpan。EntrySpan 的具体实现后面详细介绍。
     */
    boolean isEntry();

    /**
     * @return true if the actual span is an exit span.
     *
     * 判断当前是否是 ExitSpan。ExitSpan  的具体实现后面详细介绍。
     */
    boolean isExit();

    /**
     * Record an event at a specific timestamp.
     *
     * @param timestamp The explicit timestamp for the log record.
     * @param event the events
     * @return the Span, for chaining
     */
    AbstractSpan log(long timestamp, Map<String, ?> event);

    /**
     * Sets the string name for the logical operation this span represents.
     *
     * setOperationName()/setOperationId() 方法：
     * 用来设置 operation 名称（或 operation ID），这两个信息是互斥的。它们在 AbstractSpan 的具体实现（即 AbstractTracingSpan）中，
     * 分别对应 operationId 和 operationName 两个字段，两者只能有一个字段有值。
     *
     * @return this Span instance, for chaining
     */
    AbstractSpan setOperationName(String operationName);

    /**
     * Start a span.
     *
     * 开启 Span，其中会设置当前 Span 的开始时间以及调用层级等信息。
     *
     * @return this Span instance, for chaining
     */
    AbstractSpan start();

    /**
     * Get the id of span
     *
     * 用来获得当前 Span 的 ID，Span ID 是一个 int 类型的值，在其所属的 TraceSegment 中唯一，在创建 Span 对象时生成，从 0 开始自增。
     *
     * @return id value.
     */
    int getSpanId();

    int getOperationId();

    /**
     * operationName 即前文介绍的 EndpointName，可以是任意字符串，
     * 例如，在 Tomcat 插件中 operationName 就是 URI 地址，Dubbo 插件中 operationName 为 URL + 接口方法签名。
     * @return
     */
    String getOperationName();

    /**
     * setOperationName()/setOperationId() 方法：
     * 用来设置 operation 名称（或 operation ID），这两个信息是互斥的。它们在 AbstractSpan 的具体实现（即 AbstractTracingSpan）中，
     * 分别对应 operationId 和 operationName 两个字段，两者只能有一个字段有值。
     * @param operationId
     * @return
     */
    AbstractSpan setOperationId(int operationId);

    /**
     * Reference other trace segment.
     *
     * 用于设置关联的 TraceSegment 。
     *
     * @param ref segment ref
     */
    void ref(TraceSegmentRef ref);

    AbstractSpan start(long startTime);

    AbstractSpan setPeer(String remotePeer);
}
