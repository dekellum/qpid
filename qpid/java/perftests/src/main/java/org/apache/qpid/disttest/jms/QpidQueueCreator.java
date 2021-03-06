/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.qpid.disttest.jms;

import java.util.List;

import javax.jms.Session;
import org.apache.qpid.client.AMQDestination;
import org.apache.qpid.client.AMQSession;
import org.apache.qpid.disttest.DistributedTestException;
import org.apache.qpid.disttest.controller.config.QueueConfig;
import org.apache.qpid.framing.FieldTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class QpidQueueCreator implements QueueCreator
{
    private static final Logger LOGGER = LoggerFactory.getLogger(QpidQueueCreator.class);
    private static final FieldTable EMPTY_QUEUE_BIND_ARGUMENTS = new FieldTable();

    @Override
    public void createQueues(Session session, List<QueueConfig> configs)
    {
        AMQSession<?, ?> amqSession = (AMQSession<?, ?>)session;
        for (QueueConfig queueConfig : configs)
        {
            createQueue(amqSession, queueConfig);
        }
    }

    @Override
    public void deleteQueues(Session session, List<QueueConfig> configs)
    {
        AMQSession<?, ?> amqSession = (AMQSession<?, ?>)session;
        for (QueueConfig queueConfig : configs)
        {
            deleteQueue(amqSession, queueConfig);
        }
    }

    private void createQueue(AMQSession<?, ?> session, QueueConfig queueConfig)
    {
        try
        {
            AMQDestination destination = (AMQDestination) session.createQueue(queueConfig.getName());
            boolean autoDelete = false;
            boolean exclusive = false;
            session.createQueue(destination.getAMQQueueName(), autoDelete,
                    queueConfig.isDurable(), exclusive, queueConfig.getAttributes());
            session.bindQueue(destination.getAMQQueueName(), destination.getRoutingKey(),
                    EMPTY_QUEUE_BIND_ARGUMENTS, destination.getExchangeName(),
                    destination, autoDelete);

            LOGGER.debug("Created queue " + queueConfig);
        }
        catch (Exception e)
        {
            throw new DistributedTestException("Failed to create queue:" + queueConfig, e);
        }
    }

    private void deleteQueue(AMQSession<?, ?> session, QueueConfig queueConfig)
    {
        try
        {
            // The Qpid AMQSession API currently makes the #deleteQueue method protected and the
            // raw protocol method public.  This should be changed then we should switch the below to
            // use #deleteQueue.
            AMQDestination destination = (AMQDestination) session.createQueue(queueConfig.getName());
            session.sendQueueDelete(destination.getAMQQueueName());
            LOGGER.debug("Deleted queue " + queueConfig.getName());
        }
        catch (Exception e)
        {
            throw new DistributedTestException("Failed to delete queue:" + queueConfig.getName(), e);
        }
    }
}
