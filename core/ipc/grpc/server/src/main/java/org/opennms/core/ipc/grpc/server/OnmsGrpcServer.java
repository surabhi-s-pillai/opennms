/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2019 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2019 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.core.ipc.grpc.server;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.opennms.core.ipc.grpc.common.Empty;
import org.opennms.core.ipc.grpc.common.OnmsIpcGrpc;
import org.opennms.core.ipc.grpc.common.RpcMessage;
import org.opennms.core.ipc.grpc.common.SinkMessage;
import org.opennms.core.ipc.sink.api.Message;
import org.opennms.core.ipc.sink.api.SinkModule;
import org.opennms.core.ipc.sink.common.AbstractMessageConsumerManager;
import org.opennms.core.rpc.api.RemoteExecutionException;
import org.opennms.core.rpc.api.RequestTimedOutException;
import org.opennms.core.rpc.api.RpcClient;
import org.opennms.core.rpc.api.RpcClientFactory;
import org.opennms.core.rpc.api.RpcModule;
import org.opennms.core.rpc.api.RpcRequest;
import org.opennms.core.rpc.api.RpcResponse;
import org.opennms.core.rpc.api.RpcResponseHandler;
import org.opennms.core.sysprops.SystemProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.ByteString;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

public class OnmsGrpcServer extends AbstractMessageConsumerManager implements RpcClientFactory {

    private static final Logger LOG = LoggerFactory.getLogger(OnmsGrpcServer.class);
    private static final String SERVER_PORT = "org.opennms.core.ipc.grpc.server.port";
    private static final long DEFAULT_TTL = 20000;
    private Server server;
    private String location;
    private final ThreadFactory responseHandlerThreadFactory = new ThreadFactoryBuilder()
            .setNameFormat("rpc-client-response-handler-%d")
            .build();
    private final ThreadFactory timerThreadFactory = new ThreadFactoryBuilder()
            .setNameFormat("rpc-client-timeout-tracker-%d")
            .build();
    private final ExecutorService timerExecutor = Executors.newSingleThreadExecutor(timerThreadFactory);
    private final Map<String, RpcResponseHandler> rpcResponseMap = new ConcurrentHashMap<>();
    private DelayQueue<RpcResponseHandler> delayQueue = new DelayQueue<>();
    private Map<String, StreamObserver<RpcMessage>> rpcObserversByMinionId = new HashMap<>();
    private Multimap<String, StreamObserver<RpcMessage>> rpcObserversByLocation = LinkedListMultimap.create();;
    private Map<Collection<StreamObserver<RpcMessage>>, Iterator<StreamObserver<RpcMessage>>> observerIteratorMap = new ConcurrentHashMap<>();
    private final Map<String, SinkModule<?, Message>> modulesById = new ConcurrentHashMap<>();
    private final ExecutorService responseHandlerExecutor = Executors.newCachedThreadPool(responseHandlerThreadFactory);

    public void start() throws IOException {
        int port = SystemProperties.getInteger(SERVER_PORT, 8990);
        server = ServerBuilder.forPort(port)
                .addService(new OnmsIpcService()).build();
        timerExecutor.execute(this::handleTimeouts);
        server.start();
        LOG.info("OpenNMS gRPC server started");
    }

    public void shutdown() {
        server.shutdown();
        timerExecutor.shutdown();
        responseHandlerExecutor.shutdown();
        LOG.info("OpenNMS gRPC server stopped");
    }

    @Override
    protected void startConsumingForModule(SinkModule<?, Message> module) throws Exception {
        modulesById.putIfAbsent(module.getId(), module);
    }

    @Override
    protected void stopConsumingForModule(SinkModule<?, Message> module) throws Exception {
        modulesById.values().remove(module);
    }

