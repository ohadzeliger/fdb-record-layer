/*
 * SizeStatisticsGroupedCursorTest.java
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

package com.apple.foundationdb.record.provider.foundationdb;

import com.apple.foundationdb.record.EndpointType;
import com.apple.foundationdb.record.ExecuteProperties;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.RecordCursorResult;
import com.apple.foundationdb.record.ScanProperties;
import com.apple.foundationdb.record.TestRecords1Proto;
import com.apple.foundationdb.record.TupleRange;
import com.apple.foundationdb.record.logging.KeyValueLogMessage;
import com.apple.foundationdb.record.provider.foundationdb.cursors.SizeStatisticsGroupedResults;
import com.apple.foundationdb.record.provider.foundationdb.cursors.SizeStatisticsGroupingCursor;
import com.apple.foundationdb.record.provider.foundationdb.cursors.SizeStatisticsResults;
import com.apple.foundationdb.tuple.Tuple;
import com.apple.foundationdb.util.StringUtils;
import com.apple.test.Tags;
import com.google.protobuf.Message;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test the {@link SizeStatisticsGroupingCursor} through the use of real records and store.
 */
@Tag(Tags.RequiresFDB)
public class SizeStatisticsGroupedCursorTest extends FDBRecordStoreTestBase {
    private static final Logger LOGGER = LoggerFactory.getLogger(SizeStatisticsGroupedCursorTest.class);

    @Test
    public void calcSize() throws Exception {
        populateStore(1_000, 1_000_000L);

        try (FDBRecordContext context = openContext()) {
            openSimpleRecordStore(context);
            long exactStoreSize = getExactStoreSize(recordStore);

            long exactRecordsSize = getExactRecordsSize(recordStore);

            Assertions.assertThat(exactStoreSize).isGreaterThan(0);
            Assertions.assertThat(exactRecordsSize).isGreaterThan(0);

            commit(context); // commit as that logs the store timer stats
        }
    }

    @Test
    public void calcSizeWithContinuation() throws Exception {
        populateStore(1_000, 1_000_000L);

        try (FDBRecordContext context = openContext()) {
            openSimpleRecordStore(context);
            ScanProperties scanProperties = new ScanProperties(ExecuteProperties.newBuilder().setScannedBytesLimit(10000).build());
            // Scan a little of the data
            RecordCursorResult<SizeStatisticsGroupedResults> continuationResult = getStoreSizeResult(recordStore, scanProperties, null);
            Assertions.assertThat(continuationResult.hasNext()).isFalse(); // should return early due to BYTE LIMIT, no value returned
            Assertions.assertThat(continuationResult.getNoNextReason()).isEqualTo(RecordCursor.NoNextReason.BYTE_LIMIT_REACHED);
            Assertions.assertThat(continuationResult.getContinuation()).isNotNull();
            // scan the rest of the data
            continuationResult = getStoreSizeResult(recordStore, ScanProperties.FORWARD_SCAN, continuationResult.getContinuation().toBytes());
            Assertions.assertThat(continuationResult.hasNext()).isTrue();
            Assertions.assertThat(continuationResult.getContinuation().isEnd()).isFalse();
            // scan again, to get the continuation to the end
            RecordCursorResult<SizeStatisticsGroupedResults> finalResult = getStoreSizeResult(recordStore, ScanProperties.FORWARD_SCAN, continuationResult.getContinuation().toBytes());
            Assertions.assertThat(finalResult.hasNext()).isFalse();
            Assertions.assertThat(finalResult.getContinuation().isEnd()).isTrue();

            final RecordCursorResult<SizeStatisticsGroupedResults> controlResult = getStoreSizeResult(recordStore, ScanProperties.FORWARD_SCAN, null);

            Assertions.assertThat(continuationResult.get().getStats()).usingRecursiveComparison().isEqualTo(controlResult.get().getStats());

            commit(context); // commit as that logs the store timer stats
        }
    }

