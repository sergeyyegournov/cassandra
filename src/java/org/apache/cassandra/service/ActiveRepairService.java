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
package org.apache.cassandra.service;

import java.io.*;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;

import com.google.common.base.Objects;
import com.google.common.collect.Sets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.concurrent.JMXConfigurableThreadPoolExecutor;
import org.apache.cassandra.concurrent.NamedThreadFactory;
import org.apache.cassandra.concurrent.Stage;
import org.apache.cassandra.concurrent.StageManager;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.compaction.AbstractCompactedRow;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.dht.AbstractBounds;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.gms.*;
import org.apache.cassandra.io.IVersionedSerializer;
import org.apache.cassandra.locator.TokenMetadata;
import org.apache.cassandra.net.*;
import org.apache.cassandra.streaming.StreamingRepairTask;
import org.apache.cassandra.utils.*;

/**
 * ActiveRepairService encapsulates "validating" (hashing) individual column families,
 * exchanging MerkleTrees with remote nodes via a tree request/response conversation,
 * and then triggering repairs for disagreeing ranges.
 *
 * The node where repair was invoked acts as the 'initiator,' where valid trees are sent after generation
 * and where the local and remote tree will rendezvous in rendezvous().
 * Once the trees rendezvous, a Differencer is executed and the service can trigger repairs
 * for disagreeing ranges.
 *
 * Tree comparison and repair triggering occur in the single threaded Stage.ANTI_ENTROPY.
 *
 * The steps taken to enact a repair are as follows:
 * 1. A repair is requested via JMX/nodetool:
 *   * The initiator sends TreeRequest messages to all neighbors of the target node: when a node
 *     receives a TreeRequest, it will perform a validation (read-only) compaction to immediately validate
 *     the column family.  This is performed on the CompactionManager ExecutorService.
 * 2. The validation process builds the merkle tree by:
 *   * Calling Validator.prepare(), which samples the column family to determine key distribution,
 *   * Calling Validator.add() in order for rows in repair range in the column family,
 *   * Calling Validator.complete() to indicate that all rows have been added.
 *     * Calling complete() indicates that a valid MerkleTree has been created for the column family.
 *     * The valid tree is returned to the requesting node via a TreeResponse.
 * 3. When a node receives a tree response, it passes the tree to rendezvous() to see if all responses are
 *    received. Once the initiator receives all responses, it creates Differencers on every tree pair combination.
 * 4. Differencers are executed in Stage.ANTI_ENTROPY, to compare the given two trees, and perform repair via the
 *    streaming api.
 */
public class ActiveRepairService
{
    private static final Logger logger = LoggerFactory.getLogger(ActiveRepairService.class);

    // singleton enforcement
    public static final ActiveRepairService instance = new ActiveRepairService();

    private static final ThreadPoolExecutor executor;
    static
    {
        executor = new JMXConfigurableThreadPoolExecutor(4,
                                                         60,
                                                         TimeUnit.SECONDS,
                                                         new LinkedBlockingQueue<Runnable>(),
                                                         new NamedThreadFactory("AntiEntropySessions"),
                                                         "internal");
    }

    public static enum Status
    {
        STARTED, SESSION_SUCCESS, SESSION_FAILED, FINISHED
    }

    /**
     * A map of active session.
     */
    private final ConcurrentMap<String, RepairSession> sessions;

    /**
     * Protected constructor. Use ActiveRepairService.instance.
     */
    protected ActiveRepairService()
    {
        sessions = new ConcurrentHashMap<String, RepairSession>();
    }

    /**
     * Requests repairs for the given keyspace and column families.
     *
     * @return Future for asynchronous call or null if there is no need to repair
     */
    public RepairFuture submitRepairSession(Range<Token> range, String tablename, boolean isSequential, boolean isLocal, String... cfnames)
    {
        RepairSession session = new RepairSession(range, tablename, isSequential, isLocal, cfnames);
        if (session.endpoints.isEmpty())
            return null;
        RepairFuture futureTask = session.getFuture();
        executor.execute(futureTask);
        return futureTask;
    }

    public void terminateSessions()
    {
        for (RepairSession session : sessions.values())
        {
            session.forceShutdown();
        }
    }

