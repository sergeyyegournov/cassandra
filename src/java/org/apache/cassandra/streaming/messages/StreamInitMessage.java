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
package org.apache.cassandra.streaming.messages;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;

import org.apache.cassandra.db.TypeSizes;
import org.apache.cassandra.io.IVersionedSerializer;
import org.apache.cassandra.io.util.DataOutputBuffer;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.streaming.OperationType;
import org.apache.cassandra.utils.UUIDSerializer;

/**
 * StreamInitMessage is first sent from the node where StreamOperation is started,
 * to initialize corresponding StreamSession on the other side.
 */
public class StreamInitMessage
{
    public static IVersionedSerializer<StreamInitMessage> serializer = new StreamInitMessageSerializer();

    /** Stream operation ID */
    public final UUID operationId;
    public final OperationType operationType;

    public StreamInitMessage(UUID operationId, OperationType operationType)
    {
        this.operationId = operationId;
        this.operationType = operationType;
    }

    public ByteBuffer createMessage(boolean compress, int version)
    {
        int header = 0;
        // set compression bit.
        if (compress)
            header |= 4;
        // set streaming bit
        header |= 8;
        // Setting up the version bit
        header |= (version << 8);

        /* Adding the StreamHeader which contains the session Id along
         * with the pendingfile info for the stream.
         * | Session Id | Pending File Size | Pending File | Bool more files |
         * | No. of Pending files | Pending Files ... |
         */
        byte[] bytes;
        try
        {
            int size = (int)StreamInitMessage.serializer.serializedSize(this, version);
            DataOutputBuffer buffer = new DataOutputBuffer(size);
            StreamInitMessage.serializer.serialize(this, buffer, version);
            bytes = buffer.getData();
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
        assert bytes.length > 0;

        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + bytes.length);
        buffer.putInt(MessagingService.PROTOCOL_MAGIC);
        buffer.putInt(header);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }

    private static class StreamInitMessageSerializer implements IVersionedSerializer<StreamInitMessage>
    {
        public void serialize(StreamInitMessage message, DataOutput out, int version) throws IOException
        {
            UUIDSerializer.serializer.serialize(message.operationId, out, version);
            out.writeInt(message.operationType.ordinal());
        }

        public StreamInitMessage deserialize(DataInput in, int version) throws IOException
        {
            UUID opId = UUIDSerializer.serializer.deserialize(in, version);
            int type = in.readInt();
            OperationType operationType = OperationType.values()[type];
            return new StreamInitMessage(opId, operationType);
        }

        public long serializedSize(StreamInitMessage message, int version)
        {
            long size = UUIDSerializer.serializer.serializedSize(message.operationId, version);
            size += TypeSizes.NATIVE.sizeof(message.operationType.ordinal());
            return size;
        }
    }
}
