/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl.execution.init;

import com.hazelcast.internal.util.concurrent.update.ConcurrentConveyor;
import com.hazelcast.internal.util.concurrent.update.OneToOneConcurrentArrayQueue;
import com.hazelcast.internal.util.concurrent.update.QueuedPipe;
import com.hazelcast.jet.Edge.ForwardingPattern;
import com.hazelcast.jet.JetException;
import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.Processor;
import com.hazelcast.jet.ProcessorSupplier;
import com.hazelcast.jet.config.JetConfig;
import com.hazelcast.jet.impl.JetService;
import com.hazelcast.jet.impl.execution.ConcurrentInboundEdgeStream;
import com.hazelcast.jet.impl.execution.ConveyorCollector;
import com.hazelcast.jet.impl.execution.ConveyorCollectorWithPartition;
import com.hazelcast.jet.impl.execution.InboundEdgeStream;
import com.hazelcast.jet.impl.execution.OutboundCollector;
import com.hazelcast.jet.impl.execution.OutboundEdgeStream;
import com.hazelcast.jet.impl.execution.ProcessorTasklet;
import com.hazelcast.jet.impl.execution.ReceiverTasklet;
import com.hazelcast.jet.impl.execution.SenderTasklet;
import com.hazelcast.jet.impl.execution.Tasklet;
import com.hazelcast.jet.impl.execution.init.Contexts.ProcCtx;
import com.hazelcast.jet.impl.execution.init.Contexts.ProcSupplierCtx;
import com.hazelcast.jet.impl.util.SkewReductionPolicy.SkewExceededAction;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;
import com.hazelcast.spi.NodeEngine;
import com.hazelcast.spi.partition.IPartitionService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

import static com.hazelcast.internal.util.concurrent.update.ConcurrentConveyor.concurrentConveyor;
import static com.hazelcast.jet.impl.execution.OutboundCollector.compositeCollector;
import static com.hazelcast.jet.impl.util.Util.getJetInstance;
import static com.hazelcast.jet.impl.util.Util.getRemoteMembers;
import static com.hazelcast.jet.impl.util.Util.readList;
import static com.hazelcast.jet.impl.util.Util.writeList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

public class ExecutionPlan implements IdentifiedDataSerializable {

    private static final ILogger LOGGER = Logger.getLogger(ExecutionPlan.class);

    private final List<Tasklet> tasklets = new ArrayList<>();
    // dest vertex id --> dest ordinal --> sender addr -> receiver tasklet
    private final Map<Integer, Map<Integer, Map<Address, ReceiverTasklet>>> receiverMap = new HashMap<>();
    // dest vertex id --> dest ordinal --> dest addr --> sender tasklet
    private final Map<Integer, Map<Integer, Map<Address, SenderTasklet>>> senderMap = new HashMap<>();

    private Address[] partitionOwners;
    private List<VertexDef> vertices = new ArrayList<>();
    private final Map<String, ConcurrentConveyor<Object>[]> localConveyorMap = new HashMap<>();
    private final Map<String, Map<Address, ConcurrentConveyor<Object>>> edgeSenderConveyorMap = new HashMap<>();
    private PartitionArrangement ptionArrgmt;

    private NodeEngine nodeEngine;
    private long executionId;


    ExecutionPlan() {
    }

    ExecutionPlan(Address[] partitionOwners) {
        this.partitionOwners = partitionOwners;
    }

    public void initialize(NodeEngine nodeEngine, long executionId) {
        this.nodeEngine = nodeEngine;
        this.executionId = executionId;
        initProcSuppliers();
        initDag();

        this.ptionArrgmt = new PartitionArrangement(partitionOwners, nodeEngine.getThisAddress());
        JetInstance instance = getJetInstance(nodeEngine);
        for (VertexDef srcVertex : vertices) {
            int processorIdx = 0;
            for (Processor p : createProcessors(srcVertex, srcVertex.parallelism())) {
                // createOutboundEdgeStreams() populates localConveyorMap and edgeSenderConveyorMap.
                // Also populates instance fields: senderMap, receiverMap, tasklets.
                final List<OutboundEdgeStream> outboundStreams = createOutboundEdgeStreams(srcVertex, processorIdx);
                final List<InboundEdgeStream> inboundStreams = createInboundEdgeStreams(srcVertex, processorIdx);
                ILogger logger = nodeEngine.getLogger(
                        srcVertex.name() + '(' + p.getClass().getSimpleName() + ")#" + processorIdx);
                ProcCtx context =
                        new ProcCtx(instance, logger, srcVertex.name(), processorIdx + srcVertex.getProcIdxOffset());
                tasklets.add(new ProcessorTasklet(srcVertex.name(), context, p, inboundStreams, outboundStreams));
                processorIdx++;
            }
        }
        List<ReceiverTasklet> allReceivers = receiverMap.values().stream()
                                                        .flatMap(o -> o.values().stream())
                                                        .flatMap(a -> a.values().stream())
                                                        .collect(toList());

        tasklets.addAll(allReceivers);
    }

