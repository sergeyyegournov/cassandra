/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.hints;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.util.concurrent.RateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.concurrent.JMXEnabledThreadPoolExecutor;
import org.apache.cassandra.concurrent.NamedThreadFactory;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.io.FSReadError;
import org.apache.cassandra.service.StorageService;

/**
 * A multi-threaded (by default) executor for dispatching hints.
 *
 * Most of dispatch is triggered by {@link HintsDispatchTrigger} running every ~10 seconds.
 */
final class HintsDispatchExecutor
{
    private static final Logger logger = LoggerFactory.getLogger(HintsDispatchExecutor.class);

    private final File hintsDirectory;
    private final ExecutorService executor;
    private final AtomicBoolean isPaused;
    private final Map<UUID, Future> scheduledDispatches;

    HintsDispatchExecutor(File hintsDirectory, int maxThreads, AtomicBoolean isPaused)
    {
        this.hintsDirectory = hintsDirectory;
        this.isPaused = isPaused;

        scheduledDispatches = new ConcurrentHashMap<>();
        executor = new JMXEnabledThreadPoolExecutor(1,
                                                    maxThreads,
                                                    1,
                                                    TimeUnit.MINUTES,
                                                    new LinkedBlockingQueue<>(),
                                                    new NamedThreadFactory("HintsDispatcher", Thread.MIN_PRIORITY),
                                                    "internal");
    }

    /*
     * It's safe to terminate dispatch in process and to deschedule dispatch.
     */
    void shutdownBlocking()
    {
        scheduledDispatches.clear();
        executor.shutdownNow();
    }

    boolean isScheduled(HintsStore store)
    {
        return scheduledDispatches.containsKey(store.hostId);
    }

    Future dispatch(HintsStore store)
    {
        return dispatch(store, store.hostId);
    }

    Future dispatch(HintsStore store, UUID hostId)
    {
        /*
         * It is safe to perform dispatch for the same host id concurrently in two or more threads,
         * however there is nothing to win from it - so we don't.
         *
         * Additionally, having just one dispatch task per host id ensures that we'll never violate our per-destination
         * rate limit, without having to share a ratelimiter between threads.
         *
         * It also simplifies reasoning about dispatch sessions.
         */
        return scheduledDispatches.computeIfAbsent(store.hostId, uuid -> executor.submit(new DispatchHintsTask(store, hostId)));
    }

    void completeDispatchBlockingly(HintsStore store)
    {
        Future future = scheduledDispatches.get(store.hostId);
        try
        {
            if (future != null)
                future.get();
        }
        catch (ExecutionException | InterruptedException e)
        {
            throw new RuntimeException(e);
        }
    }

    private final class DispatchHintsTask implements Runnable
    {
        private final HintsStore store;
        private final UUID hostId;
        private final RateLimiter rateLimiter;

        DispatchHintsTask(HintsStore store, UUID hostId)
        {
            this.store = store;
            this.hostId = hostId;

            // rate limit is in bytes per second. Uses Double.MAX_VALUE if disabled (set to 0 in cassandra.yaml).
            // max rate is scaled by the number of nodes in the cluster (CASSANDRA-5272).
            // the goal is to bound maximum hints traffic going towards a particular node from the rest of the cluster,
            // not total outgoing hints traffic from this node - this is why the rate limiter is not shared between
            // all the dispatch tasks (as there will be at most one dispatch task for a particular host id at a time).
            int nodesCount = Math.max(1, StorageService.instance.getTokenMetadata().getAllEndpoints().size() - 1);
            int throttleInKB = DatabaseDescriptor.getHintedHandoffThrottleInKB() / nodesCount;
            this.rateLimiter = RateLimiter.create(throttleInKB == 0 ? Double.MAX_VALUE : throttleInKB * 1024);
        }

        public void run()
        {
            try
            {
                dispatch();
            }
            finally
            {
                scheduledDispatches.remove(hostId);
            }
        }

        private void dispatch()
        {
            while (true)
            {
                if (isPaused.get())
                    break;

                HintsDescriptor descriptor = store.poll();
                if (descriptor == null)
                    break;

                try
                {
                    dispatch(descriptor);
                }
                catch (FSReadError e)
                {
                    logger.error("Failed to dispatch hints file {}: file is corrupted ({})", descriptor.fileName(), e);
                    store.cleanUp(descriptor);
                    store.blacklist(descriptor);
                    throw e;
                }
            }
        }

        private void dispatch(HintsDescriptor descriptor)
        {
            logger.debug("Dispatching hints file {}", descriptor.fileName());

            File file = new File(hintsDirectory, descriptor.fileName());
            Long offset = store.getDispatchOffset(descriptor).orElse(null);

            try (HintsDispatcher dispatcher = HintsDispatcher.create(file, rateLimiter, hostId, isPaused))
            {
                if (offset != null)
                    dispatcher.seek(offset);

                if (dispatcher.dispatch())
                {
                    if (!file.delete())
                        logger.error("Failed to delete hints file {}", descriptor.fileName());
                    store.cleanUp(descriptor);
                    logger.info("Finished hinted handoff of file {} to endpoint {}", descriptor.fileName(), hostId);
                }
                else
                {
                    store.markDispatchOffset(descriptor, dispatcher.dispatchOffset());
                    store.offerFirst(descriptor);
                    logger.info("Finished hinted handoff of file {} to endpoint {}, partially", descriptor.fileName(), hostId);
                }
            }
        }
    }
}