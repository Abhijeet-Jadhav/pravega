/**
 *
 *  Copyright (c) 2017 Dell Inc., or its subsidiaries.
 *
 */
package com.emc.pravega.framework.services;


import lombok.extern.slf4j.Slf4j;
import mesosphere.marathon.client.utils.MarathonException;
import mesosphere.marathon.client.model.v2.App;
import mesosphere.marathon.client.model.v2.Container;
import mesosphere.marathon.client.model.v2.Docker;
import mesosphere.marathon.client.model.v2.HealthCheck;
import mesosphere.marathon.client.model.v2.UpgradeStrategy;
import mesosphere.marathon.client.model.v2.Volume;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;


@Slf4j
public class PravegaSegmentStoreService extends MarathonBasedService {

    private URI zkUri;

    public PravegaSegmentStoreService(final String id, URI zkUri) {

        super(id);
        this.zkUri = zkUri;
    }

    @Override
    public void start(final boolean wait)  {
        log.info("Starting Pravega SegmentStore Service: {}", getID());
        try {
            marathonClient.createApp(createPravegaSegmentStoreApp());
            if (wait) {
                try {
                    waitUntilServiceRunning().get();
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error in wait until pravega segmentstore service is running {}", e);
                }
            }
        } catch (MarathonException e) {
            handleMarathonException(e);
        }
    }

    @Override
    public void clean() {
        //TODO: Clean up to be performed after stopping the Pravega SegmentStore Service.
    }

    @Override
    public void stop() {
        log.info("Stopping Pravega SegmentStore Service : {}", getID());
        try {
            marathonClient.deleteApp("segmentstore");
        } catch (MarathonException e) {
            handleMarathonException(e);
        }
    }

    private App createPravegaSegmentStoreApp() {

        App app = new App();
        app.setId(this.id);
        app.setBackoffSeconds(7200);
        app.setCpus(1.0);
        app.setMem(500.0);
        app.setInstances(1);
        //set constraints
        List<List<String>> listString = new ArrayList<>();
        List<String> list = new ArrayList<>();
        list.add("hostname");
        list.add("UNIQUE");
        listString.add(list);
        //docker container
        app.setContainer(new Container());
        app.getContainer().setType("DOCKER");
        app.getContainer().setDocker(new Docker());
        //set volume
        Collection<Volume> volumeCollection = new ArrayList<Volume>();
        Volume volume = new Volume();
        volume.setContainerPath("/tmp/logs");
        volume.setHostPath("/mnt/logs");
        volume.setMode("RW");
        volumeCollection.add(volume);
        app.getContainer().setVolumes(volumeCollection);
        //set the image and network
        //TODO: tag change to latest
        app.getContainer().getDocker().setImage("asdrepo.isus.emc.com:8103/nautilus/pravega-host:0.0-1111.2b562cb");
        app.getContainer().getDocker().setNetwork("HOST");
        app.getContainer().getDocker().setForcePullImage(true);
        //set port
        app.setPorts(Arrays.asList(12345));
        app.setRequirePorts(true);
        //healthchecks
        List<HealthCheck> healthCheckList = new ArrayList<HealthCheck>();
        HealthCheck healthCheck = new HealthCheck();
        healthCheck.setGracePeriodSeconds(900);
        healthCheck.setProtocol("TCP");
        healthCheck.setIgnoreHttp1xx(false);
        healthCheckList.add(healthCheck);
        healthCheck.setIntervalSeconds(60);
        healthCheck.setTimeoutSeconds(20);
        healthCheck.setMaxConsecutiveFailures(0);
        app.setHealthChecks(healthCheckList);
        //upgrade strategy
        UpgradeStrategy upgradeStrategy = new UpgradeStrategy();
        upgradeStrategy.setMaximumOverCapacity(0.0);
        upgradeStrategy.setMinimumHealthCapacity(0.0);
        app.setUpgradeStrategy(upgradeStrategy);
        //set env
        String zk = zkUri.getHost()+ ":2181";
        Map<String, String> map = new HashMap<>();
        map.put("ZK_URL", zk);
        map.put("pravegaservice_zkHostName", zkUri.getHost());
        map.put("dlog_hostname", zkUri.getHost());
        map.put("hdfs_fs_default_name", "namenode-0.hdfs.mesos:9001");
        app.setEnv(map);
        return app;
    }

}