    // for testing only. Create a session corresponding to a fake request and
    // add it to the sessions (avoid NPE in tests)
    RepairFuture submitArtificialRepairSession(TreeRequest req, String tablename, String... cfnames)
    {
        RepairFuture futureTask = new RepairSession(req, tablename, cfnames).getFuture();
        executor.execute(futureTask);
        return futureTask;
    }

    /**
     * Return all of the neighbors with whom we share the provided range.
     *
     * @param table table to repair
     * @param toRepair token to repair
     * @param isLocal need to use only nodes from local datacenter
     *
     * @return neighbors with whom we share the provided range
     */
    static Set<InetAddress> getNeighbors(String table, Range<Token> toRepair, boolean isLocal)
    {
        StorageService ss = StorageService.instance;
        Map<Range<Token>, List<InetAddress>> replicaSets = ss.getRangeToAddressMap(table);
        Range<Token> rangeSuperSet = null;
        for (Range<Token> range : ss.getLocalRanges(table))
        {
            if (range.contains(toRepair))
            {
                rangeSuperSet = range;
                break;
            }
            else if (range.intersects(toRepair))
            {
                throw new IllegalArgumentException("Requested range intersects a local range but is not fully contained in one; this would lead to imprecise repair");
            }
        }
        if (rangeSuperSet == null || !replicaSets.containsKey(rangeSuperSet))
            return Collections.emptySet();

        Set<InetAddress> neighbors = new HashSet<InetAddress>(replicaSets.get(rangeSuperSet));
        neighbors.remove(FBUtilities.getBroadcastAddress());

        if (isLocal)
        {
            TokenMetadata.Topology topology = ss.getTokenMetadata().cloneOnlyTokenMap().getTopology();
            Set<InetAddress> localEndpoints = Sets.newHashSet(topology.getDatacenterEndpoints().get(DatabaseDescriptor.getLocalDataCenter()));
            return Sets.intersection(neighbors, localEndpoints);
        }

        return neighbors;
    }

    /**
     * Register a tree for the given request to be compared to the appropriate trees in Stage.ANTIENTROPY when they become available.
     */
    private void rendezvous(TreeRequest request, MerkleTree tree)
    {
        RepairSession session = sessions.get(request.sessionid);
        if (session == null)
        {
            logger.warn("Got a merkle tree response for unknown repair session {}: either this node has been restarted since the session was started, or the session has been interrupted for an unknown reason. ", request.sessionid);
            return;
        }

        RepairSession.RepairJob job = session.jobs.peek();
        if (job == null)
        {
            assert session.terminated();
            return;
        }

        logger.info(String.format("[repair #%s] Received merkle tree for %s from %s", session.getName(), request.cf.right, request.endpoint));

        if (job.addTree(request, tree) == 0)
        {
            logger.debug("All trees received for " + session.getName() + "/" + request.cf.right);
            job.submitDifferencers();

            // This job is complete, switching to next in line (note that only
            // one thread will can ever do this)
            session.jobs.poll();
            RepairSession.RepairJob nextJob = session.jobs.peek();
            if (nextJob == null)
                // We are done with this repair session as far as differencing
                // is considern. Just inform the session
                session.differencingDone.signalAll();
            else
                nextJob.sendTreeRequests();
        }
    }

    /**
     * Responds to the node that requested the given valid tree.
     * @param validator A locally generated validator
     * @param local localhost (parameterized for testing)
     */
    void respond(Validator validator, InetAddress local)
    {
        MessagingService ms = MessagingService.instance();

        try
        {
            if (!validator.request.endpoint.equals(FBUtilities.getBroadcastAddress()))
                logger.info(String.format("[repair #%s] Sending completed merkle tree to %s for %s", validator.request.sessionid, validator.request.endpoint, validator.request.cf));
            ms.sendOneWay(validator.createMessage(), validator.request.endpoint);
        }
        catch (Exception e)
        {
            logger.error(String.format("[repair #%s] Error sending completed merkle tree to %s for %s ", validator.request.sessionid, validator.request.endpoint, validator.request.cf), e);
        }
    }

    /**
     * A Strategy to handle building a merkle tree for a column family.
     *
     * Lifecycle:
     * 1. prepare() - Initialize tree with samples.
     * 2. add() - 0 or more times, to add hashes to the tree.
     * 3. complete() - complete building tree and send it back to the initiator
     */
    public static class Validator implements Runnable
    {
        public final TreeRequest request;
        public final MerkleTree tree;

