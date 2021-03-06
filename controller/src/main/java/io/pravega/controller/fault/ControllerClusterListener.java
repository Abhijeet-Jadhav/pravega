/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.controller.fault;

import io.pravega.common.LoggerHelpers;
import io.pravega.common.cluster.Cluster;
import io.pravega.common.cluster.Host;
import io.pravega.controller.server.eventProcessor.ControllerEventProcessors;
import io.pravega.controller.task.Stream.TxnSweeper;
import io.pravega.controller.task.TaskSweeper;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.AbstractIdleService;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Controller cluster listener service. This service when started, starts listening to
 * the controller cluster notifications. Whenever a controller instance leaves the
 * cluster, it does the following things.
 * 1. Try to complete the orphaned tasks running on the failed controller instance,
 * 2. Try to notify the commit and abort reader group about the loss of readers from failed controller instance, and
 * 3. Try to sweep transactions being tracked by the failed controller instance.
 *
 */
@Slf4j
public class ControllerClusterListener extends AbstractIdleService {

    private final String objectId;
    private final Host host;
    private final Cluster cluster;
    private final ExecutorService executor;
    private final Optional<ControllerEventProcessors> eventProcessorsOpt;
    private final TaskSweeper taskSweeper;
    private final Optional<TxnSweeper> txnSweeperOpt;

    public ControllerClusterListener(final Host host, final Cluster cluster,
                                     final Optional<ControllerEventProcessors> eventProcessorsOpt,
                                     final TaskSweeper taskSweeper,
                                     final Optional<TxnSweeper> txnSweeperOpt,
                                     final ExecutorService executor) {
        Preconditions.checkNotNull(host, "host");
        Preconditions.checkNotNull(cluster, "cluster");
        Preconditions.checkNotNull(executor, "executor");
        Preconditions.checkNotNull(eventProcessorsOpt, "eventProcessorsOpt");
        Preconditions.checkNotNull(taskSweeper, "taskSweeper");
        Preconditions.checkNotNull(txnSweeperOpt, "txnSweeperOpt");

        this.objectId = "ControllerClusterListener";
        this.host = host;
        this.cluster = cluster;
        this.executor = executor;
        this.eventProcessorsOpt = eventProcessorsOpt;
        this.taskSweeper = taskSweeper;
        this.txnSweeperOpt = txnSweeperOpt;
    }

    @Override
    protected void startUp() throws InterruptedException, Exception {
        long traceId = LoggerHelpers.traceEnter(log, objectId, "startUp");
        try {
            log.info("Registering host {} with controller cluster", host);
            cluster.registerHost(host);

            if (eventProcessorsOpt.isPresent()) {
                // Await initialization of eventProcesorsOpt
                log.info("Awaiting controller event processors' start");
                eventProcessorsOpt.get().awaitRunning();
            }

            if (txnSweeperOpt.isPresent()) {
                // Await initialization of transactionTasksOpt.
                log.info("Awaiting StreamTransactionMetadataTasks to get ready");
                txnSweeperOpt.get().awaitInitialization();
            }

            // Register cluster listener.
            log.info("Adding controller cluster listener");
            cluster.addListener((type, host) -> {
                switch (type) {
                    case HOST_ADDED:
                        // We need to do nothing when a new controller instance joins the cluster.
                        log.info("Received controller cluster event: {} for host: {}", type, host);
                        break;
                    case HOST_REMOVED:
                        log.info("Received controller cluster event: {} for host: {}", type, host);
                        taskSweeper.sweepOrphanedTasks(host.getHostId());
                        if (eventProcessorsOpt.isPresent()) {
                            eventProcessorsOpt.get().notifyProcessFailure(host.getHostId());
                        }
                        if (txnSweeperOpt.isPresent()) {
                            txnSweeperOpt.get().sweepOrphanedTxns(host.getHostId());
                        }
                        break;
                    case ERROR:
                        // This event should be due to ZK connection errors. If it is session lost error then
                        // ControllerServiceMain would handle it. Otherwise it is a fleeting error that can go
                        // away with retries, and hence we ignore it.
                        log.info("Received error event when monitoring the controller host cluster, ignoring...");
                        break;
                }
            }, executor);

            log.info("Sweeping orphaned tasks at startup");
            Supplier<Set<String>> processes = () -> {
                try {
                    return cluster.getClusterMembers()
                            .stream()
                            .map(Host::getHostId)
                            .collect(Collectors.toSet());
                } catch (Exception e) {
                    log.error("error fetching cluster members {}", e);
                    throw new CompletionException(e);
                }
            };

            taskSweeper.sweepOrphanedTasks(processes);

            if (eventProcessorsOpt.isPresent()) {
                // Sweep orphaned tasks or readers at startup.
                log.info("Sweeping orphaned readers at startup");
                eventProcessorsOpt.get().handleOrphanedReaders(processes);
            }

            if (txnSweeperOpt.isPresent()) {
                // Sweep orphaned transactions as startup.
                log.info("Sweeping orphaned transactions");
                txnSweeperOpt.get().sweepFailedHosts(processes);
            }

            log.info("Controller cluster listener startUp complete");
        } finally {
            LoggerHelpers.traceLeave(log, objectId, "startUp", traceId);
        }
    }

    @Override
    protected void shutDown() throws Exception {
        long traceId = LoggerHelpers.traceEnter(log, objectId, "shutDown");
        try {
            log.info("Deregistering host {} from controller cluster", host);
            cluster.deregisterHost(host);
            log.info("Controller cluster listener shutDown complete");
        } finally {
            LoggerHelpers.traceLeave(log, objectId, "shutDown", traceId);
        }
    }
}
