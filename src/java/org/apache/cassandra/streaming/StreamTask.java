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
package org.apache.cassandra.streaming;

import org.apache.cassandra.streaming.messages.StreamSummary;
import org.apache.cassandra.utils.CFPath;

/**
 * StreamTask is an abstraction of the streaming task performed over
 * specific keyspace and ranges.
 */
public abstract class StreamTask
{
    /** StreamSession that this task belongs */
    protected final StreamSession session;

    protected final CFPath path;

    protected StreamTask(StreamSession session, CFPath path)
    {
        this.session = session;
        this.path = path;
    }

    /**
     * @return total number of files this task receives/streams.
     */
    public abstract int getTotalNumberOfFiles();

    /**
     * @return total bytes expected to receive
     */
    public abstract long getTotalSize();

    /**
     * @return StreamSummary that describes this task
     */
    public StreamSummary getSummary()
    {
        return new StreamSummary(path, getTotalNumberOfFiles(), getTotalSize());
    }
}
