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
package org.apache.qpid.server.store.berkeleydb.tuples;

import org.apache.qpid.server.store.berkeleydb.records.QueueRecord;

import com.sleepycat.bind.tuple.TupleBinding;

public class QueueTupleBindingFactory extends TupleBindingFactory<QueueRecord>
{

    public QueueTupleBindingFactory(int version)
    {
        super(version);
    }

    public TupleBinding<QueueRecord> getInstance()
    {
        switch (_version)
        {
            default:
            case 5:
                return new QueueTuple_5();
            case 4:
                return new QueueTuple_4();
        }
    }
}