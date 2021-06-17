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

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.context.trace.*;
import org.apache.skywalking.apm.agent.core.dictionary.*;
import org.apache.skywalking.apm.agent.core.logging.api.*;
import org.apache.skywalking.apm.agent.core.sampling.SamplingService;
import org.apache.skywalking.apm.util.StringUtil;

/**
 * The <code>TracingContext</code> represents a core tracing logic controller. It build the final {@link
 * TracingContext}, by the stack mechanism, which is similar with the codes work.
 *
 * In opentracing concept, it means, all spans in a segment tracing context(thread) are CHILD_OF relationship, but no
 * FOLLOW_OF.
 *
 * In skywalking core concept, FOLLOW_OF is an abstract concept when cross-process MQ or cross-thread async/batch tasks
 * happen, we used {@link TraceSegmentRef} for these scenarios. Check {@link TraceSegmentRef} which is from {@link
 * ContextCarrier} or {@link ContextSnapshot}.
 *
 * @author wusheng
 * @author zhang xin
 */
public class TracingContext implements AbstractTracerContext {
    private static final ILog logger = LogManager.getLogger(TracingContext.class);
    private long lastWarningTimestamp = 0;

    /**
     * @see {@link SamplingService}
     * 负责完成 Agent 端的 Trace 采样，后面会展开介绍具体的采样逻辑。
     */
    private SamplingService samplingService;

    /**
     * The final {@link TraceSegment}, which includes all finished spans.
     *
     * 它是与当前 Context 上下文关联的 TraceSegment 对象，在 TracingContext 的构造方法中会创建该对象。
     *
     */
    private TraceSegment segment;

    /**
     * Active spans stored in a Stack, usually called 'ActiveSpanStack'. This {@link LinkedList} is the in-memory
     * storage-structure. <p> I use {@link LinkedList#removeLast()}, {@link LinkedList#addLast(Object)} and {@link
     * LinkedList#last} instead of {@link #pop()}, {@link #push(AbstractSpan)}, {@link #peek()}
     *
     * 用于记录当前 TraceSegment 中所有活跃的 Span（即未关闭的 Span）。实际上 activeSpanStack 字段是作为栈使用的，
     * TracingContext 提供了 push() 、pop() 、peek() 三个标准的栈方法，以及 first() 方法来访问栈底元素。
     *
     */
    private LinkedList<AbstractSpan> activeSpanStack = new LinkedList<AbstractSpan>();

    /**
     * A counter for the next span.
     * 它是 Span ID 自增序列，初始值为 0。该字段的自增操作都是在一个线程中完成的，所以无需加锁。
     */
    private int spanIdGenerator;

    /**
     * The counter indicates
     */
    private volatile AtomicInteger asyncSpanCounter;
    private volatile boolean isRunningInAsyncMode;
    private volatile ReentrantLock asyncFinishLock;

    /**
     * Initialize all fields with default value.
     */
    TracingContext() {
        this.segment = new TraceSegment();
        this.spanIdGenerator = 0;
        samplingService = ServiceManager.INSTANCE.findService(SamplingService.class);
        isRunningInAsyncMode = false;
    }