    public List<ProcessorSupplier> getProcessorSuppliers() {
        return vertices.stream().map(VertexDef::processorSupplier).collect(toList());
    }

    public Map<Integer, Map<Integer, Map<Address, ReceiverTasklet>>> getReceiverMap() {
        return receiverMap;
    }

    public Map<Integer, Map<Integer, Map<Address, SenderTasklet>>> getSenderMap() {
        return senderMap;
    }

    public List<Tasklet> getTasklets() {
        return tasklets;
    }

    void addVertex(VertexDef vertex) {
        vertices.add(vertex);
    }

    // Implementation of IdentifiedDataSerializable

    @Override
    public int getFactoryId() {
        return JetImplDataSerializerHook.FACTORY_ID;
    }

    @Override
    public int getId() {
        return JetImplDataSerializerHook.EXECUTION_PLAN;
    }

    @Override
    public void writeData(ObjectDataOutput out) throws IOException {
        writeList(out, vertices);
        out.writeInt(partitionOwners.length);
        for (Address address : partitionOwners) {
            out.writeObject(address);
        }
    }

    @Override
    public void readData(ObjectDataInput in) throws IOException {
        vertices = readList(in);
        int len = in.readInt();
        partitionOwners = new Address[len];
        for (int i = 0; i < len; i++) {
            partitionOwners[i] = in.readObject();
        }
    }

    // End implementation of IdentifiedDataSerializable

    private void initProcSuppliers() {
        JetService service = nodeEngine.getService(JetService.SERVICE_NAME);
        vertices.forEach(v -> v.processorSupplier().init(
                new ProcSupplierCtx(service.getJetInstance(), v.parallelism())));
    }

    private void initDag() {
        final Map<Integer, VertexDef> vMap = vertices.stream().collect(toMap(VertexDef::vertexId, v -> v));
        vertices.forEach(v -> {
            v.inboundEdges().forEach(e -> e.initTransientFields(vMap, v, false));
            v.outboundEdges().forEach(e -> e.initTransientFields(vMap, v, true));
        });
        final IPartitionService partitionService = nodeEngine.getPartitionService();
        vertices.stream()
                .map(VertexDef::outboundEdges)
                .flatMap(List::stream)
                .map(EdgeDef::partitioner)
                .filter(Objects::nonNull)
                .forEach(p -> p.init(partitionService::getPartitionId));
    }

    private static Collection<? extends Processor> createProcessors(VertexDef vertexDef, int parallelism) {
        final Collection<? extends Processor> processors = vertexDef.processorSupplier().get(parallelism);
        if (processors.size() != parallelism) {
            throw new JetException("ProcessorSupplier failed to return the requested number of processors." +
                    " Requested: " + parallelism + ", returned: " + processors.size());
        }
        return processors;
    }

    /**
     * Populates {@code localConveyorMap}, {@code edgeSenderConveyorMap}.
     * Populates {@link #senderMap} and {@link #tasklets} fields.
     */
    private List<OutboundEdgeStream> createOutboundEdgeStreams(VertexDef srcVertex, int processorIdx) {
        final List<OutboundEdgeStream> outboundStreams = new ArrayList<>();
        for (EdgeDef edge : srcVertex.outboundEdges()) {
            final Map<Address, ConcurrentConveyor<Object>> memberToSenderConveyorMap =
                    edge.isDistributed() ? memberToSenderConveyorMap(edgeSenderConveyorMap, edge) : null;
            outboundStreams.add(createOutboundEdgeStream(edge, processorIdx, memberToSenderConveyorMap));
        }
        return outboundStreams;
    }

