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
package org.apache.qpid.server.message;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public abstract class MessageReference<M extends ServerMessage>
{

    private static final AtomicReferenceFieldUpdater<MessageReference, ServerMessage> _messageUpdater =
            AtomicReferenceFieldUpdater.newUpdater(MessageReference.class, ServerMessage.class,"_message");

    private volatile M _message;

    public MessageReference(M message)
    {
        _message = message;
        onReference();
    }

    abstract protected void onReference();

    abstract protected void onRelease();

    public M getMessage()
    {
        return _message;
    }

    public void release()
    {
        ServerMessage message = _messageUpdater.getAndSet(this,null);
        if(message != null)
        {
            onRelease();
        }
    }

    @Override
    protected void finalize() throws Throwable
    {
        if(_message != null)
        {
            onRelease();
            _message = null;
        }
        super.finalize();    //To change body of overridden methods use File | Settings | File Templates.
    }
}