    /**
     * Inject the context into the given carrier, only when the active span is an exit one.
     *
     * @param carrier to carry the context for crossing process.
     * @throws IllegalStateException if the active span isn't an exit one. Ref to {@link
     * AbstractTracerContext#inject(ContextCarrier)}
     */
    @Override
    public void inject(ContextCarrier carrier) {
        AbstractSpan span = this.activeSpan();
        if (!span.isExit()) {
            throw new IllegalStateException("Inject can be done only in Exit Span");
        }

        WithPeerInfo spanWithPeer = (WithPeerInfo)span;
        String peer = spanWithPeer.getPeer();
        int peerId = spanWithPeer.getPeerId();

        carrier.setTraceSegmentId(this.segment.getTraceSegmentId());
        carrier.setSpanId(span.getSpanId());

        carrier.setParentServiceInstanceId(segment.getApplicationInstanceId());

        if (DictionaryUtil.isNull(peerId)) {
            carrier.setPeerHost(peer);
        } else {
            carrier.setPeerId(peerId);
        }
        List<TraceSegmentRef> refs = this.segment.getRefs();
        int operationId;
        String operationName;
        int entryApplicationInstanceId;
        if (refs != null && refs.size() > 0) {
            TraceSegmentRef ref = refs.get(0);
            operationId = ref.getEntryEndpointId();
            operationName = ref.getEntryEndpointName();
            entryApplicationInstanceId = ref.getEntryServiceInstanceId();
        } else {
            AbstractSpan firstSpan = first();
            operationId = firstSpan.getOperationId();
            operationName = firstSpan.getOperationName();
            entryApplicationInstanceId = this.segment.getApplicationInstanceId();
        }
        carrier.setEntryServiceInstanceId(entryApplicationInstanceId);

        if (operationId == DictionaryUtil.nullValue()) {
            if (!StringUtil.isEmpty(operationName)) {
                carrier.setEntryEndpointName(operationName);
            }
        } else {
            carrier.setEntryEndpointId(operationId);
        }

        int parentOperationId = first().getOperationId();
        if (parentOperationId == DictionaryUtil.nullValue()) {
            carrier.setParentEndpointName(first().getOperationName());
        } else {
            carrier.setParentEndpointId(parentOperationId);
        }

        carrier.setDistributedTraceIds(this.segment.getRelatedGlobalTraces());
    }

    /**
     * Extract the carrier to build the reference for the pre segment.
     *
     * @param carrier carried the context from a cross-process segment. Ref to {@link
     * AbstractTracerContext#extract(ContextCarrier)}
     */
    @Override
    public void extract(ContextCarrier carrier) {
        TraceSegmentRef ref = new TraceSegmentRef(carrier);
        this.segment.ref(ref);
        this.segment.relatedGlobalTraces(carrier.getDistributedTraceId());
        AbstractSpan span = this.activeSpan();
        if (span instanceof EntrySpan) {
            span.ref(ref);
        }
    }

    /**
     * Capture the snapshot of current context.
     *
     * @return the snapshot of context for cross-thread propagation Ref to {@link AbstractTracerContext#capture()}
     */
    @Override
    public ContextSnapshot capture() {
        List<TraceSegmentRef> refs = this.segment.getRefs();
        ContextSnapshot snapshot = new ContextSnapshot(segment.getTraceSegmentId(),
            activeSpan().getSpanId(),
            segment.getRelatedGlobalTraces());
        int entryOperationId;
        String entryOperationName;
        int entryApplicationInstanceId;
        AbstractSpan firstSpan = first();
        if (refs != null && refs.size() > 0) {
            TraceSegmentRef ref = refs.get(0);
            entryOperationId = ref.getEntryEndpointId();
            entryOperationName = ref.getEntryEndpointName();
            entryApplicationInstanceId = ref.getEntryServiceInstanceId();
        } else {
            entryOperationId = firstSpan.getOperationId();
            entryOperationName = firstSpan.getOperationName();
            entryApplicationInstanceId = this.segment.getApplicationInstanceId();
        }
        snapshot.setEntryApplicationInstanceId(entryApplicationInstanceId);

        if (entryOperationId == DictionaryUtil.nullValue()) {
            if (!StringUtil.isEmpty(entryOperationName)) {
                snapshot.setEntryOperationName(entryOperationName);
            }
        } else {
            snapshot.setEntryOperationId(entryOperationId);
        }

        if (firstSpan.getOperationId() == DictionaryUtil.nullValue()) {
            snapshot.setParentOperationName(firstSpan.getOperationName());
        } else {
            snapshot.setParentOperationId(firstSpan.getOperationId());
        }
        return snapshot;
    }

    /**
     * Continue the context from the given snapshot of parent thread.
     *
     * @param snapshot from {@link #capture()} in the parent thread. Ref to {@link AbstractTracerContext#continued(ContextSnapshot)}
     */
    @Override
    public void continued(ContextSnapshot snapshot) {
        TraceSegmentRef segmentRef = new TraceSegmentRef(snapshot);
        this.segment.ref(segmentRef);
        this.activeSpan().ref(segmentRef);
        this.segment.relatedGlobalTraces(snapshot.getDistributedTraceId());
    }