    @Override
    public <S extends RpcRequest, T extends RpcResponse> RpcClient<S, T> getClient(RpcModule<S, T> module) {

        return (S request) -> {
            if (request.getLocation() == null || request.getLocation().equals(location)) {
                // The request is for the current location, invoke it directly
                return module.execute(request);
            }
            String marshalRequest = module.marshalRequest(request);
            String rpcId = UUID.randomUUID().toString();
            CompletableFuture<T> future = new CompletableFuture<T>();
            Long ttl = request.getTimeToLiveMs();
            ttl = (ttl != null && ttl > 0) ? ttl : DEFAULT_TTL;
            long expirationTime = System.currentTimeMillis() + ttl;
            RpcResponseHandlerImpl responseHandler = new RpcResponseHandlerImpl<S, T>(future, module, rpcId, expirationTime);
            rpcResponseMap.put(rpcId, responseHandler);
            delayQueue.offer(responseHandler);
            RpcMessage.Builder builder = RpcMessage.newBuilder()
                    .setRpcId(rpcId)
                    .setLocation(request.getLocation())
                    .setModuleId(module.getId())
                    .setRpcContent(ByteString.copyFrom(marshalRequest.getBytes()));
            if(!Strings.isNullOrEmpty(request.getSystemId())) {
                builder.setSystemId(request.getSystemId());
            }
            sendRequest(builder.build());
            return future;
        };
    }



    private void handleTimeouts() {
        while (true) {
            try {
                RpcResponseHandler responseHandler = delayQueue.take();
                if (!responseHandler.isProcessed()) {
                    LOG.warn("RPC request from module {} with id {} timedout ", responseHandler.getRpcModule().getId(),
                            responseHandler.getRpcId());
                    responseHandlerExecutor.execute(() -> responseHandler.sendResponse(null));
                }
            } catch (InterruptedException e) {
                LOG.info("interrupted while waiting for an element from delayQueue", e);
                break;
            } catch (Exception e) {
                LOG.warn("error while sending response from timeout handler", e);
            }
        }
    }


    private void handleResponse(RpcMessage rpcMessage) {
        // Handle response from the Minion.
        LOG.debug("Received message from at location {} for module {}", rpcMessage.getLocation(), rpcMessage.getModuleId());
        RpcResponseHandler responseHandler = rpcResponseMap.get(rpcMessage.getRpcId());
        if(responseHandler != null) {
            responseHandler.sendResponse(rpcMessage.getRpcContent().toStringUtf8());
        }
    }

    public void sendRequest(RpcMessage rpcMessage) {
        StreamObserver<RpcMessage> responseObserver = null;
        if (!Strings.isNullOrEmpty(rpcMessage.getSystemId())) {
            responseObserver = rpcObserversByMinionId.get(rpcMessage.getSystemId());
        } else {
            responseObserver = getResponseObserver(rpcMessage.getLocation());
        }
        if (responseObserver != null) {
            try {
                responseObserver.onNext(rpcMessage);
                LOG.debug("Request for module {} being sent", rpcMessage.getModuleId());
            } catch (Throwable e) {
                LOG.error("Encountered exception while sending request {}", rpcMessage, e);
                removeResponseObserver(responseObserver);
            }
        } else {
            LOG.error("No minion found at location {}", rpcMessage.getLocation());
        }
    }

    private StreamObserver<RpcMessage> getResponseObserver(String location) {
        Collection<StreamObserver<RpcMessage>> streamObservers = rpcObserversByLocation.get(location);
        if (streamObservers.isEmpty()) {
            LOG.debug("No RPC observers found for location {}", location);
            return null;
        }
        Iterator<StreamObserver<RpcMessage>> iterator = observerIteratorMap.get(streamObservers);
        if (iterator == null) {
            iterator = Iterables.cycle(streamObservers).iterator();
            observerIteratorMap.put(streamObservers, iterator);
        }
        return iterator.next();
    }

    private synchronized void addResponseObserver(String location, String systemId, StreamObserver<RpcMessage> responseObserver) {
        if (!rpcObserversByLocation.containsValue(responseObserver)) {
            rpcObserversByLocation.put(location, responseObserver);
            rpcObserversByMinionId.put(systemId, responseObserver);
            LOG.debug("Added rpc observer for minion {} at location {}", systemId, location);
        }
    }

    private synchronized void removeResponseObserver(StreamObserver<RpcMessage> responseObserver) {
        rpcObserversByLocation.values().remove(responseObserver);
    }

