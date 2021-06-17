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

import java.io.Serializable;
import java.util.List;
import org.apache.skywalking.apm.agent.core.base64.Base64;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.context.ids.DistributedTraceId;
import org.apache.skywalking.apm.agent.core.context.ids.ID;
import org.apache.skywalking.apm.agent.core.context.ids.PropagatedTraceId;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.core.dictionary.DictionaryUtil;
import org.apache.skywalking.apm.util.StringUtil;

/**
 * {@link ContextCarrier} is a data carrier of {@link TracingContext}. It holds the snapshot (current state) of {@link
 * TracingContext}.
 * <p>
 *
 *  跨进程传播 Context 上下文信息的核心流程大致为：远程调用的 Client 端会调用 inject(ContextCarrier) 方法，
 *  将当前 TracingContext 中记录的 Trace 上下文信息填充到传入的 ContextCarrier 对象。后续 Client 端的插件
 *  会将 ContextCarrier 对象序列化成字符串并将其作为附加信息添加到请求中，这样，ContextCarrier 字符串就会和请求
 *  一并到达 Server 端。Server 端的入口插件会检查请求中是否携带了 ContextCarrier 字符串，如果存在 ContextCarrier
 *  字符串，就会将其进行反序列化，然后调用 extract() 方法从 ContextCarrier 对象中取出 Context 上下文信息，
 *  填充到当前 TracingContext（以及 TraceSegmentRef) 中。
 *
 * Created by wusheng on 2017/2/17.
 */
public class ContextCarrier implements Serializable {
    /**
     * {@link TraceSegment#traceSegmentId}
     *
     * 它记录了 Client 中 TraceSegment ID；从 Server 角度看，记录的是父 TraceSegment 的 ID。
     */
    private ID traceSegmentId;

    /**
     * id of parent span. It is unique in parent trace segment.
     *
     * 从 Client 角度看，它记录了当前 ExitSpan 的 ID；从 Server 角度，看记录的是父 Span ID。
     */
    private int spanId = -1;

    /**
     * id of parent application instance, it's the id assigned by collector.
     *
     * 它记录的是 Client 服务实例的 ID。
     */
    private int parentServiceInstanceId = DictionaryUtil.nullValue();

    /**
     * id of first application instance in this distributed trace, it's the id assigned by collector.
     *
     * 它记录了当前 Trace 的入口服务实例 ID。
     */
    private int entryServiceInstanceId = DictionaryUtil.nullValue();

    /**
     * peer(ipv4s/ipv6/hostname + port) of the server, from client side.
     *
     * 它记录了 Server 端的地址（这里 peerName 和 peerId 共用了同一个字段）。
     * 以 "#" 开头时记录的是 peerName，否则记录的是 peerId，在 inject() 方法（或 extract() 方法）
     * 中填充（或读取）该字段时会专门判断处理开头的"#"字符。
     */
    private String peerHost;

    /**
     * Operation/Service name of the first one in this distributed trace. This name may be compressed to an integer.
     *
     * 它记录整个 Trace 的入口 EndpointName，该值在整个 Trace 中传播。
     */
    private String entryEndpointName;

    /**
     * Operation/Service name of the parent one in this distributed trace. This name may be compressed to an integer.
     *
     * 它记录了 Client  入口 EndpointName（或 EndpointId）。以 "#" 开头的时候，记录的是 EndpointName，否则记录的是 EndpointId。
     *
     */
    private String parentEndpointName;

    /**
     * {@link DistributedTraceId}, also known as TraceId
     *
     * 它记录了当前 Trace ID。
     */
    private DistributedTraceId primaryDistributedTraceId;

    public CarrierItem items() {
        CarrierItemHead head;
        if (Config.Agent.ACTIVE_V2_HEADER && Config.Agent.ACTIVE_V1_HEADER) {
            SW3CarrierItem  carrierItem = new SW3CarrierItem(this, null);
            SW6CarrierItem sw6CarrierItem = new SW6CarrierItem(this, carrierItem);
            head = new CarrierItemHead(sw6CarrierItem);
        } else if (Config.Agent.ACTIVE_V2_HEADER) {
            SW6CarrierItem sw6CarrierItem = new SW6CarrierItem(this, null);
            head = new CarrierItemHead(sw6CarrierItem);
        } else if (Config.Agent.ACTIVE_V1_HEADER) {
            SW3CarrierItem carrierItem = new SW3CarrierItem(this, null);
            head = new CarrierItemHead(carrierItem);
        } else {
            throw new IllegalArgumentException("At least active v1 or v2 header.");
        }
        return head;
    }

