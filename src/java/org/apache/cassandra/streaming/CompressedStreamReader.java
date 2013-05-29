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

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

import com.google.common.base.Throwables;

import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Directories;
import org.apache.cassandra.db.Table;
import org.apache.cassandra.io.compress.CompressionMetadata;
import org.apache.cassandra.io.sstable.Component;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.io.sstable.SSTableWriter;
import org.apache.cassandra.streaming.compress.CompressedInputStream;
import org.apache.cassandra.streaming.compress.CompressionInfo;
import org.apache.cassandra.streaming.messages.FileMessageHeader;
import org.apache.cassandra.streaming.messages.StreamMessageListener;
import org.apache.cassandra.utils.BytesReadTracker;
import org.apache.cassandra.utils.Pair;

/**
 * StreamReader that reads from streamed compressed SSTable
 */
public class CompressedStreamReader extends StreamReader
{
    protected final CompressionInfo compressionInfo;

    public CompressedStreamReader(FileMessageHeader header, StreamMessageListener listener)
    {
        super(header, listener);
        this.compressionInfo = header.compressionInfo;
    }

    /**
     * @return SSTable transferred
     * @throws java.io.IOException if reading the remote sstable fails. Will throw an RTE if local write fails.
     */
    @Override
    public SSTableReader read(ReadableByteChannel channel) throws IOException
    {
        CompressedInputStream cis = new CompressedInputStream(Channels.newInputStream(channel), compressionInfo);
        long size = totalSize();

        ColumnFamilyStore cfs = Table.open(path.keyspace()).getColumnFamilyStore(path.columnFamily());
        Directories.DataDirectory localDir = cfs.directories.getLocationCapableOfSize(size);
        if (localDir == null)
            throw new IOException("Insufficient disk space to store " + size + " bytes");
        desc = Descriptor.fromFilename(cfs.getTempSSTablePath(cfs.directories.getLocationForDisk(localDir)));

        SSTableWriter writer = new SSTableWriter(desc.filenameFor(Component.DATA), estimatedKeys);
        try
        {
            BytesReadTracker in = new BytesReadTracker(new DataInputStream(cis));

            for (Pair<Long, Long> section : sections)
            {
                long length = section.right - section.left;
                // skip to beginning of section inside chunk
                cis.position(section.left);
                in.reset(0);
                while (in.getBytesRead() < length)
                {
                    writeRow(writer, in, cfs);
                    // when compressed, report total bytes of compressed chunks read since remoteFile.size is the sum of chunks transferred
                    maybeFireProgressEvent(desc, cis.getTotalCompressedBytesRead());
                }
            }
            return writer.closeAndOpenReader();
        }
        catch (Throwable e)
        {
            writer.abort();
            if (e instanceof IOException)
                throw (IOException) e;
            else
                throw Throwables.propagate(e);
        }
    }

    @Override
    protected long totalSize()
    {
        long size = 0;
        // calculate total length of transferring chunks
        for (CompressionMetadata.Chunk chunk : compressionInfo.chunks)
            size += chunk.length + 4; // 4 bytes for CRC
        return size;
    }
}
