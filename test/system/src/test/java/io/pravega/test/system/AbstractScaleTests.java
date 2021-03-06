/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.test.system;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.pravega.client.ClientFactory;
import io.pravega.client.netty.impl.ConnectionFactory;
import io.pravega.client.netty.impl.ConnectionFactoryImpl;
import io.pravega.client.stream.impl.ClientFactoryImpl;
import io.pravega.client.stream.impl.ControllerImpl;
import io.pravega.common.concurrent.FutureHelpers;
import io.pravega.test.system.framework.services.PravegaControllerService;
import io.pravega.test.system.framework.services.Service;
import java.net.URI;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract scale tests. This contains all the common methods used for auto scale related tests.
 */
@Slf4j
abstract class AbstractScaleTests {
    protected final static String SCOPE = "testAutoScale" + new Random().nextInt(Integer.MAX_VALUE);
    @Getter(lazy = true, onMethod = @__({ @SuppressFBWarnings }))
    private final URI controllerURI = createControllerURI();
    @Getter(lazy = true, onMethod = @__({ @SuppressFBWarnings }))
    private final ConnectionFactory connectionFactory = new ConnectionFactoryImpl(false);
    @Getter(lazy = true, onMethod = @__({ @SuppressFBWarnings }))
    private final ClientFactory clientFactory = new ClientFactoryImpl(SCOPE, new ControllerImpl(getControllerURI()));
    @Getter(lazy = true, onMethod = @__({ @SuppressFBWarnings }))
    private final ControllerImpl controller = new ControllerImpl(getControllerURI());

    private URI createControllerURI() {
        Service conService = new PravegaControllerService("controller", null);
        List<URI> ctlURIs = conService.getServiceDetails();
        return ctlURIs.get(0);
    }

    void recordResult(final CompletableFuture<Void> scaleTestResult, final String testName) {
        FutureHelpers.getAndHandleExceptions(scaleTestResult.handle((r, e) -> {
            if (e != null) {
                log.error("test {} failed with exception {}", testName, e);
            } else {
                log.debug("test {} succeed", testName);
            }
            return null;
        }), RuntimeException::new);
    }

    // Exception to indicate that the scaling operation did not happen.
    // We need to retry operation to check scaling on this exception.
    class ScaleOperationNotDoneException extends RuntimeException {
    }

}