    /**
     * @return the first global trace id.
     */
    @Override
    public String getReadableGlobalTraceId() {
        return segment.getRelatedGlobalTraces().get(0).toString();
    }

    /**
     * Create an entry span
     *
     * 一般情况下，在 Agent 插件的前置处理逻辑中，会调用 createEntrySpan() 方法创建 EntrySpan，
     * 在 TracingContext 的实现中，会检测 EntrySpan 是否已创建，如果是，则不会创建新的 EntrySpan，
     * 只是重新调用一下其 start() 方法即可。
     *
     * @param operationName most likely a service name
     * @return span instance. Ref to {@link EntrySpan}
     */
    @Override
    public AbstractSpan createEntrySpan(final String operationName) {
        if (isLimitMechanismWorking()) {
            // 前面提到过，默认配置下，每个TraceSegment只能放300个Span,超过300就放 NoopSpan
            NoopSpan span = new NoopSpan();
            // 将Span记录到activeSpanStack这个栈中
            return push(span);
        }
        AbstractSpan entrySpan;
        // 读取栈顶Span，即当前Span
        final AbstractSpan parentSpan = peek();
        final int parentSpanId = parentSpan == null ? -1 : parentSpan.getSpanId();
        if (parentSpan != null && parentSpan.isEntry()) {
            // EndpointNameDictionary 的处理，其核心逻辑在前面的小节已经介绍过了。
            entrySpan = (AbstractTracingSpan)DictionaryManager.findEndpointSection()
                .findOnly(segment.getServiceId(), operationName)
                .doInCondition(new PossibleFound.FoundAndObtain() {
                    @Override public Object doProcess(int operationId) {
                        return parentSpan.setOperationId(operationId);
                    }
                }, new PossibleFound.NotFoundAndObtain() {
                    @Override public Object doProcess() {
                        return parentSpan.setOperationName(operationName);
                    }
                });
            // 重新调用 start()方法，前面提到过，start()方法会重置operationId(以及或operationName)之外的其他字段
            return entrySpan.start();
        } else {
            entrySpan = (AbstractTracingSpan)DictionaryManager.findEndpointSection()
                .findOnly(segment.getServiceId(), operationName)
                .doInCondition(new PossibleFound.FoundAndObtain() {
                    @Override public Object doProcess(int operationId) {
                        // 新建 EntrySpan对象，spanIdGenerator生成Span ID并递增
                        return new EntrySpan(spanIdGenerator++, parentSpanId, operationId);
                    }
                }, new PossibleFound.NotFoundAndObtain() {
                    @Override public Object doProcess() {
                        return new EntrySpan(spanIdGenerator++, parentSpanId, operationName);
                    }
                });
            // 调用 start()方法，第一次调用start()方法时会设置startTime
            entrySpan.start();
            // 将新建的Span添加到activeSpanStack栈的栈顶
            return push(entrySpan);
        }
    }

    /**
     * Create a local span
     *
     * @param operationName most likely a local method signature, or business name.
     * @return the span represents a local logic block. Ref to {@link LocalSpan}
     */
    @Override
    public AbstractSpan createLocalSpan(final String operationName) {
        if (isLimitMechanismWorking()) {
            NoopSpan span = new NoopSpan();
            return push(span);
        }
        AbstractSpan parentSpan = peek();
        final int parentSpanId = parentSpan == null ? -1 : parentSpan.getSpanId();
        /**
         * From v6.0.0-beta, local span doesn't do op name register.
         * All op name register is related to entry and exit spans only.
         */
        AbstractTracingSpan span = new LocalSpan(spanIdGenerator++, parentSpanId, operationName);
        span.start();
        return push(span);
    }