    @Test
    public void calcSizeWithAggregationDepthOne() throws Exception {
        final int recordCount = 1_000;
        populateStore(recordCount, 1_000_000L);

        try (FDBRecordContext context = openContext()) {
            openSimpleRecordStore(context);
            final Map<Tuple, SizeStatisticsResults> result = getStoreSizeAllResults(recordStore, 1);

            Assertions.assertThat(result.size()).isEqualTo(3); // store header, records, indexes
            Assertions.assertThat(result.get(Tuple.from(0))).matches(stats -> stats.getKeyCount() == 1); // header
            Assertions.assertThat(result.get(Tuple.from(1))).matches(stats -> stats.getKeyCount() == recordCount * 2); // records and versions
            Assertions.assertThat(result.get(Tuple.from(2))).matches(stats -> stats.getKeyCount() > 0); // indexes


            commit(context); // commit as that logs the store timer stats
        }
    }

    @Test
    public void calcSizeWithAggregationDepthTwo() throws Exception {
        final int recordCount = 1_000;
        populateStore(recordCount, 1_000_000L);

        try (FDBRecordContext context = openContext()) {
            openSimpleRecordStore(context);
            final Map<Tuple, SizeStatisticsResults> result = getStoreSizeAllResults(recordStore, 2);

            Assertions.assertThat(result.size()).isEqualTo(1006);

            SizeStatisticsResults headerStats = result.get(Tuple.from(0));
            Assertions.assertThat(headerStats.getKeyCount()).isEqualTo(1); // header

            List<Map.Entry<Tuple, SizeStatisticsResults>> recordStats = result.entrySet().stream()
                    .filter(entry -> entry.getKey().getLong(0) == 1).collect(Collectors.toList()); // all entries for records have "1" as the first element of the tuple
            Assertions.assertThat(recordStats).hasSize(recordCount);
            Assertions.assertThat(recordStats).allMatch(entry -> entry.getKey().size() == 2); // grouping key has size 2
            Assertions.assertThat(recordStats).allMatch(entry -> entry.getValue().getKeyCount() == 2); // record data and version

            List<Map.Entry<Tuple, SizeStatisticsResults>> indexStats = result.entrySet().stream()
                    .filter(entry -> entry.getKey().getLong(0) == 2).collect(Collectors.toList()); // all entries for index have "2" as the first element of the tuple
            Assertions.assertThat(indexStats).hasSize(5); // 5 indexes on the store
            Assertions.assertThat(indexStats).allMatch(entry -> entry.getKey().size() == 2); // grouping key has size 2
            Assertions.assertThat(indexStats).allMatch(entry ->
                    !(entry.getKey().getString(1).contains("value")) || (entry.getValue().getKeyCount() == recordCount)); // value indexes contain all records

            commit(context); // commit as that logs the store timer stats
        }
    }

    @Test
    public void calcSizeWithAggregationDepthUnlimited() throws Exception {
        final int recordCount = 1_000;
        populateStore(recordCount, 1_000_000L);

        try (FDBRecordContext context = openContext()) {
            openSimpleRecordStore(context);
            final Map<Tuple, SizeStatisticsResults> result = getStoreSizeAllResults(recordStore, 1000);

            Assertions.assertThat(result.size()).isEqualTo(5003);

            SizeStatisticsResults headerStats = result.get(Tuple.from(0));
            Assertions.assertThat(headerStats.getKeyCount()).isEqualTo(1); // header

            List<Map.Entry<Tuple, SizeStatisticsResults>> recordStats = result.entrySet().stream()
                    .filter(entry -> entry.getKey().getLong(0) == 1).collect(Collectors.toList()); // all entries for records have "1" as the first element of the tuple
            Assertions.assertThat(recordStats).hasSize(recordCount * 2); // record data and version
            Assertions.assertThat(recordStats).allMatch(entry -> entry.getKey().size() == 3); // grouping key has size 3
            Assertions.assertThat(recordStats).allMatch(entry -> (entry.getKey().getLong(2) == 0) || (entry.getKey().getLong(2) == -1)); // split suffix is either 0 or -1
            Assertions.assertThat(recordStats).allMatch(entry -> entry.getValue().getKeyCount() == 1); // single entry per stat

            List<Map.Entry<Tuple, SizeStatisticsResults>> indexStats = result.entrySet().stream()
                    .filter(entry -> entry.getKey().getLong(0) == 2).collect(Collectors.toList()); // all entries for index have "2" as the first element of the tuple

            commit(context); // commit as that logs the store timer stats
        }
    }