    /**
     * Serialize this {@link ContextCarrier} to a {@link String}, with '|' split.
     *
     * 有多个版本的结构，这里只关注最新的V2版本
     *
     * ContextCarrier 序列化之后得到的字符串分为 9 个部分，每个部分通过"-"（中划线）连接。在 deserialize() 方法中实现了
     * ContextCarrier 反序列化的逻辑，即将上述字符串进行切分并赋值到对应的字段中，具体逻辑为 serialize() 方法的逆操作，这里不再展开分析。
     *
     * 下面来看 TracingContext 对跨线程传播的支持，这里涉及 capture() 方法和 continued() 方法。跨线程传播时使用 ContextSnapshot
     * 为 Context 上下文创建快照，因为是在一个 JVM 中，所以 ContextSnapshot 不涉及序列化的问题，也无需携带服务实例 ID 以及 peerHost
     * 信息，其他核心字段与 ContextCarrier 类似，这里不再展开介绍。
     *
     * @return the serialization string.
     */
    String serialize(HeaderVersion version) {
        if (this.isValid(version)) {
            if (HeaderVersion.v1.equals(version)) {
                if (Config.Agent.ACTIVE_V1_HEADER) {
                    return StringUtil.join('|',
                        this.getTraceSegmentId().encode(),
                        this.getSpanId() + "",
                        this.getParentServiceInstanceId() + "",
                        this.getEntryServiceInstanceId() + "",
                        this.getPeerHost(),
                        this.getEntryEndpointName(),
                        this.getParentEndpointName(),
                        this.getPrimaryDistributedTraceId().encode());
                } else {
                    return "";
                }
            } else {
                if (Config.Agent.ACTIVE_V2_HEADER) {
                    return StringUtil.join('-',
                        "1",
                        Base64.encode(this.getPrimaryDistributedTraceId().encode()),
                        Base64.encode(this.getTraceSegmentId().encode()),
                        this.getSpanId() + "",
                        this.getParentServiceInstanceId() + "",
                        this.getEntryServiceInstanceId() + "",
                        Base64.encode(this.getPeerHost()),
                        Base64.encode(this.getEntryEndpointName()),
                        Base64.encode(this.getParentEndpointName()));
                } else {
                    return "";
                }
            }
        } else {
            return "";
        }
    }

    /**
     * Initialize fields with the given text.
     *
     * 有多个版本的结构，这里只关注最新的V2版本
     *
     * ContextCarrier 序列化之后得到的字符串分为 9 个部分，每个部分通过"-"（中划线）连接。在 deserialize() 方法中实现了
     * ContextCarrier 反序列化的逻辑，即将上述字符串进行切分并赋值到对应的字段中，具体逻辑为 serialize() 方法的逆操作，这里不再展开分析。
     *
     * 下面来看 TracingContext 对跨线程传播的支持，这里涉及 capture() 方法和 continued() 方法。跨线程传播时使用 ContextSnapshot
     * 为 Context 上下文创建快照，因为是在一个 JVM 中，所以 ContextSnapshot 不涉及序列化的问题，也无需携带服务实例 ID 以及 peerHost
     * 信息，其他核心字段与 ContextCarrier 类似，这里不再展开介绍。
     *
     * @param text carries {@link #traceSegmentId} and {@link #spanId}, with '|' split.
     */
    ContextCarrier deserialize(String text, HeaderVersion version) {
        if (text != null) {
            // if this carrier is initialized by v1 or v2, don't do deserialize again for performance.
            if (this.isValid(HeaderVersion.v1) || this.isValid(HeaderVersion.v2)) {
                return this;
            }
            if (HeaderVersion.v1.equals(version)) {
                String[] parts = text.split("\\|", 8);
                if (parts.length == 8) {
                    try {
                        this.traceSegmentId = new ID(parts[0]);
                        this.spanId = Integer.parseInt(parts[1]);
                        this.parentServiceInstanceId = Integer.parseInt(parts[2]);
                        this.entryServiceInstanceId = Integer.parseInt(parts[3]);
                        this.peerHost = parts[4];
                        this.entryEndpointName = parts[5];
                        this.parentEndpointName = parts[6];
                        this.primaryDistributedTraceId = new PropagatedTraceId(parts[7]);
                    } catch (NumberFormatException e) {

                    }
                }
            } else if (HeaderVersion.v2.equals(version)) {
                String[] parts = text.split("\\-", 9);
                if (parts.length == 9) {
                    try {
                        // parts[0] is sample flag, always trace if header exists.
                        this.primaryDistributedTraceId = new PropagatedTraceId(Base64.decode2UTFString(parts[1]));
                        this.traceSegmentId = new ID(Base64.decode2UTFString(parts[2]));
                        this.spanId = Integer.parseInt(parts[3]);
                        this.parentServiceInstanceId = Integer.parseInt(parts[4]);
                        this.entryServiceInstanceId = Integer.parseInt(parts[5]);
                        this.peerHost = Base64.decode2UTFString(parts[6]);
                        this.entryEndpointName = Base64.decode2UTFString(parts[7]);
                        this.parentEndpointName = Base64.decode2UTFString(parts[8]);
                    } catch (NumberFormatException e) {

                    }
                }
            } else {
                throw new IllegalArgumentException("Unimplemented header version." + version);
            }
        }
        return this;
    }

