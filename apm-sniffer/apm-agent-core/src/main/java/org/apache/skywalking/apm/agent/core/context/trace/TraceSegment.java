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

import java.util.LinkedList;
import java.util.List;
import org.apache.skywalking.apm.agent.core.conf.RemoteDownstreamConfig;
import org.apache.skywalking.apm.agent.core.context.ids.DistributedTraceId;
import org.apache.skywalking.apm.agent.core.context.ids.DistributedTraceIds;
import org.apache.skywalking.apm.agent.core.context.ids.GlobalIdGenerator;
import org.apache.skywalking.apm.agent.core.context.ids.ID;
import org.apache.skywalking.apm.agent.core.context.ids.NewDistributedTraceId;
import org.apache.skywalking.apm.network.language.agent.*;
import org.apache.skywalking.apm.network.language.agent.v2.SegmentObject;

/**
 * {@link TraceSegment} is a segment or fragment of the distributed trace. See https://github.com/opentracing/specification/blob/master/specification.md#the-opentracing-data-model
 * A {@link TraceSegment} means the segment, which exists in current {@link Thread}. And the distributed trace is formed
 * by multi {@link TraceSegment}s, because the distributed trace crosses multi-processes, multi-threads. <p>
 *
 * TraceSegment 是一个介于 Trace 与 Span 之间的概念，它是一条 Trace 的一段，可以包含多个 Span。在微服务架构中，
 * 一个请求基本都会涉及跨进程（以及跨线程）的操作，例如， RPC 调用、通过 MQ 异步执行、HTTP 请求远端资源等，处理一个
 * 请求就需要涉及到多个服务的多个线程。TraceSegment 记录了一个请求在一个线程中的执行流程（即 Trace 信息）。
 * 将该请求关联的 TraceSegment 串联起来，就能得到该请求对应的完整 Trace。
 *
 * @author wusheng
 */
public class TraceSegment {
    /**
     * The id of this trace segment. Every segment has its unique-global-id.
     *
     * TraceSegment 的全局唯一标识，是由前面介绍的 GlobalIdGenerator 生成的。
     */
    private ID traceSegmentId;

    /**
     * The refs of parent trace segments, except the primary one. For most RPC call, {@link #refs} contains only one
     * element, but if this segment is a start span of batch process, the segment faces multi parents, at this moment,
     * we use this {@link #refs} to link them.
     *
     * 它指向父 TraceSegment。在我们常见的 RPC 调用、HTTP 请求等跨进程调用中，一个 TraceSegment 最多只有一个父
     * TraceSegment，但是在一个 Consumer 批量消费 MQ 消息时，同一批内的消息可能来自不同的 Producer，这就会导致
     * Consumer 线程对应的 TraceSegment 有多个父 TraceSegment 了，当然，该 Consumer TraceSegment 也就属于多个 Trace 了。
     *
     * This field will not be serialized. Keeping this field is only for quick accessing.
     */
    private List<TraceSegmentRef> refs;

    /**
     * The spans belong to this trace segment. They all have finished. All active spans are hold and controlled by
     * "skywalking-api" module.
     *
     * 当前 TraceSegment 包含的所有 Span。
     *
     */
    private List<AbstractTracingSpan> spans;

    /**
     * The <code>relatedGlobalTraces</code> represent a set of all related trace. Most time it contains only one
     * element, because only one parent {@link TraceSegment} exists, but, in batch scenario, the num becomes greater
     * than 1, also meaning multi-parents {@link TraceSegment}. <p> The difference between
     * <code>relatedGlobalTraces</code> and {@link #refs} is: {@link #refs} targets this {@link TraceSegment}'s direct
     * parent, <p> and <p> <code>relatedGlobalTraces</code> targets this {@link TraceSegment}'s related call chain, a
     * call chain contains multi {@link TraceSegment}s, only using {@link #refs} is not enough for analysis and ui.
     *
     * 记录当前 TraceSegment 所属 Trace 的 Trace ID。
     *
     */
    private DistributedTraceIds relatedGlobalTraces;

    /**
     * ignore 字段表示当前 TraceSegment 是否被忽略。主要是为了忽略一些问题 TraceSegment（主要是对只包含一个 Span 的 Trace 进行采样收集）。
     */
    private boolean ignore = false;