    @Disabled("too expensive but useful for smoke-checking the performance on large stores")
    @Test
    public void estimateLargeStore() throws Exception {
        populateStore(200_000, 1_000_000_000L);

        try (FDBRecordContext context = openContext()) {
            openSimpleRecordStore(context);
            long estimatedStoreSize = estimateStoreSize(recordStore);
            long estimatedRecordsSize = estimateRecordsSize(recordStore);
            LOGGER.info(KeyValueLogMessage.of("calculated estimated record size on 1 GB store",
                    "estimated_store_size", estimatedStoreSize,
                    "estimated_records_size", estimatedRecordsSize));
            assertThat(estimatedRecordsSize, lessThanOrEqualTo(estimatedStoreSize));

            commit(context);
        }
    }

    @Test
    public void estimateEmptyStore() throws Exception {
        try (FDBRecordContext context = openContext()) {
            openSimpleRecordStore(context);
            long estimatedStoreSize = estimateStoreSize(recordStore);
            long estimatedRecordsSize = estimateRecordsSize(recordStore);
            LOGGER.info(KeyValueLogMessage.of("estimated size of empty store",
                    "estimated_store_size", estimatedStoreSize,
                    "estimated_record_size", estimatedRecordsSize));
            assertThat(estimatedRecordsSize, lessThanOrEqualTo(estimatedStoreSize));

            commit(context);
        }
    }

    @Test
    public void estimateSingleRecord() throws Exception {
        populateStore(1, 5_000L);

        try (FDBRecordContext context = openContext()) {
            openSimpleRecordStore(context);
            long estimatedStoreSize = estimateStoreSize(recordStore);
            long estimatedRecordsSize = estimateRecordsSize(recordStore);
            LOGGER.info(KeyValueLogMessage.of("estimated size of store with a single record",
                    "estimated_store_size", estimatedStoreSize,
                    "estimated_record_size", estimatedRecordsSize));
            assertThat(estimatedRecordsSize, lessThanOrEqualTo(estimatedStoreSize));

            commit(context);
        }
    }

    @Test
    public void estimateInTwoRanges() throws Exception {
        final int recordCount = 500;
        populateStore(recordCount, recordCount * 5_000);

        try (FDBRecordContext context = openContext()) {
            openSimpleRecordStore(context);
            long estimatedRecordSize = estimateRecordsSize(recordStore);
            final Tuple halfWayTuple = Tuple.from(recordCount / 2);
            final TupleRange firstHalf = new TupleRange(null, halfWayTuple, EndpointType.TREE_START, EndpointType.RANGE_EXCLUSIVE);
            long estimatedFirstHalf = estimateRecordsSize(recordStore, firstHalf);
            final TupleRange secondHalf = new TupleRange(halfWayTuple, null, EndpointType.RANGE_INCLUSIVE, EndpointType.TREE_END);
            long estimatedSecondHalf = estimateRecordsSize(recordStore, secondHalf);
            LOGGER.info(KeyValueLogMessage.of("estimated both halves of records",
                    "estimated_records_size", estimatedRecordSize,
                    "estimated_first_half_size", estimatedFirstHalf,
                    "estimated_second_half_size", estimatedSecondHalf));

            assertEquals(estimatedFirstHalf + estimatedSecondHalf, estimatedRecordSize,
                    "expected first half size (" + estimatedFirstHalf + ") and second half size (" + estimatedSecondHalf + ") to match full size");

            commit(context);
        }
    }

    private static long estimateStoreSize(@Nonnull FDBRecordStore store) {
        return store.getRecordContext().asyncToSync(FDBStoreTimer.Waits.WAIT_ESTIMATE_SIZE, store.estimateStoreSizeAsync());
    }

    private static long estimateRecordsSize(@Nonnull FDBRecordStore store) {
        return store.getRecordContext().asyncToSync(FDBStoreTimer.Waits.WAIT_ESTIMATE_SIZE, store.estimateRecordsSizeAsync());
    }

    private static long estimateRecordsSize(@Nonnull FDBRecordStore store, @Nonnull TupleRange range) {
        return store.getRecordContext().asyncToSync(FDBStoreTimer.Waits.WAIT_ESTIMATE_SIZE, store.estimateRecordsSizeAsync(range));
    }