    /**
     * Creates (if absent) for the given edge one sender tasklet per remote member,
     * each with a single conveyor with a number of producer queues feeding it.
     * Populates the {@link #senderMap} and {@link #tasklets} fields.
     */
    private Map<Address, ConcurrentConveyor<Object>> memberToSenderConveyorMap(
            Map<String, Map<Address, ConcurrentConveyor<Object>>> edgeSenderConveyorMap, EdgeDef edge
    ) {
        assert edge.isDistributed() : "Edge is not distributed";
        return edgeSenderConveyorMap.computeIfAbsent(edge.edgeId(), x -> {
            final Map<Address, ConcurrentConveyor<Object>> addrToConveyor = new HashMap<>();
            for (Address destAddr : getRemoteMembers(nodeEngine)) {
                final ConcurrentConveyor<Object> conveyor =
                        createConveyorArray(1, edge.sourceVertex().parallelism(), edge.getConfig().getQueueSize())[0];
                final ConcurrentInboundEdgeStream inboundEdgeStream = createInboundEdgeStream(
                        edge.destOrdinal(), edge.priority(), conveyor);
                final int destVertexId = edge.destVertex().vertexId();
                final SenderTasklet t = new SenderTasklet(inboundEdgeStream, nodeEngine,
                        destAddr, executionId, destVertexId, edge.getConfig().getPacketSizeLimit());
                senderMap.computeIfAbsent(destVertexId, xx -> new HashMap<>())
                         .computeIfAbsent(edge.destOrdinal(), xx -> new HashMap<>())
                         .put(destAddr, t);
                tasklets.add(t);
                addrToConveyor.put(destAddr, conveyor);
            }
            return addrToConveyor;
        });
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentConveyor<Object>[] createConveyorArray(int count, int queueCount, int queueSize) {
        ConcurrentConveyor<Object>[] concurrentConveyors = new ConcurrentConveyor[count];
        Arrays.setAll(concurrentConveyors, i -> {
            QueuedPipe<Object>[] queues = new QueuedPipe[queueCount];
            Arrays.setAll(queues, j -> new OneToOneConcurrentArrayQueue<>(queueSize));
            return concurrentConveyor(null, queues);
        });
        return concurrentConveyors;
    }

    private OutboundEdgeStream createOutboundEdgeStream(
            EdgeDef edge, int processorIndex, Map<Address, ConcurrentConveyor<Object>> senderConveyorMap
    ) {
        final int totalPtionCount = nodeEngine.getPartitionService().getPartitionCount();
        OutboundCollector[] outboundCollectors = createOutboundCollectors(edge, processorIndex, senderConveyorMap);
        int outboxLimit = edge.isBuffered() ? Integer.MAX_VALUE : edge.getConfig().getOutboxLimit();
        OutboundCollector compositeCollector = compositeCollector(outboundCollectors, edge, totalPtionCount);
        return new OutboundEdgeStream(edge.sourceOrdinal(), outboxLimit, compositeCollector);
    }

    private OutboundCollector[] createOutboundCollectors(EdgeDef edge, int processorIndex,
                                                      Map<Address, ConcurrentConveyor<Object>> senderConveyorMap) {
        final int upstreamParallelism = edge.sourceVertex().parallelism();
        final int downstreamParallelism = edge.destVertex().parallelism();
        final int numRemoteMembers = ptionArrgmt.remotePartitionAssignment.get().size();
        final int queueSize = edge.getConfig().getQueueSize();

        final int[][] ptionsPerProcessor =
                ptionArrgmt.assignPartitionsToProcessors(downstreamParallelism, edge.isDistributed());

        // in a one to many edge, each downstream processor is assigned only one processor.
        if (edge.forwardingPattern() == ForwardingPattern.ONE_TO_MANY) {
            if (downstreamParallelism < upstreamParallelism) {
                throw new IllegalArgumentException("Downstream parallelism (" + downstreamParallelism
                        + ") should be greater than or equal to upstream parallelism (" + upstreamParallelism
                        + ") for a ONE_TO_MANY edge " + edge.toString());
            }
            if (edge.isDistributed()) {
                throw new IllegalArgumentException("One to many edges must be local: " + edge.toString());
            }

            // there is only one producer per consumer for a one to many edge, so queueCount is always 1
            ConcurrentConveyor<Object>[] localConveyors = localConveyorMap.computeIfAbsent(edge.edgeId(),
                    e -> createConveyorArray(downstreamParallelism, 1, queueSize));
            return IntStream.range(0, downstreamParallelism)
                            .filter(i -> i % upstreamParallelism == processorIndex)
                            .mapToObj(i -> new ConveyorCollector(localConveyors[i], 0, ptionsPerProcessor[i]))
                            .toArray(OutboundCollector[]::new);
        }

        /*
         * Each edge is represented by an array of conveyors between the producers and consumers
         * There are as many conveyors as there are consumers.
         * Each conveyor has one queue per producer.
         *
         * For a distributed edge, there is one additional producer per member represented
         * by the ReceiverTasklet.
         */
        final ConcurrentConveyor<Object>[] localConveyors = localConveyorMap.computeIfAbsent(edge.edgeId(),
                e -> {
                    int queueCount = upstreamParallelism + (edge.isDistributed() ? numRemoteMembers : 0);
                    return createConveyorArray(downstreamParallelism, queueCount, queueSize);
                });
        final OutboundCollector[] localCollectors = new OutboundCollector[downstreamParallelism];
        Arrays.setAll(localCollectors, n ->
                new ConveyorCollector(localConveyors[n], processorIndex, ptionsPerProcessor[n]));

        // in a local edge, we only have the local collectors.
        if (!edge.isDistributed()) {
            return localCollectors;
        }

        // in a distributed edge, allCollectors[0] is the composite of local collectors, and
        // allCollectors[n] where n > 0 is a collector pointing to a remote member _n_.
        final int totalPtionCount = nodeEngine.getPartitionService().getPartitionCount();
        final OutboundCollector[] allCollectors;
        createIfAbsentReceiverTasklet(edge, ptionsPerProcessor, totalPtionCount);

        // assign remote partitions to outbound data collectors
        final Map<Address, int[]> memberToPartitions = ptionArrgmt.remotePartitionAssignment.get();
        allCollectors = new OutboundCollector[memberToPartitions.size() + 1];
        allCollectors[0] = compositeCollector(localCollectors, edge, totalPtionCount);
        int index = 1;
        for (Map.Entry<Address, int[]> entry : memberToPartitions.entrySet()) {
            allCollectors[index++] = new ConveyorCollectorWithPartition(senderConveyorMap.get(entry.getKey()),
                    processorIndex, entry.getValue());
        }
        return allCollectors;
    }

    private void createIfAbsentReceiverTasklet(EdgeDef edge, int[][] ptionsPerProcessor, int totalPtionCount) {
        final ConcurrentConveyor<Object>[] localConveyors = localConveyorMap.get(edge.edgeId());

        receiverMap.computeIfAbsent(edge.destVertex().vertexId(), x -> new HashMap<>())
                   .computeIfAbsent(edge.destOrdinal(), x -> {
                       Map<Address, ReceiverTasklet> addrToTasklet = new HashMap<>();
                       //create a receiver per address
                       int offset = 0;
                       for (Address addr : ptionArrgmt.remotePartitionAssignment.get().keySet()) {
                           final OutboundCollector[] collectors = new OutboundCollector[ptionsPerProcessor.length];
                           // assign the queues starting from end
                           final int queueOffset = --offset;
                           Arrays.setAll(collectors, n -> new ConveyorCollector(
                                   localConveyors[n], localConveyors[n].queueCount() + queueOffset,
                                   ptionsPerProcessor[n]));
                           final OutboundCollector collector = compositeCollector(collectors, edge, totalPtionCount);
                           ReceiverTasklet receiverTasklet = new ReceiverTasklet(
                                   collector, edge.getConfig().getReceiveWindowMultiplier(),
                                   getConfig().getInstanceConfig().getFlowControlPeriodMs());
                           addrToTasklet.put(addr, receiverTasklet);
                       }
                       return addrToTasklet;
                   });
    }

    private JetConfig getConfig() {
        JetService service = nodeEngine.getService(JetService.SERVICE_NAME);
        return service.getJetInstance().getConfig();
    }

    private List<InboundEdgeStream> createInboundEdgeStreams(VertexDef srcVertex, int processorIdx) {
        final List<InboundEdgeStream> inboundStreams = new ArrayList<>();
        for (EdgeDef inEdge : srcVertex.inboundEdges()) {
            // each tasklet has one input conveyor per edge
            final ConcurrentConveyor<Object> conveyor = localConveyorMap.get(inEdge.edgeId())[processorIdx];
            inboundStreams.add(createInboundEdgeStream(inEdge.destOrdinal(), inEdge.priority(), conveyor));
        }
        return inboundStreams;
    }

    @SuppressWarnings("checkstyle:magicnumber")
    private static ConcurrentInboundEdgeStream createInboundEdgeStream(
            int ordinal, int priority, ConcurrentConveyor<Object> conveyor
    ) {
        // TODO set these params through Edge
        return new ConcurrentInboundEdgeStream(conveyor, ordinal, priority, 6000, 1500,
                SkewExceededAction.NO_ACTION);
    }
}

