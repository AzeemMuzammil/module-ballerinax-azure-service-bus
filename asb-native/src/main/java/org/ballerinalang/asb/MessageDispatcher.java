/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinalang.asb;

import com.microsoft.azure.servicebus.*;
import org.ballerinalang.jvm.api.BStringUtils;
import org.ballerinalang.jvm.api.BValueCreator;
import org.ballerinalang.jvm.api.values.*;
import org.ballerinalang.jvm.scheduling.StrandMetadata;
import org.ballerinalang.jvm.types.AnnotatableType;
import org.ballerinalang.jvm.types.BType;
import org.ballerinalang.jvm.types.AttachedFunction;
import org.ballerinalang.jvm.runtime.AsyncFunctionCallback;
import org.ballerinalang.jvm.api.BRuntime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.ballerinalang.asb.ASBConstants.*;
import static org.ballerinalang.asb.connection.ListenerUtils.isClosing;

/**
 * Handles and dispatched messages with data binding.
 */
public class MessageDispatcher {
    private static final Logger LOG = Logger.getLogger(MessageDispatcher.class.getName());

    private BObject service;
    private String queueName;
    private String connectionKey;
    private BRuntime runtime;
    private IMessageReceiver receiver;
    private static final StrandMetadata ON_MESSAGE_METADATA = new StrandMetadata(ORG_NAME, ASB,
            ASB_VERSION, FUNC_ON_MESSAGE);

    /**
     * Initialize the Message Dispatcher.
     *
     * @param service Ballerina service instance.
     * @param runtime Ballerina runtime instance.
     * @param iMessageReceiver Asb MessageReceiver instance.
     */
    public MessageDispatcher(BObject service, BRuntime runtime, IMessageReceiver iMessageReceiver) {
        this.service = service;
        this.queueName = getQueueNameFromConfig(service);
        this.connectionKey = getConnectionStringFromConfig(service);
        this.runtime = runtime;
        this.receiver = iMessageReceiver;
    }

    /**
     * Get queue name from annotation configuration attached to the service.
     *
     * @param service Ballerina service instance.
     * @return Queue name from annotation configuration attached to the service.
     */
    public static String getQueueNameFromConfig(BObject service) {
        BMap serviceConfig = (BMap) ((AnnotatableType) service.getType())
                .getAnnotation(BStringUtils.fromString(ASBConstants.PACKAGE_RABBITMQ_FQN + ":"
                        + ASBConstants.SERVICE_CONFIG));
        @SuppressWarnings(ASBConstants.UNCHECKED)
        BMap<BString, Object> queueConfig =
                (BMap) serviceConfig.getMapValue(ASBConstants.ALIAS_QUEUE_CONFIG);
        return queueConfig.getStringValue(ASBConstants.QUEUE_NAME).getValue();
    }

    /**
     * Get connection string from annotation configuration attached to the service.
     *
     * @param service Ballerina service instance.
     * @return Connection string from annotation configuration attached to the service.
     */
    public static String getConnectionStringFromConfig(BObject service) {
        BMap serviceConfig = (BMap) ((AnnotatableType) service.getType())
                .getAnnotation(BStringUtils.fromString(ASBConstants.PACKAGE_RABBITMQ_FQN + ":"
                        + ASBConstants.SERVICE_CONFIG));
        @SuppressWarnings(ASBConstants.UNCHECKED)
        BMap<BString, Object> queueConfig =
                (BMap) serviceConfig.getMapValue(ASBConstants.ALIAS_QUEUE_CONFIG);
        return queueConfig.getStringValue(CONNECTION_STRING).getValue();
    }