    private static long getExactStoreSize(@Nonnull FDBRecordStore store) {
        return getExactStoreSize(store, ScanProperties.FORWARD_SCAN, true);
    }

    private Map<Tuple, SizeStatisticsResults> getStoreSizeAllResults(@Nonnull FDBRecordStore store, int aggregationDepth) {
        byte[] continuation = null;
        boolean done = false;
        Map<Tuple, SizeStatisticsResults> result = new HashMap<>();
        while (!done) {
            final RecordCursorResult<SizeStatisticsGroupedResults> storeSizeResult = getStoreSizeResult(store, ScanProperties.FORWARD_SCAN, continuation, aggregationDepth);
            if (storeSizeResult.hasNext()) {
                continuation = storeSizeResult.getContinuation().toBytes();
                Assertions.assertThat(result).doesNotContainKey(storeSizeResult.get().getAggregationKey());
                result.put(storeSizeResult.get().getAggregationKey(), storeSizeResult.get().getStats());
            }
            if (storeSizeResult.getContinuation().isEnd()) {
                done = true;
            } else {
                continuation = storeSizeResult.getContinuation().toBytes();
            }
        }
        return result;
    }

    private RecordCursorResult<SizeStatisticsGroupedResults> getStoreSizeResult(@Nonnull FDBRecordStore store, byte[] continuation) {
        return getStoreSizeResult(store, ScanProperties.FORWARD_SCAN, continuation);
    }

    private RecordCursorResult<SizeStatisticsGroupedResults> getStoreSizeResult(@Nonnull FDBRecordStore store, final ScanProperties scanProperties, byte[] continuation) {
        return getStoreSizeResult(store, scanProperties, continuation, 0);
    }

    private RecordCursorResult<SizeStatisticsGroupedResults> getStoreSizeResult(@Nonnull FDBRecordStore store, final ScanProperties scanProperties, byte[] continuation, int aggregationDepth) {
        SizeStatisticsGroupingCursor statsCursor = SizeStatisticsGroupingCursor.ofStore(store, store.getContext(), scanProperties, continuation, aggregationDepth);
        return statsCursor.getNext();
    }

    private static long getExactStoreSize(@Nonnull FDBRecordStore store, final ScanProperties scanProperties, boolean hasResult) {
        SizeStatisticsGroupingCursor statsCursor = SizeStatisticsGroupingCursor.ofStore(store, store.getContext(), scanProperties, null, 0);
        return getTotalSize(statsCursor, hasResult);
    }

    private static long getExactRecordsSize(@Nonnull FDBRecordStore store) {
        SizeStatisticsGroupingCursor statsCursor = SizeStatisticsGroupingCursor.ofRecords(store, store.getContext(), ScanProperties.FORWARD_SCAN, null, 0);
        return getTotalSize(statsCursor);
    }

    private static long getTotalSize(@Nonnull SizeStatisticsGroupingCursor statsCursor) {
        return getTotalSize(statsCursor, true);
    }

    private static long getTotalSize(@Nonnull SizeStatisticsGroupingCursor statsCursor, boolean hasResult) {
        final RecordCursorResult<SizeStatisticsGroupedResults> result = statsCursor.getNext();
        Assertions.assertThat(result.hasNext()).isEqualTo(hasResult);
        if (result.hasNext()) {
            return result.get().getStats().getTotalSize();
        } else {
            return -1;
        }
    }

    private void populateStore(int recordCount, long byteCount) throws Exception {
        int bytesPerRecord = (int)(byteCount / recordCount);
        final String data = StringUtils.repeat('x', bytesPerRecord);

        int currentRecord = 0;
        while (currentRecord < recordCount) {
            try (FDBRecordContext context = openContext()) {
                openSimpleRecordStore(context);

                long transactionSize;
                do {
                    final Message record = TestRecords1Proto.MySimpleRecord.newBuilder()
                            .setRecNo(currentRecord)
                            .setStrValueIndexed(data)
                            .build();
                    recordStore.saveRecord(record);
                    transactionSize = context.asyncToSync(FDBStoreTimer.Waits.WAIT_APPROXIMATE_TRANSACTION_SIZE,
                            context.getApproximateTransactionSize());
                    currentRecord++;
                } while (currentRecord < recordCount && transactionSize < 1_000_000);

                commit(context);
            }
        }
    }
}
