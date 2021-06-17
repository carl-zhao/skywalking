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

import org.apache.skywalking.apm.agent.core.dictionary.DictionaryManager;
import org.apache.skywalking.apm.agent.core.dictionary.DictionaryUtil;
import org.apache.skywalking.apm.agent.core.dictionary.PossibleFound;
import org.apache.skywalking.apm.network.language.agent.v2.SpanObjectV2;

/**
 * The <code>StackBasedTracingSpan</code> represents a span with an inside stack construction.
 *
 * This kind of span can start and finish multi times in a stack-like invoke line.
 *
 * 在继承 AbstractTracingSpan 存储 Span 核心数据能力的同时，还引入了栈的概念，这种 Span 可以多次调用 start() 方法和 end() 方法，
 * 但是两者调用次数必须要配对，类似出栈和入栈的操作。
 *
 * 下面以 EntrySpan 为例说明为什么需要“栈”这个概念，EntrySpan 表示的是一个服务的入口 Span，是 TraceSegment 的第一个 Span，
 * 出现在服务提供方的入口，例如，Dubbo Provider、Tomcat、Spring MVC，等等。 那么为什么 EntrySpan 继承 StackBasedTracingSpan 呢？
 * 从前面对 SkyWalking Agent 的分析来看，Agent 插件只会拦截指定类的指定方法并对其进行增强，例如，Tomcat、Spring MVC
 * 等插件的增强逻辑中就包含了创建 EntrySpan 的逻辑（后面在分析具体插件实现的时候，会看到具体的实现代码）。很多 Web 项目会同时使用到这两个插件，
 * 难道一个 TraceSegment 要有两个 EntrySpan 吗？显然不行。
 *
 * SkyWalking 的处理方式是让 EntrySpan 继承了 StackBasedTracingSpan
 *
 * 除了将“栈”概念与 EntrySpan 结合之外，还添加了 peer（以及 peerId）字段来记录远端地址，在发送远程调用时创建的 ExitSpan 会将该记录用于对端地址。
 *
 * @author wusheng
 */
public abstract class StackBasedTracingSpan extends AbstractTracingSpan {
    protected int stackDepth;
    protected String peer;
    protected int peerId;

    protected StackBasedTracingSpan(int spanId, int parentSpanId, String operationName) {
        super(spanId, parentSpanId, operationName);
        this.stackDepth = 0;
        this.peer = null;
        this.peerId = DictionaryUtil.nullValue();
    }

    protected StackBasedTracingSpan(int spanId, int parentSpanId, int operationId) {
        super(spanId, parentSpanId, operationId);
        this.stackDepth = 0;
        this.peer = null;
        this.peerId = DictionaryUtil.nullValue();
    }

    public StackBasedTracingSpan(int spanId, int parentSpanId, int operationId, int peerId) {
        super(spanId, parentSpanId, operationId);
        this.peer = null;
        this.peerId = peerId;
    }

    public StackBasedTracingSpan(int spanId, int parentSpanId, int operationId, String peer) {
        super(spanId, parentSpanId, operationId);
        this.peer = peer;
        this.peerId = DictionaryUtil.nullValue();
    }

    protected StackBasedTracingSpan(int spanId, int parentSpanId, String operationName, String peer) {
        super(spanId, parentSpanId, operationName);
        this.peer = peer;
        this.peerId = DictionaryUtil.nullValue();
    }

    protected StackBasedTracingSpan(int spanId, int parentSpanId, String operationName, int peerId) {
        super(spanId, parentSpanId, operationName);
        this.peer = null;
        this.peerId = peerId;
    }

    @Override
    public SpanObjectV2.Builder transform() {
        SpanObjectV2.Builder spanBuilder = super.transform();
        if (peerId != DictionaryUtil.nullValue()) {
            spanBuilder.setPeerId(peerId);
        } else {
            if (peer != null) {
                spanBuilder.setPeer(peer);
            }
        }
        return spanBuilder;
    }

    @Override
    public boolean finish(TraceSegment owner) {
        if (--stackDepth == 0) {
            if (this.operationId == DictionaryUtil.nullValue()) {
                this.operationId = (Integer)DictionaryManager.findEndpointSection()
                    .findOrPrepare4Register(owner.getServiceId(), operationName, this.isEntry(), this.isExit())
                    .doInCondition(
                        new PossibleFound.FoundAndObtain() {
                            @Override public Object doProcess(int value) {
                                return value;
                            }
                        },
                        new PossibleFound.NotFoundAndObtain() {
                            @Override public Object doProcess() {
                                return DictionaryUtil.nullValue();
                            }
                        }
                    );
            }
            return super.finish(owner);
        } else {
            return false;
        }
    }

    @Override public AbstractSpan setPeer(final String remotePeer) {
        DictionaryManager.findNetworkAddressSection().find(remotePeer).doInCondition(
            new PossibleFound.Found() {
                @Override
                public void doProcess(int remotePeerId) {
                    peerId = remotePeerId;
                }
            }, new PossibleFound.NotFound() {
                @Override
                public void doProcess() {
                    peer = remotePeer;
                }
            }
        );
        return this;
    }
}