    /**
     * Start receiving messages asynchronously and dispatch the messages to the attached service.
     *
     * @param listener Ballerina listener object.
     */
    public void receiveMessages(BObject listener) {
        LOG.info("Consumer service started for queue " + queueName);

        try {
            ExecutorService executorService = Executors.newSingleThreadExecutor();
            this.pumpMessage(receiver, executorService);
            LOG.info("\tDone receiving messages from \n" + receiver.getEntityPath());
        } catch (Exception e) {
            ASBUtils.returnErrorValue(e.getMessage());
        }

        ArrayList<BObject> startedServices =
                (ArrayList<BObject>) listener.getNativeData(ASBConstants.STARTED_SERVICES);
        startedServices.add(service);
        service.addNativeData(ASBConstants.QUEUE_NAME.getValue(), queueName);
    }

    /**
     * Asynchronously pump messages from the Azure service bus.
     *
     * @param receiver Ballerina listener object.
     * @param executorService Thread executor for processing the messages.
     */
    public void pumpMessage(IMessageReceiver receiver, ExecutorService executorService) {
        if(isClosing()) {
            CompletableFuture<IMessage> receiveMessageFuture = receiver.receiveAsync(Duration.ofSeconds(5));
            LOG.info("\n\tWaiting up to 5 seconds for messages from  ...\n" + receiver.getEntityPath());

            receiveMessageFuture.handleAsync((message, receiveEx) -> {
                if (receiveEx != null) {
                    LOG.info("Receiving message from entity failed.");
                    pumpMessage(receiver, executorService);
                } else if (message == null) {
                    LOG.info("Receive from entity returned no messages.");
                    pumpMessage(receiver, executorService);
                } else {
                    LOG.info("\t<= Received a message with messageId \n" + message.getMessageId());
                    LOG.info("\t<= Received a message with messageBody \n" + new String(message.getBody(), UTF_8));
                    handleDispatch(message.getBody());
                    try {
                        receiver.complete(message.getLockToken());
                    } catch (Exception e) {
                        return ASBUtils.returnErrorValue(e.getMessage());
                    }
                    pumpMessage(receiver, executorService);
                    return null;
                }
                return null;
            }, executorService);
        }
    }

    /**
     * Handle the dispatch of message to the service.
     *
     * @param message Received azure service bus message instance.
     */
    private void handleDispatch(byte[] message) {
        AttachedFunction[] attachedFunctions = service.getType().getAttachedFunctions();
        AttachedFunction onMessageFunction;
        if (FUNC_ON_MESSAGE.equals(attachedFunctions[0].getName())) {
            onMessageFunction = attachedFunctions[0];
        } else {
            return;
        }
        BType[] paramTypes = onMessageFunction.getParameterType();
        int paramSize = paramTypes.length;
        dispatchMessage(message);
    }

    /**
     * Dispatch message to the service.
     *
     * @param message Received azure service bus message instance.
     */
    private void dispatchMessage(byte[] message) {
        try {
            AsyncFunctionCallback callback = new ASBResourceCallback();
            BObject messageBObject = getMessageBObject(message);
            executeResourceOnMessage(callback, messageBObject, true);
        } catch (BError exception) {
            ASBUtils.returnErrorValue("Error occur while dispatching the message to the service " +
                    exception.getMessage());
        }
    }

    /**
     * Get the ballerina Message object from the azure service bus message object.
     *
     * @param message Received azure service bus message instance.
     */
    private BObject getMessageBObject(byte[] message)  {
        LOG.info("\t<= Received a message with messageBody \n" + new String(message, UTF_8));
        BObject messageBObject = BValueCreator.createObjectValue(ASBConstants.PACKAGE_ID_ASB,
                ASBConstants.MESSAGE_OBJECT);
        messageBObject.set(ASBConstants.MESSAGE_CONTENT, BValueCreator.createArrayValue(message));

        return messageBObject;
    }

    private void executeResourceOnMessage(AsyncFunctionCallback callback, Object... args) {
        executeResource(ASBConstants.FUNC_ON_MESSAGE, callback, ON_MESSAGE_METADATA, args);
    }

    private void executeResource(String function, AsyncFunctionCallback callback, StrandMetadata metaData,
                                 Object... args) {
        runtime.invokeMethodAsync(service, function, null, metaData, callback,args);
    }
}
