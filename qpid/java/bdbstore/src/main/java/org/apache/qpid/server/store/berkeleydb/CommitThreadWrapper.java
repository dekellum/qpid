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
package org.apache.qpid.server.store.berkeleydb;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.Logger;
import org.apache.qpid.server.store.StoreFuture;

import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.Transaction;

public class CommitThreadWrapper
{
    private final CommitThread _commitThread;
    
    public CommitThreadWrapper(String name, Environment env)
    {
        _commitThread = new CommitThread(name, env);
    }

    public void startCommitThread()
    {
        _commitThread.start();
    }

    public void stopCommitThread() throws InterruptedException
    {
        _commitThread.close();
        _commitThread.join();
    }

    public StoreFuture commit(Transaction tx, boolean syncCommit)
    {
        BDBCommitFuture commitFuture = new BDBCommitFuture(_commitThread, tx, syncCommit);
        commitFuture.commit();
        return commitFuture;
    }

    private static final class BDBCommitFuture implements StoreFuture
    {
        private static final Logger LOGGER = Logger.getLogger(BDBCommitFuture.class);

        private final CommitThread _commitThread;
        private final Transaction _tx;
        private DatabaseException _databaseException;
        private boolean _complete;
        private boolean _syncCommit;

        public BDBCommitFuture(CommitThread commitThread, Transaction tx, boolean syncCommit)
        {
            _commitThread = commitThread;
            _tx = tx;
            _syncCommit = syncCommit;
        }

        public synchronized void complete()
        {
            if (LOGGER.isDebugEnabled())
            {
                LOGGER.debug("public synchronized void complete(): called (Transaction = " + _tx + ")");
            }
            _complete = true;

            notifyAll();
        }

        public synchronized void abort(DatabaseException databaseException)
        {
            _complete = true;
            _databaseException = databaseException;

            notifyAll();
        }

        public void commit() throws DatabaseException
        {
            _commitThread.addJob(this, _syncCommit);

            if(!_syncCommit)
            {
                LOGGER.debug("CommitAsync was requested, returning immediately.");
                return;
            }

            waitForCompletion();

            if (_databaseException != null)
            {
                throw _databaseException;
            }

        }

        public synchronized boolean isComplete()
        {
            return _complete;
        }

        public synchronized void waitForCompletion()
        {
            while (!isComplete())
            {
                _commitThread.explicitNotify();
                try
                {
                    wait(250);
                }
                catch (InterruptedException e)
                {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    /**
     * Implements a thread which batches and commits a queue of {@link BDBCommitFuture} operations. The commit operations
     * themselves are responsible for adding themselves to the queue and waiting for the commit to happen before
     * continuing, but it is the responsibility of this thread to tell the commit operations when they have been
     * completed by calling back on their {@link BDBCommitFuture#complete()} and {@link BDBCommitFuture#abort} methods.
     *
     * <p/><table id="crc"><caption>CRC Card</caption> <tr><th> Responsibilities <th> Collaborations </table>
     */
    private static class CommitThread extends Thread
    {
        private static final Logger LOGGER = Logger.getLogger(CommitThread.class);

        private final AtomicBoolean _stopped = new AtomicBoolean(false);
        private final Queue<BDBCommitFuture> _jobQueue = new ConcurrentLinkedQueue<BDBCommitFuture>();
        private final CheckpointConfig _config = new CheckpointConfig();
        private final Object _lock = new Object();
        private Environment _environment;

        public CommitThread(String name, Environment env)
        {
            super(name);
            _config.setForce(true);
            _environment = env;
        }

        public void explicitNotify()
        {
            synchronized (_lock)
            {
                _lock.notify();
            }
        }

        public void run()
        {
            while (!_stopped.get())
            {
                synchronized (_lock)
                {
                    while (!_stopped.get() && !hasJobs())
                    {
                        try
                        {
                            // RHM-7 Periodically wake up and check, just in case we
                            // missed a notification. Don't want to lock the broker hard.
                            _lock.wait(1000);
                        }
                        catch (InterruptedException e)
                        {
                        }
                    }
                }
                processJobs();
            }
        }

        private void processJobs()
        {
            int size = _jobQueue.size();

            try
            {
                _environment.flushLog(true);

                for(int i = 0; i < size; i++)
                {
                    BDBCommitFuture commit = _jobQueue.poll();
                    commit.complete();
                }

            }
            catch (DatabaseException e)
            {
                try
                {
                    LOGGER.error("Exception during environment log flush", e);

                    for(int i = 0; i < size; i++)
                    {
                        BDBCommitFuture commit = _jobQueue.poll();
                        commit.abort(e);
                    }
                }
                finally
                {
                    LOGGER.error("Closing store environment", e);

                    try
                    {
                        _environment.close();
                    }
                    catch (DatabaseException ex)
                    {
                        LOGGER.error("Exception closing store environment", ex);
                    }
                }
            }
        }

        private boolean hasJobs()
        {
            return !_jobQueue.isEmpty();
        }

        public void addJob(BDBCommitFuture commit, final boolean sync)
        {

            _jobQueue.add(commit);
            if(sync)
            {
                synchronized (_lock)
                {
                    _lock.notifyAll();
                }
            }
        }

        public void close()
        {
            synchronized (_lock)
            {
                _stopped.set(true);
                _lock.notifyAll();
            }
        }
    }
}