        // null when all rows with the min token have been consumed
        private transient long validated;
        private transient MerkleTree.TreeRange range;
        private transient MerkleTree.TreeRangeIterator ranges;
        private transient DecoratedKey lastKey;

        public final static MerkleTree.RowHash EMPTY_ROW = new MerkleTree.RowHash(null, new byte[0]);
        public static ValidatorSerializer serializer = new ValidatorSerializer();

        public Validator(TreeRequest request)
        {
            this(request,
                 // TODO: memory usage (maxsize) should either be tunable per
                 // CF, globally, or as shared for all CFs in a cluster
                 new MerkleTree(DatabaseDescriptor.getPartitioner(), request.range, MerkleTree.RECOMMENDED_DEPTH, (int)Math.pow(2, 15)));
        }

        Validator(TreeRequest request, MerkleTree tree)
        {
            this.request = request;
            this.tree = tree;
            // Reestablishing the range because we don't serialize it (for bad
            // reason - see MerkleTree for details)
            this.tree.fullRange = this.request.range;
            validated = 0;
            range = null;
            ranges = null;
        }

        public void prepare(ColumnFamilyStore cfs)
        {
            if (!tree.partitioner().preservesOrder())
            {
                // You can't beat an even tree distribution for md5
                tree.init();
            }
            else
            {
                List<DecoratedKey> keys = new ArrayList<DecoratedKey>();
                for (DecoratedKey sample : cfs.keySamples(request.range))
                {
                    assert request.range.contains(sample.token): "Token " + sample.token + " is not within range " + request.range;
                    keys.add(sample);
                }

                if (keys.isEmpty())
                {
                    // use an even tree distribution
                    tree.init();
                }
                else
                {
                    int numkeys = keys.size();
                    Random random = new Random();
                    // sample the column family using random keys from the index
                    while (true)
                    {
                        DecoratedKey dk = keys.get(random.nextInt(numkeys));
                        if (!tree.split(dk.token))
                            break;
                    }
                }
            }
            logger.debug("Prepared AEService tree of size " + tree.size() + " for " + request);
            ranges = tree.invalids();
        }

        /**
         * Called (in order) for rows in given range present in the CF.
         * Hashes the row, and adds it to the tree being built.
         *
         * @param row The row.
         */
        public void add(AbstractCompactedRow row)
        {
            assert request.range.contains(row.key.token) : row.key.token + " is not contained in " + request.range;
            assert lastKey == null || lastKey.compareTo(row.key) < 0
                   : "row " + row.key + " received out of order wrt " + lastKey;
            lastKey = row.key;

            if (range == null)
                range = ranges.next();

            // generate new ranges as long as case 1 is true
            while (!range.contains(row.key.token))
            {
                // add the empty hash, and move to the next range
                range.addHash(EMPTY_ROW);
                range = ranges.next();
            }

            // case 3 must be true: mix in the hashed row
            range.addHash(rowHash(row));
        }

        private MerkleTree.RowHash rowHash(AbstractCompactedRow row)
        {
            validated++;
            // MerkleTree uses XOR internally, so we want lots of output bits here
            MessageDigest digest = FBUtilities.newMessageDigest("SHA-256");
            row.update(digest);
            return new MerkleTree.RowHash(row.key.token, digest.digest());
        }

        /**
         * Registers the newly created tree for rendezvous in Stage.ANTI_ENTROPY.
         */
        public void complete()
        {
            completeTree();

            StageManager.getStage(Stage.ANTI_ENTROPY).execute(this);
            logger.debug("Validated " + validated + " rows into AEService tree for " + request);
        }

        void completeTree()
        {
            assert ranges != null : "Validator was not prepared()";

            if (range != null)
                range.addHash(EMPTY_ROW);
            while (ranges.hasNext())
            {
                range = ranges.next();
                range.addHash(EMPTY_ROW);
            }
        }

        /**
         * Called after the validation lifecycle to respond with the now valid tree. Runs in Stage.ANTI_ENTROPY.
         */
        public void run()
        {
            // respond to the request that triggered this validation
            ActiveRepairService.instance.respond(this, FBUtilities.getBroadcastAddress());
        }