    /**
     * Create an exit span
     *
     * @param operationName most likely a service name of remote
     * @param remotePeer the network id(ip:port, hostname:port or ip1:port1,ip2,port, etc.)
     * @return the span represent an exit point of this segment.
     * @see ExitSpan
     */
    @Override
    public AbstractSpan createExitSpan(final String operationName, final String remotePeer) {
        AbstractSpan exitSpan;
        // 从activeSpanStack栈顶获取当前Span
        AbstractSpan parentSpan = peek();
        if (parentSpan != null && parentSpan.isExit()) {
            // 当前Span已经是ExitSpan，则不再新建ExitSpan，而是调用其start()方法
            exitSpan = parentSpan;
        } else {
            // 当前Span不是 ExitSpan，就新建一个ExitSpan
            final int parentSpanId = parentSpan == null ? -1 : parentSpan.getSpanId();
            exitSpan = (AbstractSpan)DictionaryManager.findNetworkAddressSection()
                .find(remotePeer).doInCondition(
                    new PossibleFound.FoundAndObtain() {
                        @Override
                        public Object doProcess(final int peerId) {
                            if (isLimitMechanismWorking()) {
                                return new NoopExitSpan(peerId);
                            }

                            return DictionaryManager.findEndpointSection()
                                .findOnly(segment.getServiceId(), operationName)
                                .doInCondition(
                                    new PossibleFound.FoundAndObtain() {
                                        @Override
                                        public Object doProcess(int operationId) {
                                            return new ExitSpan(spanIdGenerator++, parentSpanId, operationId, peerId);
                                        }
                                    }, new PossibleFound.NotFoundAndObtain() {
                                        @Override
                                        public Object doProcess() {
                                            return new ExitSpan(spanIdGenerator++, parentSpanId, operationName, peerId);
                                        }
                                    });
                        }
                    },
                    new PossibleFound.NotFoundAndObtain() {
                        @Override
                        public Object doProcess() {
                            if (isLimitMechanismWorking()) {
                                return new NoopExitSpan(remotePeer);
                            }

                            return DictionaryManager.findEndpointSection()
                                .findOnly(segment.getServiceId(), operationName)
                                .doInCondition(
                                    new PossibleFound.FoundAndObtain() {
                                        @Override
                                        public Object doProcess(int operationId) {
                                            return new ExitSpan(spanIdGenerator++, parentSpanId, operationId, remotePeer);
                                        }
                                    }, new PossibleFound.NotFoundAndObtain() {
                                        @Override
                                        public Object doProcess() {
                                            return new ExitSpan(spanIdGenerator++, parentSpanId, operationName, remotePeer);
                                        }
                                    });
                        }
                    });
            // 将新建的ExitSpan入栈
            push(exitSpan);
        }
        // 调用start()方法
        exitSpan.start();
        return exitSpan;
    }

    /**
     * @return the active span of current context, the top element of {@link #activeSpanStack}
     */
    @Override
    public AbstractSpan activeSpan() {
        AbstractSpan span = peek();
        if (span == null) {
            throw new IllegalStateException("No active span.");
        }
        return span;
    }

    /**
     * Stop the given span, if and only if this one is the top element of {@link #activeSpanStack}. Because the tracing
     * core must make sure the span must match in a stack module, like any program did.
     *
     * @param span to finish
     */
    @Override
    public boolean stopSpan(AbstractSpan span) {
        // 获取当前栈顶的Span对象
        AbstractSpan lastSpan = peek();
        // 只能关闭当前活跃Span对象，否则抛异常
        if (lastSpan == span) {
            if (lastSpan instanceof AbstractTracingSpan) {
                AbstractTracingSpan toFinishSpan = (AbstractTracingSpan)lastSpan;
                // 尝试关闭Span
                if (toFinishSpan.finish(segment)) {
                    //当Span完全关闭之后，会将其出栈(即从activeSpanStack中删除）
                    pop();
                }
            } else {
                // 针对NoopSpan类型Span的处理
                pop();
            }
        } else {
            throw new IllegalStateException("Stopping the unexpected span = " + span);
        }

        // TraceSegment中全部Span都关闭(且异步状态的Span也关闭了)，则当前
        // TraceSegment也会关闭，该关闭会触发TraceSegment上传操作，后面详述
        if (checkFinishConditions()) {
            finish();
        }

        return activeSpanStack.isEmpty();
    }

