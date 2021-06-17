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


package org.apache.skywalking.apm.agent.core.plugin.loader;

import org.apache.skywalking.apm.agent.core.boot.AgentPackageNotFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The <code>InterceptorInstanceLoader</code> is a classes finder and container.
 * <p>
 * This is a very important class in sky-walking's auto-instrumentation mechanism. If you want to fully understand why
 * need this, and how it works, you need have knowledge about Classloader appointment mechanism.
 * <p>
 *
 * 前面加载 Interceptpr 的 ClassLoader 并没有使用 AgentClassLoader 的默认实例或是Application ClassLoader，
 * 而是通过 InterceptorInstanceLoader 完成加载的。 在 InterceptorInstanceLoader 里面会维护一个 ClassLoader Cache，
 * 以及一个 Instance Cache
 * Created by wusheng on 16/8/2.
 */
public class InterceptorInstanceLoader {

    // 记录了 instanceKey与实例之间的映射关系，保证单例
    private static ConcurrentHashMap<String, Object> INSTANCE_CACHE = new ConcurrentHashMap<String, Object>();

    private static ReentrantLock INSTANCE_LOAD_LOCK = new ReentrantLock();

    // 记录了 targetClassLoader以及其子 AgentClassLoader的对应关系
    private static Map<ClassLoader, ClassLoader> EXTEND_PLUGIN_CLASSLOADERS = new HashMap<ClassLoader, ClassLoader>();

    /**
     * Load an instance of interceptor, and keep it singleton.
     * Create {@link AgentClassLoader} for each targetClassLoader, as an extend classloader.
     * It can load interceptor classes from plugins, activations folders.
     *
     * @param className         the interceptor class, which is expected to be found
     * @param targetClassLoader the class loader for current application context
     * @param <T>               expected type
     * @return the type reference.
     * @throws IllegalAccessException
     * @throws InstantiationException
     * @throws ClassNotFoundException
     * @throws AgentPackageNotFoundException
     */
    public static <T> T load(String className, ClassLoader targetClassLoader)
            throws IllegalAccessException, InstantiationException, ClassNotFoundException, AgentPackageNotFoundException {
        if (targetClassLoader == null) {
            targetClassLoader = InterceptorInstanceLoader.class.getClassLoader();
        }
        // 通过该 instanceKey保证该 Interceptor在一个 ClassLoader中只创建一次
        String instanceKey = className + "_OF_" + targetClassLoader.getClass().getName() + "@" + Integer.toHexString(targetClassLoader.hashCode());
        Object inst = INSTANCE_CACHE.get(instanceKey);
        if (inst == null) {
            INSTANCE_LOAD_LOCK.lock();
            try {
                // 查找targetClassLoader对应的子AgentClassLoader
                ClassLoader pluginLoader = EXTEND_PLUGIN_CLASSLOADERS.get(targetClassLoader);
                if (pluginLoader == null) {
                    // 为 targetClassLoader创建子AgentClassLoader
                    pluginLoader = new AgentClassLoader(targetClassLoader);
                    EXTEND_PLUGIN_CLASSLOADERS.put(targetClassLoader, pluginLoader);
                }
                // 通过子AgentClassLoader加载Interceptor类
                inst = Class.forName(className, true, pluginLoader).newInstance();
            } finally {
                INSTANCE_LOAD_LOCK.unlock();
            }
            if (inst != null) {
                // 记录Interceptor对象
                INSTANCE_CACHE.put(instanceKey, inst);
            }
        }

        return (T) inst;
    }
}