        public MessageOut<Validator> createMessage()
        {
            return new MessageOut<Validator>(MessagingService.Verb.TREE_RESPONSE, this, Validator.serializer);
        }

        public static class ValidatorSerializer implements IVersionedSerializer<Validator>
        {
            public void serialize(Validator validator, DataOutput out, int version) throws IOException
            {
                TreeRequest.serializer.serialize(validator.request, out, version);
                MerkleTree.serializer.serialize(validator.tree, out, version);
            }

            public Validator deserialize(DataInput in, int version) throws IOException
            {
                final TreeRequest request = TreeRequest.serializer.deserialize(in, version);
                try
                {
                    return new Validator(request, MerkleTree.serializer.deserialize(in, version));
                }
                catch(Exception e)
                {
                    throw new RuntimeException(e);
                }
            }

            public long serializedSize(Validator validator, int version)
            {
                return TreeRequest.serializer.serializedSize(validator.request, version)
                       + MerkleTree.serializer.serializedSize(validator.tree, version);
            }
        }
    }

    /**
     * Handler for requests from remote nodes to generate a valid tree.
     */
    public static class TreeRequestVerbHandler implements IVerbHandler<TreeRequest>
    {
        /**
         * Trigger a validation compaction which will return the tree upon completion.
         */
        public void doVerb(MessageIn<TreeRequest> message, int id)
        {
            TreeRequest remotereq = message.payload;
            TreeRequest request = new TreeRequest(remotereq.sessionid, message.from, remotereq.range, remotereq.gcBefore, remotereq.cf);

            // trigger read-only compaction
            ColumnFamilyStore store = Table.open(request.cf.left).getColumnFamilyStore(request.cf.right);
            Validator validator = new Validator(request);
            logger.debug("Queueing validation compaction for " + request);
            CompactionManager.instance.submitValidation(store, validator);
        }
    }

    /**
     * Handler for responses from remote nodes which contain a valid tree.
     * The payload is a completed Validator object from the remote endpoint.
     */
    public static class TreeResponseVerbHandler implements IVerbHandler<Validator>
    {
        public void doVerb(MessageIn<Validator> message, int id)
        {
            // deserialize the remote tree, and register it
            Validator response = message.payload;
            TreeRequest request = new TreeRequest(response.request.sessionid, message.from, response.request.range, response.request.gcBefore, response.request.cf);
            ActiveRepairService.instance.rendezvous(request, response.tree);
        }
    }

    /**
     * A tuple of table, cf, address and range that represents a location we have an outstanding TreeRequest for.
     */
    public static class TreeRequest
    {
        public static final TreeRequestSerializer serializer = new TreeRequestSerializer();

        public final String sessionid;
        public final InetAddress endpoint;
        public final Range<Token> range;
        public final int gcBefore;
        public final CFPath cf;

        public TreeRequest(String sessionid, InetAddress endpoint, Range<Token> range, int gcBefore, CFPath cf)
        {
            this.sessionid = sessionid;
            this.endpoint = endpoint;
            this.cf = cf;
            this.gcBefore = gcBefore;
            this.range = range;
        }

        @Override
        public final int hashCode()
        {
            return Objects.hashCode(sessionid, endpoint, gcBefore, cf, range);
        }

        @Override
        public final boolean equals(Object o)
        {
            if(!(o instanceof TreeRequest))
                return false;
            TreeRequest that = (TreeRequest)o;
            // handles nulls properly
            return Objects.equal(sessionid, that.sessionid) && Objects.equal(endpoint, that.endpoint) && gcBefore == that.gcBefore && Objects.equal(cf, that.cf) && Objects.equal(range, that.range);
        }

        @Override
        public String toString()
        {
            return "#<TreeRequest " + sessionid + ", " + endpoint + ", " + gcBefore + ", " + cf  + ", " + range + ">";
        }

        public MessageOut<TreeRequest> createMessage()
        {
            return new MessageOut<TreeRequest>(MessagingService.Verb.TREE_REQUEST, this, TreeRequest.serializer);
        }

        public static class TreeRequestSerializer implements IVersionedSerializer<TreeRequest>
        {
            public void serialize(TreeRequest request, DataOutput out, int version) throws IOException
            {
                out.writeUTF(request.sessionid);
                CompactEndpointSerializationHelper.serialize(request.endpoint, out);

                if (version >= MessagingService.VERSION_20)
                    out.writeInt(request.gcBefore);
                out.writeUTF(request.cf.left);
                out.writeUTF(request.cf.right);
                AbstractBounds.serializer.serialize(request.range, out, version);
            }

