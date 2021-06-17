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


package org.apache.skywalking.apm.agent.core.plugin.match;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * All implementations can't direct match the class like {@link NameMatch} did.
 *
 * @author wusheng
 */
public interface IndirectMatch extends ClassMatch {

    /**
     * Junction是Byte Buddy中的类，可以通过and、or等操作串联多个 ElementMatcher 进行匹配
     * @return
     */
    ElementMatcher.Junction buildJunction();

    /**
     * 用于检测传入的类型是否匹配该Match
     * @param typeDescription
     * @return
     */
    boolean isMatch(TypeDescription typeDescription);
}
