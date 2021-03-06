/*
 *
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
 *
 */

package org.apache.qpid.ra;

import java.io.Serializable;

import javax.jms.ConnectionFactory;
import javax.jms.QueueConnectionFactory;
import javax.jms.TopicConnectionFactory;
import javax.jms.XAConnectionFactory;
import javax.jms.XAQueueConnectionFactory;
import javax.jms.XATopicConnectionFactory;
import javax.resource.Referenceable;

/**
 * An aggregate interface for the JMS connection factories
 *
 */
public interface QpidRAConnectionFactory extends ConnectionFactory, TopicConnectionFactory, QueueConnectionFactory,
         XAConnectionFactory, XAQueueConnectionFactory, XATopicConnectionFactory, Serializable, Referenceable
{
   /** Connection factory capable of handling connections */
   public static final int CONNECTION = 0;

   /** Connection factory capable of handling queues */
   public static final int QUEUE_CONNECTION = 1;

   /** Connection factory capable of handling topics */
   public static final int TOPIC_CONNECTION = 2;

   /** Connection factory capable of handling XA connections */
   public static final int XA_CONNECTION = 3;

   /** Connection factory capable of handling XA queues */
   public static final int XA_QUEUE_CONNECTION = 4;

   /** Connection factory capable of handling XA topics */
   public static final int XA_TOPIC_CONNECTION = 5;
}
