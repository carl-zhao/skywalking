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

import org.apache.skywalking.apm.agent.core.dictionary.DictionaryUtil;
import org.apache.skywalking.apm.network.trace.component.Component;

/**
 * The <code>EntrySpan</code> represents a service provider point, such as Tomcat server entrance.
 *
 * It is a start point of {@link TraceSegment}, even in a complex application, there maybe have multi-layer entry point,
 * the <code>EntrySpan</code> only represents the first one.
 *
 * But with the last <code>EntrySpan</code>'s tags and logs, which have more details about a service provider.
 *
 * Such as: Tomcat Embed - Dubbox The <code>EntrySpan</code> represents the Dubbox span.
 *
 * 当请求进入服务时会创建 EntrySpan 类型的 Span，它也是 TraceSegment 中的第一个 Span。
 * 例如，HTTP 服务、RPC 服务、MQ-Consumer 等入口服务的插件在接收到请求时都会创建相应的 EntrySpan。
 *
 * @author wusheng
 */
public class EntrySpan extends StackBasedTracingSpan {

    private int currentMaxDepth;

    public EntrySpan(int spanId, int parentSpanId, String operationName) {
        super(spanId, parentSpanId, operationName);
        this.currentMaxDepth = 0;
    }

    public EntrySpan(int spanId, int parentSpanId, int operationId) {
        super(spanId, parentSpanId, operationId);
        this.currentMaxDepth = 0;
    }

    /**
     * Set the {@link #startTime}, when the first start, which means the first service provided.
     *
     * 1、将 stackDepth 字段（定义在 StackBasedTracingSpan 中）加 1，stackDepth 表示当前所处的插件栈深度 。
     * 2、更新 currentMaxDepth 字段（定义在 EntrySpan 中），currentMaxDepth 会记录该EntrySpan 到达过的插件栈的最深位置。
     * 3、此时第一次启动 EntrySpan 时会更新 startTime 字段，记录请求开始时间。
     *
     * 注意：
     * 一是在调用 start() 方法时，会将之前设置的 component、Tags、Log 等信息全部清理掉（startTime不会清理），上例中请求到 Spring MVC
     * 插件之前（即 ② 处之前）设置的这些信息都会被清理掉。
     *
     * 二是 stackDepth 与 currentMaxDepth 不相等时（上例中 ③ 处），无法记录上述字段的信息。通过这两点，我们知道 EntrySpan 实际上
     * 只会记录最贴近业务侧的 Span 信息。
     */
    @Override
    public EntrySpan start() {
        if ((currentMaxDepth = ++stackDepth) == 1) {
            super.start();
        }
        clearWhenRestart();
        return this;
    }

    @Override
    public EntrySpan tag(String key, String value) {
        if (stackDepth == currentMaxDepth) {
            super.tag(key, value);
        }
        return this;
    }

    @Override
    public AbstractTracingSpan setLayer(SpanLayer layer) {
        if (stackDepth == currentMaxDepth) {
            return super.setLayer(layer);
        } else {
            return this;
        }
    }

    @Override
    public AbstractTracingSpan setComponent(Component component) {
        if (stackDepth == currentMaxDepth) {
            return super.setComponent(component);
        } else {
            return this;
        }
    }

    @Override
    public AbstractTracingSpan setComponent(String componentName) {
        if (stackDepth == currentMaxDepth) {
            return super.setComponent(componentName);
        } else {
            return this;
        }
    }

    @Override
    public AbstractTracingSpan setOperationName(String operationName) {
        if (stackDepth == currentMaxDepth) {
            return super.setOperationName(operationName);
        } else {
            return this;
        }
    }

    @Override
    public AbstractTracingSpan setOperationId(int operationId) {
        if (stackDepth == currentMaxDepth) {
            return super.setOperationId(operationId);
        } else {
            return this;
        }
    }

    @Override
    public EntrySpan log(Throwable t) {
        super.log(t);
        return this;
    }

    @Override public boolean isEntry() {
        return true;
    }

    @Override public boolean isExit() {
        return false;
    }

    private void clearWhenRestart() {
        this.componentId = DictionaryUtil.nullValue();
        this.componentName = null;
        this.layer = null;
        this.logs = null;
        this.tags = null;
    }
}
