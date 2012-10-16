package org.apache.cassandra.db.compaction;
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


import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.base.Joiner;
import com.google.common.collect.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.columniterator.IColumnIterator;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.io.sstable.SSTable;
import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.io.sstable.SSTableScanner;
import org.apache.cassandra.notifications.INotification;
import org.apache.cassandra.notifications.INotificationConsumer;
import org.apache.cassandra.notifications.SSTableAddedNotification;
import org.apache.cassandra.notifications.SSTableListChangedNotification;

public class LeveledCompactionStrategy extends AbstractCompactionStrategy implements INotificationConsumer
{
    private static final Logger logger = LoggerFactory.getLogger(LeveledCompactionStrategy.class);

    private final LeveledManifest manifest;
    private final String SSTABLE_SIZE_OPTION = "sstable_size_in_mb";
    private final int maxSSTableSizeInMB;
    private final AtomicReference<LeveledCompactionTask> task = new AtomicReference<LeveledCompactionTask>();

    public LeveledCompactionStrategy(ColumnFamilyStore cfs, Map<String, String> options)
    {
        super(cfs, options);
        int configuredMaxSSTableSize = 5;
        if (options != null)
        {
            String value = options.containsKey(SSTABLE_SIZE_OPTION) ? options.get(SSTABLE_SIZE_OPTION) : null;
            if (null != value)
            {
                try
                {
                    configuredMaxSSTableSize = Integer.parseInt(value);
                }
                catch (NumberFormatException ex)
                {
                    logger.warn(String.format("%s is not a parsable int (base10) for %s using default value",
                                              value, SSTABLE_SIZE_OPTION));
                }
            }
        }
        maxSSTableSizeInMB = configuredMaxSSTableSize;

        cfs.getDataTracker().subscribe(this);
        logger.debug("{} subscribed to the data tracker.", this);

        manifest = LeveledManifest.create(cfs, this.maxSSTableSizeInMB);
        logger.debug("Created {}", manifest);
    }

    public void shutdown()
    {
        super.shutdown();
        cfs.getDataTracker().unsubscribe(this);
    }

    public int getLevelSize(int i)
    {
        return manifest.getLevelSize(i);
    }

    /**
     * the only difference between background and maximal in LCS is that maximal is still allowed
     * (by explicit user request) even when compaction is disabled.
     */
    public AbstractCompactionTask getNextBackgroundTask(int gcBefore)
    {
        if (cfs.isCompactionDisabled())
            return null;

        return getMaximalTask(gcBefore);
    }

    public AbstractCompactionTask getMaximalTask(int gcBefore)
    {
        LeveledCompactionTask currentTask = task.get();
        if (currentTask != null && !currentTask.isDone())
        {
            logger.debug("Compaction still in progress for {}", this);
            return null;
        }

        Collection<SSTableReader> sstables = manifest.getCompactionCandidates();
        if (sstables.isEmpty())
        {
            logger.debug("No compaction necessary for {}", this);
            return null;
        }

        LeveledCompactionTask newTask = new LeveledCompactionTask(cfs, sstables, gcBefore, this.maxSSTableSizeInMB);
        return task.compareAndSet(currentTask, newTask)
               ? newTask
               : null;
    }

    public AbstractCompactionTask getUserDefinedTask(Collection<SSTableReader> sstables, int gcBefore)
    {
        throw new UnsupportedOperationException("LevelDB compaction strategy does not allow user-specified compactions");
    }

    public int getEstimatedRemainingTasks()
    {
        return manifest.getEstimatedTasks();
    }

