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


package org.apache.skywalking.apm.agent.core.context.ids;

/**
 * The <code>PropagatedTraceId</code> represents a {@link DistributedTraceId}, which is propagated from the peer.
 *
 * PropagatedTraceId 负责处理 Trace 传播过程中的 TraceId。PropagatedTraceId 的构造方法接收一个 String
 * 类型参数（也就是在跨进程传播时序列化后的 Trace ID），解析之后得到 ID 对象。
 *
 * @author wusheng
 */
public class PropagatedTraceId extends DistributedTraceId {
    public PropagatedTraceId(String id) {
        super(id);
    }
}
