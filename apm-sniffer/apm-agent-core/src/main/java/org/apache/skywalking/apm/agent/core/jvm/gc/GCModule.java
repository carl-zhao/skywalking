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

package org.apache.skywalking.apm.agent.core.jvm.gc;

import java.lang.management.GarbageCollectorMXBean;
import java.util.LinkedList;
import java.util.List;
import org.apache.skywalking.apm.network.language.agent.*;

/**
 * @author wusheng
 */
public abstract class GCModule implements GCMetricAccessor {
    private List<GarbageCollectorMXBean> beans;

    private long lastOGCCount = 0;
    private long lastYGCCount = 0;
    private long lastOGCCollectionTime = 0;
    private long lastYGCCollectionTime = 0;

    public GCModule(List<GarbageCollectorMXBean> beans) {
        this.beans = beans;
    }

    @Override
    public List<GC> getGCList() {
        List<GC> gcList = new LinkedList<GC>();
        for (GarbageCollectorMXBean bean : beans) {
            String name = bean.getName();
            GCPhrase phrase;
            long gcCount = 0;
            long gcTime = 0;
            // 下面根据 MXBean的名称判断具体的GC信息
            if (name.equals(getNewGCName())) {
                // Young GC的信息
                phrase = GCPhrase.NEW;
                // 计算GC次数，从MXBean直接拿到的是GC总次数
                long collectionCount = bean.getCollectionCount();
                gcCount = collectionCount - lastYGCCount;
                // 更新 lastYGCCount
                lastYGCCount = collectionCount;
                // 计算GC时间，从 MXBean直接拿到的是GC总时间
                long time = bean.getCollectionTime();
                gcTime = time - lastYGCCollectionTime;
                // 更新lastYGCCollectionTime
                lastYGCCollectionTime = time;
            } else if (name.equals(getOldGCName())) {
                // Old GC的信息
                phrase = GCPhrase.OLD;
                // Old GC的计算方式与Young GC的计算方式相同，不再重复
                long collectionCount = bean.getCollectionCount();
                gcCount = collectionCount - lastOGCCount;
                lastOGCCount = collectionCount;

                long time = bean.getCollectionTime();
                gcTime = time - lastOGCCollectionTime;
                lastOGCCollectionTime = time;
            } else {
                continue;
            }

            gcList.add(
                GC.newBuilder().setPhrase(phrase)
                    .setCount(gcCount)
                    .setTime(gcTime)
                    .build()
            );
        }

        return gcList;
    }

    protected abstract String getOldGCName();

    protected abstract String getNewGCName();
}