    /**
     * 这是一个容错设计，例如业务代码出现了死循环 Bug，可能会向相应的 TraceSegment 中不断追加 Span，
     * 为了防止对应用内存以及后端存储造成不必要的压力，每个 TraceSegment 中 Span 的个数是有上限的（默认值为 300），
     * 超过上限之后，就不再添加 Span了。
     */
    private boolean isSizeLimited = false;

    /**
     * Create a default/empty trace segment, with current time as start time, and generate a new segment id.
     */
    public TraceSegment() {
        this.traceSegmentId = GlobalIdGenerator.generate();
        this.spans = new LinkedList<AbstractTracingSpan>();
        this.relatedGlobalTraces = new DistributedTraceIds();
        this.relatedGlobalTraces.append(new NewDistributedTraceId());
    }

    /**
     * Establish the link between this segment and its parents.
     *
     * @param refSegment {@link TraceSegmentRef}
     */
    public void ref(TraceSegmentRef refSegment) {
        if (refs == null) {
            refs = new LinkedList<TraceSegmentRef>();
        }
        if (!refs.contains(refSegment)) {
            refs.add(refSegment);
        }
    }

    /**
     * Establish the line between this segment and all relative global trace ids.
     */
    public void relatedGlobalTraces(DistributedTraceId distributedTraceId) {
        relatedGlobalTraces.append(distributedTraceId);
    }

    /**
     * After {@link AbstractSpan} is finished, as be controller by "skywalking-api" module, notify the {@link
     * TraceSegment} to archive it.
     *
     * @param finishedSpan
     */
    public void archive(AbstractTracingSpan finishedSpan) {
        spans.add(finishedSpan);
    }

    /**
     * Finish this {@link TraceSegment}. <p> return this, for chaining
     */
    public TraceSegment finish(boolean isSizeLimited) {
        this.isSizeLimited = isSizeLimited;
        return this;
    }

    public ID getTraceSegmentId() {
        return traceSegmentId;
    }

    public int getServiceId() {
        return RemoteDownstreamConfig.Agent.SERVICE_ID;
    }

    public boolean hasRef() {
        return !(refs == null || refs.size() == 0);
    }

    public List<TraceSegmentRef> getRefs() {
        return refs;
    }

    public List<DistributedTraceId> getRelatedGlobalTraces() {
        return relatedGlobalTraces.getRelatedGlobalTraces();
    }

    public boolean isSingleSpanSegment() {
        return this.spans != null && this.spans.size() == 1;
    }

    public boolean isIgnore() {
        return ignore;
    }

    public void setIgnore(boolean ignore) {
        this.ignore = ignore;
    }

    /**
     * This is a high CPU cost method, only called when sending to collector or test cases.
     *
     * @return the segment as GRPC service parameter
     */
    public UpstreamSegment transform() {
        UpstreamSegment.Builder upstreamBuilder = UpstreamSegment.newBuilder();
        for (DistributedTraceId distributedTraceId : getRelatedGlobalTraces()) {
            upstreamBuilder = upstreamBuilder.addGlobalTraceIds(distributedTraceId.toUniqueId());
        }
        SegmentObject.Builder traceSegmentBuilder = SegmentObject.newBuilder();
        /**
         * Trace Segment
         */
        traceSegmentBuilder.setTraceSegmentId(this.traceSegmentId.transform());
        // Don't serialize TraceSegmentReference

        // SpanObject
        for (AbstractTracingSpan span : this.spans) {
            traceSegmentBuilder.addSpans(span.transform());
        }
        traceSegmentBuilder.setServiceId(RemoteDownstreamConfig.Agent.SERVICE_ID);
        traceSegmentBuilder.setServiceInstanceId(RemoteDownstreamConfig.Agent.SERVICE_INSTANCE_ID);
        traceSegmentBuilder.setIsSizeLimited(this.isSizeLimited);

        upstreamBuilder.setSegment(traceSegmentBuilder.build().toByteString());
        return upstreamBuilder.build();
    }

    @Override
    public String toString() {
        return "TraceSegment{" +
            "traceSegmentId='" + traceSegmentId + '\'' +
            ", refs=" + refs +
            ", spans=" + spans +
            ", relatedGlobalTraces=" + relatedGlobalTraces +
            '}';
    }

    public int getApplicationInstanceId() {
        return RemoteDownstreamConfig.Agent.SERVICE_INSTANCE_ID;
    }
}
