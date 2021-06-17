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

import org.apache.skywalking.apm.agent.core.context.tag.AbstractTag;
import org.apache.skywalking.apm.network.trace.component.Component;

/**
 * The <code>ExitSpan</code> represents a service consumer point, such as Feign, Okhttp client for a Http service.
 *
 * It is an exit point or a leaf span(our old name) of trace tree. In a single rpc call, because of a combination of
 * discovery libs, there maybe contain multi-layer exit point:
 *
 * The <code>ExitSpan</code> only presents the first one.
 *
 * Such as: Dubbox - Apache Httpcomponent - ...(Remote) The <code>ExitSpan</code> represents the Dubbox span, and ignore
 * the httpcomponent span's info.
 *
 * 当请求离开当前服务、进入其他服务时会创建 ExitSpan 类型的 Span。
 * 例如， Http Client 、RPC Client 发起远程调用或是 MQ-producer 生产消息时，都会产生该类型的 Span。
 *
 * 表示的是出口 Span，如果在一个调用栈里面出现多个插件嵌套的场景，也需要通过“栈”的方式进行处理，与上述逻辑类似，只会在第一个插件中创建 ExitSpan，
 * 后续调用的 ExitSpan.start() 方法并不会更新 startTime，只会增加栈的深度。当然，在设置 Tags、Log 等信息时也会进行判断，只有 stackDepth
 * 为 1 的时候，才会能正常写入相应字段。也就是说，ExitSpan 中只会记录最贴近当前服务侧的 Span 信息。
 *
 * 一个 TraceSegment 可以有多个 ExitSpan，例如，Dubbo A 服务在处理一个请求时，会调用 Dubbo B 服务，在得到响应之后，
 * 会紧接着调用 Dubbo C 服务，这样，该 TraceSegment 就有了两个完全独立的 ExitSpan。
 *
 * LocalSpan 则比较简单，它表示一个本地方法调用。LocalSpan 直接继承了 AbstractTracingSpan，由于它未继承 StackBasedTracingSpan，
 * 所以也不能 start 或 end 多次，在后面介绍 @Trace 注解的相关实现时，还会看到 LocalSpan 的身影。
 *
 * @author wusheng
 */
public class ExitSpan extends StackBasedTracingSpan implements WithPeerInfo {

    public ExitSpan(int spanId, int parentSpanId, String operationName, String peer) {
        super(spanId, parentSpanId, operationName, peer);
    }

    public ExitSpan(int spanId, int parentSpanId, int operationId, int peerId) {
        super(spanId, parentSpanId, operationId, peerId);
    }

    public ExitSpan(int spanId, int parentSpanId, int operationId, String peer) {
        super(spanId, parentSpanId, operationId, peer);
    }

    public ExitSpan(int spanId, int parentSpanId, String operationName, int peerId) {
        super(spanId, parentSpanId, operationName, peerId);
    }

    /**
     * Set the {@link #startTime}, when the first start, which means the first service provided.
     */
    @Override
    public ExitSpan start() {
        if (++stackDepth == 1) {
            super.start();
        }
        return this;
    }

    @Override
    public ExitSpan tag(String key, String value) {
        if (stackDepth == 1) {
            super.tag(key, value);
        }
        return this;
    }

    @Override public AbstractTracingSpan tag(AbstractTag tag, String value) {
        if (stackDepth == 1 || tag.isCanOverwrite()) {
            super.tag(tag, value);
        }
        return this;
    }

    @Override
    public AbstractTracingSpan setLayer(SpanLayer layer) {
        if (stackDepth == 1) {
            return super.setLayer(layer);
        } else {
            return this;
        }
    }

    @Override
    public AbstractTracingSpan setComponent(Component component) {
        if (stackDepth == 1) {
            return super.setComponent(component);
        } else {
            return this;
        }
    }

    @Override
    public AbstractTracingSpan setComponent(String componentName) {
        if (stackDepth == 1) {
            return super.setComponent(componentName);
        } else {
            return this;
        }
    }

    @Override
    public ExitSpan log(Throwable t) {
        if (stackDepth == 1) {
            super.log(t);
        }
        return this;
    }

    @Override
    public AbstractTracingSpan setOperationName(String operationName) {
        if (stackDepth == 1) {
            return super.setOperationName(operationName);
        } else {
            return this;
        }
    }

    @Override
    public AbstractTracingSpan setOperationId(int operationId) {
        if (stackDepth == 1) {
            return super.setOperationId(operationId);
        } else {
            return this;
        }
    }

    @Override
    public int getPeerId() {
        return peerId;
    }

    @Override
    public String getPeer() {
        return peer;
    }

    @Override public boolean isEntry() {
        return false;
    }

    @Override public boolean isExit() {
        return true;
    }
}