    public boolean isValid() {
        return isValid(HeaderVersion.v2) || isValid(HeaderVersion.v1);
    }

    /**
     * Make sure this {@link ContextCarrier} has been initialized.
     *
     * @return true for unbroken {@link ContextCarrier} or no-initialized. Otherwise, false;
     */
    boolean isValid(HeaderVersion version) {
        if (HeaderVersion.v1.equals(version)) {
            return traceSegmentId != null
                && traceSegmentId.isValid()
                && getSpanId() > -1
                && parentServiceInstanceId != DictionaryUtil.nullValue()
                && entryServiceInstanceId != DictionaryUtil.nullValue()
                && !StringUtil.isEmpty(peerHost)
                && !StringUtil.isEmpty(entryEndpointName)
                && !StringUtil.isEmpty(parentEndpointName)
                && primaryDistributedTraceId != null;
        } else if (HeaderVersion.v2.equals(version)) {
            return traceSegmentId != null
                && traceSegmentId.isValid()
                && getSpanId() > -1
                && parentServiceInstanceId != DictionaryUtil.nullValue()
                && entryServiceInstanceId != DictionaryUtil.nullValue()
                && !StringUtil.isEmpty(peerHost)
                && primaryDistributedTraceId != null;
        } else {
            throw new IllegalArgumentException("Unimplemented header version." + version);
        }
    }

    public String getEntryEndpointName() {
        return entryEndpointName;
    }

    void setEntryEndpointName(String entryEndpointName) {
        this.entryEndpointName = '#' + entryEndpointName;
    }

    void setEntryEndpointId(int entryOperationId) {
        this.entryEndpointName = entryOperationId + "";
    }

    void setParentEndpointName(String parentEndpointName) {
        this.parentEndpointName = '#' + parentEndpointName;
    }

    void setParentEndpointId(int parentOperationId) {
        this.parentEndpointName = parentOperationId + "";
    }

    public ID getTraceSegmentId() {
        return traceSegmentId;
    }

    public int getSpanId() {
        return spanId;
    }

    void setTraceSegmentId(ID traceSegmentId) {
        this.traceSegmentId = traceSegmentId;
    }

    void setSpanId(int spanId) {
        this.spanId = spanId;
    }

    public int getParentServiceInstanceId() {
        return parentServiceInstanceId;
    }

    void setParentServiceInstanceId(int parentServiceInstanceId) {
        this.parentServiceInstanceId = parentServiceInstanceId;
    }

    public String getPeerHost() {
        return peerHost;
    }

    void setPeerHost(String peerHost) {
        this.peerHost = '#' + peerHost;
    }

    void setPeerId(int peerId) {
        this.peerHost = peerId + "";
    }

    public DistributedTraceId getDistributedTraceId() {
        return primaryDistributedTraceId;
    }

    public void setDistributedTraceIds(List<DistributedTraceId> distributedTraceIds) {
        this.primaryDistributedTraceId = distributedTraceIds.get(0);
    }

    private DistributedTraceId getPrimaryDistributedTraceId() {
        return primaryDistributedTraceId;
    }

    public String getParentEndpointName() {
        return parentEndpointName;
    }

    public int getEntryServiceInstanceId() {
        return entryServiceInstanceId;
    }

    public void setEntryServiceInstanceId(int entryServiceInstanceId) {
        this.entryServiceInstanceId = entryServiceInstanceId;
    }

    public enum HeaderVersion {
        v1, v2
    }
}
