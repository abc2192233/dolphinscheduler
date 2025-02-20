/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.server.master.dispatch.host;

import org.apache.dolphinscheduler.common.Constants;
import org.apache.dolphinscheduler.common.utils.CollectionUtils;
import org.apache.dolphinscheduler.common.utils.HeartBeat;
import org.apache.dolphinscheduler.remote.utils.Host;
import org.apache.dolphinscheduler.remote.utils.NamedThreadFactory;
import org.apache.dolphinscheduler.server.master.dispatch.context.ExecutionContext;
import org.apache.dolphinscheduler.server.master.dispatch.host.assign.HostWeight;
import org.apache.dolphinscheduler.server.master.dispatch.host.assign.HostWorker;
import org.apache.dolphinscheduler.server.master.dispatch.host.assign.LowerWeightRoundRobin;
import org.apache.dolphinscheduler.spi.utils.StringUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * lower weight host manager
 */
public class LowerWeightHostManager extends CommonHostManager {

    private final Logger logger = LoggerFactory.getLogger(LowerWeightHostManager.class);

    /**
     * selector
     */
    private LowerWeightRoundRobin selector;

    /**
     * worker host weights
     */
    private ConcurrentHashMap<String, Set<HostWeight>> workerHostWeightsMap;

    /**
     * worker group host lock
     */
    private Lock lock;

    /**
     * executor service
     */
    private ScheduledExecutorService executorService;

    @PostConstruct
    public void init() {
        this.selector = new LowerWeightRoundRobin();
        this.workerHostWeightsMap = new ConcurrentHashMap<>();
        this.lock = new ReentrantLock();
        this.executorService = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("LowerWeightHostManagerExecutor"));
        this.executorService.scheduleWithFixedDelay(new RefreshResourceTask(), 0, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void close() {
        this.executorService.shutdownNow();
    }

    /**
     * select host
     *
     * @param context context
     * @return host
     */
    @Override
    public Host select(ExecutionContext context) {
        Set<HostWeight> workerHostWeights = getWorkerHostWeights(context.getWorkerGroup());
        if (CollectionUtils.isNotEmpty(workerHostWeights)) {
            return selector.select(workerHostWeights).getHost();
        }
        return new Host();
    }

    @Override
    public HostWorker select(Collection<HostWorker> nodes) {
        throw new UnsupportedOperationException("not support");
    }

    private void syncWorkerHostWeight(Map<String, Set<HostWeight>> workerHostWeights) {
        lock.lock();
        try {
            workerHostWeightsMap.clear();
            workerHostWeightsMap.putAll(workerHostWeights);
        } finally {
            lock.unlock();
        }
    }

    private Set<HostWeight> getWorkerHostWeights(String workerGroup) {
        lock.lock();
        try {
            return workerHostWeightsMap.get(workerGroup);
        } finally {
            lock.unlock();
        }
    }

    class RefreshResourceTask implements Runnable {

        @Override
        public void run() {
            try {
                Map<String, Set<HostWeight>> workerHostWeights = new HashMap<>();
                Map<String, Set<String>> workerGroupNodes = serverNodeManager.getWorkerGroupNodes();
                for (Map.Entry<String, Set<String>> entry : workerGroupNodes.entrySet()) {
                    String workerGroup = entry.getKey();
                    Set<String> nodes = entry.getValue();
                    Set<HostWeight> hostWeights = new HashSet<>(nodes.size());
                    for (String node : nodes) {
                        String heartbeat = serverNodeManager.getWorkerNodeInfo(node);
                        Optional<HostWeight> hostWeightOpt = getHostWeight(node, workerGroup, heartbeat);
                        if (hostWeightOpt.isPresent()) {
                            hostWeights.add(hostWeightOpt.get());
                        }
                    }
                    if (!hostWeights.isEmpty()) {
                        workerHostWeights.put(workerGroup, hostWeights);
                    }
                }
                syncWorkerHostWeight(workerHostWeights);
            } catch (Throwable ex) {
                logger.error("RefreshResourceTask error", ex);
            }
        }

        public Optional<HostWeight> getHostWeight(String addr, String workerGroup, String heartBeatInfo) {
            if (StringUtils.isEmpty(heartBeatInfo)) {
                logger.warn("worker {} in work group {} have not received the heartbeat", addr, workerGroup);
                return Optional.empty();
            }
            HeartBeat heartBeat = HeartBeat.decodeHeartBeat(heartBeatInfo);
            if (heartBeat == null) {
                return Optional.empty();
            }
            if (Constants.ABNORMAL_NODE_STATUS == heartBeat.getServerStatus()) {
                logger.warn("worker {} current cpu load average {} is too high or available memory {}G is too low",
                        addr, heartBeat.getLoadAverage(), heartBeat.getAvailablePhysicalMemorySize());
                return Optional.empty();
            }
            if (Constants.BUSY_NODE_STATUE == heartBeat.getServerStatus()) {
                logger.warn("worker {} is busy, current waiting task count {} is large than worker thread count {}",
                        addr, heartBeat.getWorkerWaitingTaskCount(), heartBeat.getWorkerExecThreadCount());
                return Optional.empty();
            }
            return Optional.of(
                    new HostWeight(HostWorker.of(addr, heartBeat.getWorkerHostWeight(), workerGroup),
                            heartBeat.getCpuUsage(), heartBeat.getMemoryUsage(), heartBeat.getLoadAverage(),
                            heartBeat.getStartupTime()));
        }
    }

}