    /**
     * Handle metadata from minion.
     */
    private boolean isMetadata(RpcMessage rpcMessage) {
        String systemId = rpcMessage.getSystemId();
        String rpcId = rpcMessage.getRpcId();
        return !Strings.isNullOrEmpty(systemId) &&
                !Strings.isNullOrEmpty(rpcId) &&
                systemId.equals(rpcId);
    }

    Server getServer() {
        return server;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    private class OnmsIpcService extends OnmsIpcGrpc.OnmsIpcImplBase {

        @Override
        public StreamObserver<RpcMessage> rpcStreaming(
                StreamObserver<RpcMessage> responseObserver) {

            return new StreamObserver<RpcMessage>() {

                @Override
                public void onNext(RpcMessage rpcMessage) {
                    // Register client when message is metadata.
                    LOG.debug("Received RPC message from module {}", rpcMessage.getModuleId());
                    if (isMetadata(rpcMessage)) {
                        addResponseObserver(rpcMessage.getLocation(), rpcMessage.getSystemId(), responseObserver);
                    }
                    responseHandlerExecutor.execute(() -> handleResponse(rpcMessage));
                }

                @Override
                public void onError(Throwable throwable) {
                    LOG.error("Error in rpc streaming", throwable);
                    removeResponseObserver(responseObserver);
                }

                @Override
                public void onCompleted() {
                    removeResponseObserver(responseObserver);
                }
            };
        }

        public io.grpc.stub.StreamObserver<SinkMessage> sinkStreaming(
                io.grpc.stub.StreamObserver<Empty> responseObserver) {

            return new StreamObserver<SinkMessage>() {

                @Override
                public void onNext(SinkMessage sinkMessage) {
                    LOG.debug("Received sink message from module {}", sinkMessage.getModuleId());
                    SinkModule<?, Message> sinkModule = modulesById.get(sinkMessage.getModuleId());
                    if(sinkModule != null) {
                        Message message = sinkModule.unmarshal(sinkMessage.getContent().toByteArray());
                        dispatch(sinkModule, message);
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    LOG.error("Error in sink streaming", throwable);
                }

                @Override
                public void onCompleted() {

                }
            };
        }
    }

    private class RpcResponseHandlerImpl<S extends RpcRequest, T extends RpcResponse> implements RpcResponseHandler {

        private final CompletableFuture<T> responseFuture;
        private final RpcModule<S, T> rpcModule;
        private final String rpcId;
        private final long expirationTime;
        private boolean isProcessed = false;

        private RpcResponseHandlerImpl(CompletableFuture<T> responseFuture, RpcModule<S, T> rpcModule, String rpcId,
                                      long timeout) {
            this.responseFuture = responseFuture;
            this.rpcModule = rpcModule;
            this.rpcId = rpcId;
            this.expirationTime = timeout;
        }

        @Override
        public void sendResponse(String message) {

            if (message != null) {
                T response = rpcModule.unmarshalResponse(message);
                if (response.getErrorMessage() != null) {
                    responseFuture.completeExceptionally(new RemoteExecutionException(response.getErrorMessage()));
                } else {
                    LOG.debug("Response handled successfully");
                    responseFuture.complete(response);
                }
                isProcessed = true;
            } else {
                responseFuture.completeExceptionally(new RequestTimedOutException(new TimeoutException()));
            }
            rpcResponseMap.remove(rpcId);
        }

        @Override
        public boolean isProcessed() {
            return isProcessed;
        }

        @Override
        public String getRpcId() {
            return rpcId;
        }


        @Override
        public int compareTo(Delayed other) {
            long myDelay = getDelay(TimeUnit.MILLISECONDS);
            long otherDelay = other.getDelay(TimeUnit.MILLISECONDS);
            return Long.compare(myDelay, otherDelay);
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long now = System.currentTimeMillis();
            return unit.convert(expirationTime - now, TimeUnit.MILLISECONDS);
        }

        public RpcModule<S, T> getRpcModule() {
            return rpcModule;
        }
    }

}