            public TreeRequest deserialize(DataInput in, int version) throws IOException
            {
                String sessId = in.readUTF();
                InetAddress endpoint = CompactEndpointSerializationHelper.deserialize(in);
                int gcBefore = -1;
                if (version >= MessagingService.VERSION_20)
                    gcBefore = in.readInt();
                CFPath cfpair = new CFPath(in.readUTF(), in.readUTF());
                Range<Token> range;
                range = (Range<Token>) AbstractBounds.serializer.deserialize(in, version);

                return new TreeRequest(sessId, endpoint, range, gcBefore, cfpair);
            }

            public long serializedSize(TreeRequest request, int version)
            {
                return TypeSizes.NATIVE.sizeof(request.sessionid)
                     + CompactEndpointSerializationHelper.serializedSize(request.endpoint)
                     + TypeSizes.NATIVE.sizeof(request.gcBefore)
                     + TypeSizes.NATIVE.sizeof(request.cf.left)
                     + TypeSizes.NATIVE.sizeof(request.cf.right)
                     + AbstractBounds.serializer.serializedSize(request.range, version);
            }
        }
    }

    /**
     * Triggers repairs with all neighbors for the given table, cfs and range.
     */
    static class RepairSession extends WrappedRunnable implements IEndpointStateChangeSubscriber, IFailureDetectionEventListener
    {
        private final String sessionName;
        private final boolean isSequential;
        private final String tablename;
        private final String[] cfnames;
        private final Range<Token> range;
        private volatile Exception exception;
        private final AtomicBoolean isFailed = new AtomicBoolean(false);

        private final Set<InetAddress> endpoints;
        final Queue<RepairJob> jobs = new ConcurrentLinkedQueue<RepairJob>();
        final Map<String, RepairJob> activeJobs = new ConcurrentHashMap<String, RepairJob>();

        private final SimpleCondition completed = new SimpleCondition();
        public final Condition differencingDone = new SimpleCondition();

        private volatile boolean terminated = false;

        public RepairSession(TreeRequest req, String tablename, String... cfnames)
        {
            this(req.sessionid, req.range, tablename, false, false, cfnames);
            ActiveRepairService.instance.sessions.put(getName(), this);
        }

        public RepairSession(Range<Token> range, String tablename, boolean isSequential, boolean isLocal, String... cfnames)
        {
            this(UUIDGen.getTimeUUID().toString(), range, tablename, isSequential, isLocal, cfnames);
        }

        private RepairSession(String id, Range<Token> range, String tablename, boolean isSequential, boolean isLocal, String[] cfnames)
        {
            this.sessionName = id;
            this.isSequential = isSequential;
            this.tablename = tablename;
            this.cfnames = cfnames;
            assert cfnames.length > 0 : "Repairing no column families seems pointless, doesn't it";
            this.range = range;
            this.endpoints = ActiveRepairService.getNeighbors(tablename, range, isLocal);
        }

        public String getName()
        {
            return sessionName;
        }

        public Range<Token> getRange()
        {
            return range;
        }

        RepairFuture getFuture()
        {
            return new RepairFuture(this);
        }

        private String repairedNodes()
        {
            StringBuilder sb = new StringBuilder();
            sb.append(FBUtilities.getBroadcastAddress());
            for (InetAddress ep : endpoints)
                sb.append(", ").append(ep);
            return sb.toString();
        }

