/*
 * Copyright (c) 2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.netconf.messagebus.eventsources.netconf;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.opendaylight.mdsal.binding.dom.codec.impl.BindingNormalizedNodeCodecRegistry;
import org.opendaylight.mdsal.binding.generator.impl.ModuleInfoBackedContext;
import org.opendaylight.mdsal.binding.generator.util.BindingRuntimeContext;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.mdsal.dom.api.DOMDataBroker;
import org.opendaylight.mdsal.dom.api.DOMDataTreeReadTransaction;
import org.opendaylight.mdsal.dom.api.DOMMountPoint;
import org.opendaylight.mdsal.dom.api.DOMNotificationListener;
import org.opendaylight.mdsal.dom.api.DOMNotificationService;
import org.opendaylight.mdsal.dom.api.DOMRpcResult;
import org.opendaylight.mdsal.dom.api.DOMRpcService;
import org.opendaylight.mdsal.dom.api.DOMService;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.netconf.notification._1._0.rev080714.CreateSubscriptionInput;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.netconf.notification._1._0.rev080714.CreateSubscriptionInputBuilder;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.netmod.notification.rev080714.Netconf;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.netmod.notification.rev080714.netconf.Streams;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.netmod.notification.rev080714.netconf.streams.Stream;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.types.rev130715.DateAndTime;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.ContainerNode;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.opendaylight.yangtools.yang.model.api.SchemaPath;

/**
 * Facade of mounted netconf device.
 */
class NetconfEventSourceMount {

    private static final BindingNormalizedNodeCodecRegistry CODEC_REGISTRY;
    private static final YangInstanceIdentifier STREAMS_PATH = YangInstanceIdentifier.builder().node(Netconf.QNAME)
            .node(Streams.QNAME).build();
    private static final SchemaPath CREATE_SUBSCRIPTION = SchemaPath
            .create(true, QName.create(CreateSubscriptionInput.QNAME, "create-subscription"));

    static {
        final ModuleInfoBackedContext moduleInfoBackedContext = ModuleInfoBackedContext.create();
        moduleInfoBackedContext.addModuleInfos(Collections.singletonList(org.opendaylight.yang.gen.v1.urn.ietf.params
                .xml.ns.netmod.notification.rev080714.$YangModuleInfoImpl.getInstance()));
        SchemaContext notificationsSchemaCtx = moduleInfoBackedContext.tryToCreateSchemaContext().get();

        CODEC_REGISTRY = new BindingNormalizedNodeCodecRegistry(BindingRuntimeContext.create(moduleInfoBackedContext,
                notificationsSchemaCtx));
    }

    private final DOMMountPoint mountPoint;
    private final DOMRpcService rpcService;
    private final DOMNotificationService notificationService;
    private final DOMDataBroker dataBroker;
    private final Node node;
    private final String nodeId;

    NetconfEventSourceMount(final Node node, final DOMMountPoint mountPoint) {
        this.mountPoint = mountPoint;
        this.node = node;
        this.nodeId = node.getNodeId().getValue();
        this.rpcService = getService(mountPoint, DOMRpcService.class);
        this.notificationService = getService(mountPoint, DOMNotificationService.class);
        this.dataBroker = getService(mountPoint, DOMDataBroker.class);
    }

    private static <T extends DOMService> T getService(final DOMMountPoint mountPoint, final Class<T> service) {
        final Optional<T> optional = mountPoint.getService(service);
        Preconditions.checkState(optional.isPresent(), "Service not present on mount point: %s", service.getName());
        return optional.get();
    }

    Node getNode() {
        return node;
    }

    String getNodeId() {
        return nodeId;
    }

    /**
     * Invokes create-subscription rpc on mounted device stream. If lastEventTime is provided and stream supports
     * replay,
     * rpc will be invoked with start time parameter.
     *
     * @param stream        stream
     * @param lastEventTime last event time
     * @return rpc result
     */
    ListenableFuture<DOMRpcResult> invokeCreateSubscription(final Stream stream,
            final Optional<Instant> lastEventTime) {
        final CreateSubscriptionInputBuilder inputBuilder = new CreateSubscriptionInputBuilder()
                .setStream(stream.getName());
        if (lastEventTime.isPresent() && stream.isReplaySupport()) {
            final ZonedDateTime dateTime = lastEventTime.get().atZone(ZoneId.systemDefault());
            final String formattedDate = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dateTime);
            inputBuilder.setStartTime(new DateAndTime(formattedDate));
        }
        final CreateSubscriptionInput input = inputBuilder.build();
        final ContainerNode nnInput = CODEC_REGISTRY.toNormalizedNodeRpcData(input);
        return rpcService.invokeRpc(CREATE_SUBSCRIPTION, nnInput);
    }

    /**
     * Invokes create-subscription rpc on mounted device stream.
     *
     * @param stream stream
     * @return rpc result
     */
    ListenableFuture<DOMRpcResult> invokeCreateSubscription(final Stream stream) {
        return invokeCreateSubscription(stream, Optional.empty());
    }

    /**
     * Returns list of streams available on device.
     *
     * @return list of streams
     * @throws ExecutionException if data read fails
     * @throws InterruptedException if data read fails
     */
    List<Stream> getAvailableStreams() throws InterruptedException, ExecutionException {
        final Optional<NormalizedNode<?, ?>> streams;
        try (DOMDataTreeReadTransaction tx = dataBroker.newReadOnlyTransaction()) {
            streams = tx.read(LogicalDatastoreType.OPERATIONAL, STREAMS_PATH).get();
        }
        if (streams.isPresent()) {
            Streams streams1 = (Streams) CODEC_REGISTRY.fromNormalizedNode(STREAMS_PATH, streams.get()).getValue();
            return streams1.getStream();
        }
        return Collections.emptyList();
    }

    SchemaContext getSchemaContext() {
        return mountPoint.getSchemaContext();
    }

    /**
     * Registers notification listener to receive a set of notifications.
     *
     * @param listener         listener
     * @param notificationPath notification path
     * @return ListenerRegistration
     * @see DOMNotificationService#registerNotificationListener(DOMNotificationListener, SchemaPath...)
     */
    ListenerRegistration<DOMNotificationListener> registerNotificationListener(final DOMNotificationListener listener,
                                                                               final SchemaPath notificationPath) {
        return notificationService.registerNotificationListener(listener, notificationPath);
    }

}