    @Override public AbstractTracerContext awaitFinishAsync() {
        if (!isRunningInAsyncMode) {
            synchronized (this) {
                if (!isRunningInAsyncMode) {
                    asyncFinishLock = new ReentrantLock();
                    asyncSpanCounter = new AtomicInteger(0);
                    isRunningInAsyncMode = true;
                }
            }
        }
        asyncSpanCounter.addAndGet(1);
        return this;
    }

    @Override public void asyncStop(AsyncSpan span) {
        asyncSpanCounter.addAndGet(-1);

        if (checkFinishConditions()) {
            finish();
        }
    }

    private boolean checkFinishConditions() {
        if (isRunningInAsyncMode) {
            asyncFinishLock.lock();
        }
        try {
            if (activeSpanStack.isEmpty() && (!isRunningInAsyncMode || asyncSpanCounter.get() == 0)) {
                return true;
            }
        } finally {
            if (isRunningInAsyncMode) {
                asyncFinishLock.unlock();
            }
        }
        return false;
    }

    /**
     * Finish this context, and notify all {@link TracingContextListener}s, managed by {@link
     * TracingContext.ListenerManager}
     */
    private void finish() {
        TraceSegment finishedSegment = segment.finish(isLimitMechanismWorking());
        /**
         * Recheck the segment if the segment contains only one span.
         * Because in the runtime, can't sure this segment is part of distributed trace.
         *
         * @see {@link #createSpan(String, long, boolean)}
         */
        if (!segment.hasRef() && segment.isSingleSpanSegment()) {
            if (!samplingService.trySampling()) {
                finishedSegment.setIgnore(true);
            }
        }
        TracingContext.ListenerManager.notifyFinish(finishedSegment);
    }

    /**
     * The <code>ListenerManager</code> represents an event notify for every registered listener, which are notified
     * when the <code>TracingContext</code> finished, and {@link #segment} is ready for further process.
     */
    public static class ListenerManager {
        private static List<TracingContextListener> LISTENERS = new LinkedList<TracingContextListener>();

        /**
         * Add the given {@link TracingContextListener} to {@link #LISTENERS} list.
         *
         * @param listener the new listener.
         */
        public static synchronized void add(TracingContextListener listener) {
            LISTENERS.add(listener);
        }

        /**
         * Notify the {@link TracingContext.ListenerManager} about the given {@link TraceSegment} have finished. And
         * trigger {@link TracingContext.ListenerManager} to notify all {@link #LISTENERS} 's {@link
         * TracingContextListener#afterFinished(TraceSegment)}
         *
         * @param finishedSegment
         */
        static void notifyFinish(TraceSegment finishedSegment) {
            for (TracingContextListener listener : LISTENERS) {
                listener.afterFinished(finishedSegment);
            }
        }

        /**
         * Clear the given {@link TracingContextListener}
         */
        public static synchronized void remove(TracingContextListener listener) {
            LISTENERS.remove(listener);
        }

    }

    /**
     * @return the top element of 'ActiveSpanStack', and remove it.
     */
    private AbstractSpan pop() {
        return activeSpanStack.removeLast();
    }

    /**
     * Add a new Span at the top of 'ActiveSpanStack'
     *
     * @param span
     */
    private AbstractSpan push(AbstractSpan span) {
        activeSpanStack.addLast(span);
        return span;
    }

    /**
     * @return the top element of 'ActiveSpanStack' only.
     */
    private AbstractSpan peek() {
        if (activeSpanStack.isEmpty()) {
            return null;
        }
        return activeSpanStack.getLast();
    }

    private AbstractSpan first() {
        return activeSpanStack.getFirst();
    }

    private boolean isLimitMechanismWorking() {
        if (spanIdGenerator >= Config.Agent.SPAN_LIMIT_PER_SEGMENT) {
            long currentTimeMillis = System.currentTimeMillis();
            if (currentTimeMillis - lastWarningTimestamp > 30 * 1000) {
                logger.warn(new RuntimeException("Shadow tracing context. Thread dump"), "More than {} spans required to create",
                    Config.Agent.SPAN_LIMIT_PER_SEGMENT);
                lastWarningTimestamp = currentTimeMillis;
            }
            return true;
        } else {
            return false;
        }
    }
}