        // we don't care about the return value but care about it throwing exception
        public void runMayThrow() throws Exception
        {
            logger.info(String.format("[repair #%s] new session: will sync %s on range %s for %s.%s", getName(), repairedNodes(), range, tablename, Arrays.toString(cfnames)));

            if (endpoints.isEmpty())
            {
                differencingDone.signalAll();
                logger.info(String.format("[repair #%s] No neighbors to repair with on range %s: session completed", getName(), range));
                return;
            }

            // Checking all nodes are live
            for (InetAddress endpoint : endpoints)
            {
                if (!FailureDetector.instance.isAlive(endpoint))
                {
                    String message = String.format("Cannot proceed on repair because a neighbor (%s) is dead: session failed", endpoint);
                    differencingDone.signalAll();
                    logger.error(String.format("[repair #%s] ", getName()) + message);
                    throw new IOException(message);
                }
            }

            ActiveRepairService.instance.sessions.put(getName(), this);
            Gossiper.instance.register(this);
            FailureDetector.instance.registerFailureDetectionEventListener(this);
            try
            {
                // Create and queue a RepairJob for each column family
                for (String cfname : cfnames)
                {
                    RepairJob job = new RepairJob(cfname);
                    jobs.offer(job);
                    activeJobs.put(cfname, job);
                }

                jobs.peek().sendTreeRequests();

                // block whatever thread started this session until all requests have been returned:
                // if this thread dies, the session will still complete in the background
                completed.await();
                if (exception == null)
                {
                    logger.info(String.format("[repair #%s] session completed successfully", getName()));
                }
                else
                {
                    logger.error(String.format("[repair #%s] session completed with the following error", getName()), exception);
                    throw exception;
                }
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException("Interrupted while waiting for repair.");
            }
            finally
            {
                // mark this session as terminated
                terminate();
                FailureDetector.instance.unregisterFailureDetectionEventListener(this);
                Gossiper.instance.unregister(this);
                ActiveRepairService.instance.sessions.remove(getName());
            }
        }

        /**
         * @return whether this session is terminated
         */
        public boolean terminated()
        {
            return terminated;
        }

        public void terminate()
        {
            terminated = true;
            for (RepairJob job : jobs)
                job.terminate();
            jobs.clear();
            activeJobs.clear();
        }

        /**
         * terminate this session.
         */
        public void forceShutdown()
        {
            differencingDone.signalAll();
            completed.signalAll();
        }

        void completed(Differencer differencer)
        {
            logger.debug(String.format("[repair #%s] Repair completed between %s and %s on %s",
                                       getName(),
                                       differencer.r1.endpoint,
                                       differencer.r2.endpoint,
                                       differencer.cfname));
            RepairJob job = activeJobs.get(differencer.cfname);
            if (job == null)
            {
                assert terminated;
                return;
            }

            if (job.completedSynchronization(differencer))
            {
                activeJobs.remove(differencer.cfname);
                String remaining = activeJobs.size() == 0 ? "" : String.format(" (%d remaining column family to sync for this session)", activeJobs.size());
                logger.info(String.format("[repair #%s] %s is fully synced%s", getName(), differencer.cfname, remaining));
                if (activeJobs.isEmpty())
                    completed.signalAll();
            }
        }

        void failedNode(InetAddress remote)
        {
            String errorMsg = String.format("Endpoint %s died", remote);
            exception = new IOException(errorMsg);
            // If a node failed, we stop everything (though there could still be some activity in the background)
            forceShutdown();
        }

        public void onJoin(InetAddress endpoint, EndpointState epState) {}
        public void onChange(InetAddress endpoint, ApplicationState state, VersionedValue value) {}
        public void onAlive(InetAddress endpoint, EndpointState state) {}
        public void onDead(InetAddress endpoint, EndpointState state) {}

        public void onRemove(InetAddress endpoint)
        {
            convict(endpoint, Double.MAX_VALUE);
        }

        public void onRestart(InetAddress endpoint, EndpointState epState)
        {
            convict(endpoint, Double.MAX_VALUE);
        }

        public void convict(InetAddress endpoint, double phi)
        {
            if (!endpoints.contains(endpoint))
                return;

            // We want a higher confidence in the failure detection than usual because failing a repair wrongly has a high cost.
            if (phi < 2 * DatabaseDescriptor.getPhiConvictThreshold())
                return;

            // Though unlikely, it is possible to arrive here multiple time and we
            // want to avoid print an error message twice
            if (!isFailed.compareAndSet(false, true))
                return;

            failedNode(endpoint);
        }

        class RepairJob
        {
            private final String cfname;
            // first we send tree requests.  this tracks the endpoints remaining to hear from
            private final RequestCoordinator<TreeRequest> treeRequests;
            // tree responses are then tracked here
            private final List<TreeResponse> trees = new ArrayList<TreeResponse>(endpoints.size() + 1);
            // once all responses are received, each tree is compared with each other, and differencer tasks
            // are submitted.  the job is done when all differencers are complete.
            private final RequestCoordinator<Differencer> differencers;
            private final Condition requestsSent = new SimpleCondition();
            private CountDownLatch snapshotLatch = null;

