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

import java.util.ArrayList;
import java.util.Collection;

import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Table;
import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.utils.CFPath;

/**
 * Task that manages receiving files for the session for certain keyspace, ranges, and columnfamily.
 */
public class StreamReceiveTask extends StreamTask
{
    private final int totalFiles;
    private final long totalSize;

    //  holds SSTables received
    protected Collection<SSTableReader> sstables;

    public StreamReceiveTask(StreamSession session, CFPath path, int totalFiles, long totalSize)
    {
        super(session, path);
        this.totalFiles = totalFiles;
        this.totalSize = totalSize;
        this.sstables =  new ArrayList<>(totalFiles);
    }

    /**
     * Receive file.
     *
     * @param sstable
     */
    public void receive(SSTableReader sstable)
    {
        assert path.keyspace().equals(sstable.descriptor.ksname) && path.columnFamily().equals(sstable.getColumnFamilyName());

        sstables.add(sstable);
        if (sstables.size() == totalFiles)
            complete();
    }

    public int getTotalNumberOfFiles()
    {
        return totalFiles;
    }

    public long getTotalSize()
    {
        return totalSize;
    }

    // TODO should be run in background so that this does not block streaming
    private void complete()
    {
        if (!SSTableReader.acquireReferences(sstables))
            throw new AssertionError("We shouldn't fail acquiring a reference on a sstable that has just been transferred");
        try
        {
            ColumnFamilyStore cfs = Table.open(path.keyspace()).getColumnFamilyStore(path.columnFamily());
            // add sstables and build secondary indexes
            cfs.addSSTables(sstables);
            cfs.indexManager.maybeBuildSecondaryIndexes(sstables, cfs.indexManager.allIndexesNames());
        }
        finally
        {
            SSTableReader.releaseReferences(sstables);
        }

        session.taskCompleted(this);
    }
}