    public void handleNotification(INotification notification, Object sender)
    {
        if (notification instanceof SSTableAddedNotification)
        {
            SSTableAddedNotification flushedNotification = (SSTableAddedNotification) notification;
            manifest.add(flushedNotification.added);
        }
        else if (notification instanceof SSTableListChangedNotification)
        {
            SSTableListChangedNotification listChangedNotification = (SSTableListChangedNotification) notification;
            switch (listChangedNotification.compactionType)
            {
                // Cleanup, scrub and updateSSTable shouldn't promote (see #3989)
                case CLEANUP:
                case SCRUB:
                case UPGRADE_SSTABLES:
                    manifest.replace(listChangedNotification.removed, listChangedNotification.added);
                    break;
                default:
                    manifest.promote(listChangedNotification.removed, listChangedNotification.added);
                    break;
            }
        }
    }

    public long getMaxSSTableSize()
    {
        return maxSSTableSizeInMB * 1024L * 1024L;
    }

    public boolean isKeyExistenceExpensive(Set<? extends SSTable> sstablesToIgnore)
    {
        Set<SSTableReader> L0 = ImmutableSet.copyOf(manifest.getLevel(0));
        return Sets.difference(L0, sstablesToIgnore).size() + manifest.getLevelCount() > 20;
    }

    public List<ICompactionScanner> getScanners(Collection<SSTableReader> sstables, Range<Token> range) throws IOException
    {
        Multimap<Integer, SSTableReader> byLevel = ArrayListMultimap.create();
        for (SSTableReader sstable : sstables)
            byLevel.get(manifest.levelOf(sstable)).add(sstable);

        List<ICompactionScanner> scanners = new ArrayList<ICompactionScanner>(sstables.size());
        for (Integer level : byLevel.keySet())
        {
            // level can be -1 when sstables are added to DataTracker but not to LeveledManifest
            // since we don't know which level those sstable belong yet, we simply do the same as L0 sstables.
            if (level <= 0)
            {
                // L0 makes no guarantees about overlapping-ness.  Just create a direct scanner for each
                for (SSTableReader sstable : byLevel.get(level))
                    scanners.add(sstable.getDirectScanner(range));
            }
            else
            {
                // Create a LeveledScanner that only opens one sstable at a time, in sorted order
                scanners.add(new LeveledScanner(byLevel.get(level), range));
            }
        }

        return scanners;
    }

    // Lazily creates SSTableBoundedScanner for sstable that are assumed to be from the
    // same level (e.g. non overlapping) - see #4142
    private static class LeveledScanner extends AbstractIterator<IColumnIterator> implements ICompactionScanner
    {
        private final Range<Token> range;
        private final List<SSTableReader> sstables;
        private final Iterator<SSTableReader> sstableIterator;
        private final long totalLength;

        private SSTableScanner currentScanner;
        private long positionOffset;

        public LeveledScanner(Collection<SSTableReader> sstables, Range<Token> range)
        {
            this.range = range;
            this.sstables = new ArrayList<SSTableReader>(sstables);
            Collections.sort(this.sstables, SSTable.sstableComparator);
            sstableIterator = this.sstables.iterator();
            currentScanner = sstableIterator.next().getDirectScanner(range);

            long length = 0;
            for (SSTableReader sstable : sstables)
                length += sstable.uncompressedLength();
            totalLength = length;
        }

        protected IColumnIterator computeNext()
        {
            try
            {
                while (true)
                {
                    if (currentScanner.hasNext())
                        return currentScanner.next();

                    positionOffset += currentScanner.getLengthInBytes();
                    currentScanner.close();
                    if (!sstableIterator.hasNext())
                    {
                        currentScanner = null;
                        return endOfData();
                    }
                    currentScanner = sstableIterator.next().getDirectScanner(range);
                }
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        public void close() throws IOException
        {
            if (currentScanner != null)
                currentScanner.close();
        }

        public long getLengthInBytes()
        {
            return totalLength;
        }

        public long getCurrentPosition()
        {
            return positionOffset + (currentScanner == null ? 0L : currentScanner.getCurrentPosition());
        }

        public String getBackingFiles()
        {
            return Joiner.on(", ").join(sstables);
        }
    }

    @Override
    public String toString()
    {
        return String.format("LCS@%d(%s)", hashCode(), cfs.columnFamily);
    }
}