            public RepairJob(String cfname)
            {
                this.cfname = cfname;
                this.treeRequests = new RequestCoordinator<TreeRequest>(isSequential)
                {
                    public void send(TreeRequest r)
                    {
                        MessagingService.instance().sendOneWay(r.createMessage(), r.endpoint);
                    }
                };
                this.differencers = new RequestCoordinator<Differencer>(isSequential)
                {
                    public void send(Differencer d)
                    {
                        StageManager.getStage(Stage.ANTI_ENTROPY).execute(d);
                    }
                };
            }

            /**
             * Send merkle tree request to every involved neighbor.
             */
            public void sendTreeRequests()
            {
                // send requests to all nodes
                List<InetAddress> allEndpoints = new ArrayList<InetAddress>(endpoints);
                allEndpoints.add(FBUtilities.getBroadcastAddress());

                if (isSequential)
                    makeSnapshots(endpoints);

                int gcBefore = (int)(System.currentTimeMillis()/1000) - Table.open(tablename).getColumnFamilyStore(cfname).metadata.getGcGraceSeconds();

                for (InetAddress endpoint : allEndpoints)
                    treeRequests.add(new TreeRequest(getName(), endpoint, range, gcBefore, new CFPath(tablename, cfname)));

                logger.info(String.format("[repair #%s] requesting merkle trees for %s (to %s)", getName(), cfname, allEndpoints));
                treeRequests.start();
                requestsSent.signalAll();
            }

            public void makeSnapshots(Collection<InetAddress> endpoints)
            {
                try
                {
                    snapshotLatch = new CountDownLatch(endpoints.size());
                    IAsyncCallback callback = new IAsyncCallback()
                    {
                        public boolean isLatencyForSnitch()
                        {
                            return false;
                        }

                        public void response(MessageIn msg)
                        {
                            RepairJob.this.snapshotLatch.countDown();
                        }
                    };
                    for (InetAddress endpoint : endpoints)
                        MessagingService.instance().sendRR(new SnapshotCommand(tablename, cfname, sessionName, false).createMessage(), endpoint, callback);
                    snapshotLatch.await();
                    snapshotLatch = null;
                }
                catch (InterruptedException e)
                {
                    throw new RuntimeException(e);
                }
            }

            /**
             * Add a new received tree and return the number of remaining tree to
             * be received for the job to be complete.
             *
             * Callers may assume exactly one addTree call will result in zero remaining endpoints.
             */
            public synchronized int addTree(TreeRequest request, MerkleTree tree)
            {
                // Wait for all request to have been performed (see #3400)
                try
                {
                    requestsSent.await();
                }
                catch (InterruptedException e)
                {
                    throw new AssertionError("Interrupted while waiting for requests to be sent");
                }

                assert request.cf.right.equals(cfname);
                trees.add(new TreeResponse(request.endpoint, tree));
                return treeRequests.completed(request);
            }

            /**
             * Submit differencers for running.
             * All tree *must* have been received before this is called.
             */
            public void submitDifferencers()
            {
                // We need to difference all trees one against another
                for (int i = 0; i < trees.size() - 1; ++i)
                {
                    TreeResponse r1 = trees.get(i);
                    for (int j = i + 1; j < trees.size(); ++j)
                    {
                        TreeResponse r2 = trees.get(j);
                        Differencer differencer = new Differencer(cfname, r1, r2);
                        logger.debug("Queueing comparison {}", differencer);
                        differencers.add(differencer);
                    }
                }
                differencers.start();
                trees.clear(); // allows gc to do its thing
            }

            /**
             * @return true if the differencer was the last remaining
             */
            synchronized boolean completedSynchronization(Differencer differencer)
            {
                return differencers.completed(differencer) == 0;
            }

            public void terminate()
            {
                if (snapshotLatch != null)
                {
                    while (snapshotLatch.getCount() > 0)
                        snapshotLatch.countDown();
                }
            }
        }

