/*
 * LuceneDataBlockReferences.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2025 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.record.lucene.directory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class LuceneDataBlockReferences {
    private final Map<Long, Map<Long, AtomicLong>> blockReferenceCounters = new ConcurrentHashMap<>();

    public long getBlockReferenceCount(long fileId, long blockId) {
        final AtomicLong refCount = getReferenceCountInternal(fileId, blockId);
        return (refCount == null) ? 0 : refCount.get();
    }

    public boolean hasBlock(long fileId, long blockId) {
        return getReferenceCountInternal(fileId, blockId) != null;
    }

    public long addReference(long fileId, long blockId) {
        final Map<Long, AtomicLong> blockMap = blockReferenceCounters.computeIfAbsent(fileId, key -> new ConcurrentHashMap<>());
        AtomicLong blockRef = blockMap.computeIfAbsent(blockId, key -> new AtomicLong(0));
        return blockRef.incrementAndGet();
    }

    public void removeAllReferences(long fileId, boolean removeIfZero) {
        Map<Long, AtomicLong> fileRefs = blockReferenceCounters.get(fileId);
        if (fileRefs != null) {
            final List<Long> blockIds = new ArrayList<>(fileRefs.keySet());
            blockIds.forEach(blockId -> removeReference(fileId, blockId, removeIfZero));
        }
    }

    public long removeReference(long fileId, long blockId, boolean removeIfZero) {
        final AtomicLong refCount = Objects.requireNonNull(getReferenceCountInternal(fileId, blockId));
        refCount.decrementAndGet();
        if (removeIfZero && (refCount.get() == 0)) {
            Map<Long, AtomicLong> blockMap = Objects.requireNonNull(blockReferenceCounters.get(fileId)); // can be removed by another thread
            blockMap.remove(blockId);
            if (blockMap.isEmpty()) {
                blockReferenceCounters.remove(fileId);
            }
        }
        return refCount.get();
    }

    @Override
    public String toString() {
        StringBuffer buffer = new StringBuffer("LuceneDataBlockReferences: Referenced blocks:\n");
        blockReferenceCounters.forEach((fileId, blocks) -> {
            buffer.append("    File Id: ").append(fileId).append(" ").append(" Blocks: ");
            blocks.forEach((blockId, count) -> buffer.append(", ").append(blockId).append("->").append(count.get()));
            buffer.append("\n");
        });
        return buffer.toString();
    }

    private AtomicLong getReferenceCountInternal(long fileId, long blockId) {
        // Don't add anything to the maps for get
        Map<Long, AtomicLong> fileRefs = blockReferenceCounters.get(fileId);
        if (fileRefs != null) {
            AtomicLong blockRef = fileRefs.get(blockId);
            if (blockRef != null) {
                return blockRef;
            }
        }
        return null;
    }
}