        /**
         * Runs on the node that initiated a request to compare two trees, and launch repairs for disagreeing ranges.
         */
        class Differencer implements Runnable
        {
            public final String cfname;
            public final TreeResponse r1;
            public final TreeResponse r2;
            public final List<Range<Token>> differences = new ArrayList<Range<Token>>();

            Differencer(String cfname, TreeResponse r1, TreeResponse r2)
            {
                this.cfname = cfname;
                this.r1 = r1;
                this.r2 = r2;
            }

            /**
             * Compares our trees, and triggers repairs for any ranges that mismatch.
             */
            public void run()
            {
                // restore partitioners (in case we were serialized)
                if (r1.tree.partitioner() == null)
                    r1.tree.partitioner(StorageService.getPartitioner());
                if (r2.tree.partitioner() == null)
                    r2.tree.partitioner(StorageService.getPartitioner());

                // compare trees, and collect differences
                differences.addAll(MerkleTree.difference(r1.tree, r2.tree));

                // choose a repair method based on the significance of the difference
                String format = String.format("[repair #%s] Endpoints %s and %s %%s for %s", getName(), r1.endpoint, r2.endpoint, cfname);
                if (differences.isEmpty())
                {
                    logger.info(String.format(format, "are consistent"));
                    completed(this);
                    return;
                }

                // non-0 difference: perform streaming repair
                logger.info(String.format(format, "have " + differences.size() + " range(s) out of sync"));
                performStreamingRepair();
            }

            /**
             * Starts sending/receiving our list of differences to/from the remote endpoint: creates a callback
             * that will be called out of band once the streams complete.
             */
            void performStreamingRepair()
            {
                Runnable callback = new Runnable()
                {
                    public void run()
                    {
                        completed(Differencer.this);
                    }
                };
                StreamingRepairTask task = StreamingRepairTask.create(r1.endpoint, r2.endpoint, tablename, cfname, differences, callback);

                task.run();
            }

            public String toString()
            {
                return "#<Differencer " + r1.endpoint + "<->" + r2.endpoint + "/" + range + ">";
            }
        }
    }

    static class TreeResponse
    {
        public final InetAddress endpoint;
        public final MerkleTree tree;

        TreeResponse(InetAddress endpoint, MerkleTree tree)
        {
            this.endpoint = endpoint;
            this.tree = tree;
        }
    }

    public static class RepairFuture extends FutureTask
    {
        public final RepairSession session;

        RepairFuture(RepairSession session)
        {
            super(session, null);
            this.session = session;
        }
    }

    public static abstract class RequestCoordinator<R>
    {
        private final Order<R> orderer;

        protected RequestCoordinator(boolean isSequential)
        {
            this.orderer = isSequential ? new SequentialOrder(this) : new ParallelOrder(this);
        }

        public abstract void send(R request);

        public void add(R request)
        {
            orderer.add(request);
        }

        public void start()
        {
            orderer.start();
        }

        // Returns how many request remains
        public int completed(R request)
        {
            return orderer.completed(request);
        }

        private static abstract class Order<R>
        {
            protected final RequestCoordinator<R> coordinator;

            Order(RequestCoordinator<R> coordinator)
            {
                this.coordinator = coordinator;
            }

            public abstract void add(R request);
            public abstract void start();
            public abstract int completed(R request);
        }

        private static class SequentialOrder<R> extends Order<R>
        {
            private final Queue<R> requests = new LinkedList<R>();

            SequentialOrder(RequestCoordinator<R> coordinator)
            {
                super(coordinator);
            }

            public void add(R request)
            {
                requests.add(request);
            }

            public void start()
            {
                if (requests.isEmpty())
                    return;

                coordinator.send(requests.peek());
            }

            public int completed(R request)
            {
                assert request.equals(requests.peek());
                requests.poll();
                int remaining = requests.size();
                if (remaining != 0)
                    coordinator.send(requests.peek());
                return remaining;
            }
        }

        private static class ParallelOrder<R> extends Order<R>
        {
            private final Set<R> requests = new HashSet<R>();

            ParallelOrder(RequestCoordinator<R> coordinator)
            {
                super(coordinator);
            }

            public void add(R request)
            {
                requests.add(request);
            }

            public void start()
            {
                for (R request : requests)
                    coordinator.send(request);
            }

            public int completed(R request)
            {
                requests.remove(request);
                return requests.size();
            }
        }

    }
}
