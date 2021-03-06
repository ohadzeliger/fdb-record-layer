/*
 * IndexingBase.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2020 Apple Inc. and the FoundationDB project authors
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

import com.apple.foundationdb.FDBException;
import com.apple.foundationdb.MutationType;
import com.apple.foundationdb.Transaction;
import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.async.AsyncUtil;
import com.apple.foundationdb.async.MoreAsyncUtil;
import com.apple.foundationdb.async.RangeSet;
import com.apple.foundationdb.record.IndexBuildProto;
import com.apple.foundationdb.record.IndexState;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.RecordCursorResult;
import com.apple.foundationdb.record.logging.KeyValueLogMessage;
import com.apple.foundationdb.record.logging.LogMessageKeys;
import com.apple.foundationdb.record.metadata.Index;
import com.apple.foundationdb.record.metadata.Key;
import com.apple.foundationdb.record.provider.foundationdb.synchronizedsession.SynchronizedSessionRunner;
import com.apple.foundationdb.record.query.plan.synthetic.SyntheticRecordFromStoredRecordPlan;
import com.apple.foundationdb.subspace.Subspace;
import com.apple.foundationdb.tuple.Tuple;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A base class for different types of online indexing scanners.
 */
@API(API.Status.INTERNAL)
public abstract class IndexingBase {

    private static final Object INDEX_BUILD_LOCK_KEY = 0L;
    private static final Object INDEX_BUILD_SCANNED_RECORDS = 1L;
    private static final Object INDEX_BUILD_TYPE_VERSION = 2L;

    @Nonnull
    private static final Logger LOGGER = LoggerFactory.getLogger(IndexingBase.class);
    @Nonnull
    protected final IndexingCommon common; // to be used by extenders
    @Nonnull
    private final IndexingThrottle throttle;

    private long timeOfLastProgressLogMillis = 0;
    private boolean forceStampOverwrite = false;

    IndexingBase(IndexingCommon common) {
        this.common = common;
        this.throttle = new IndexingThrottle(common);
    }

    // helper functions
    protected FDBDatabaseRunner getRunner() {
        return common.getRunner();
    }

    @Nonnull
    private static Subspace indexBuildSubspace(@Nonnull FDBRecordStoreBase<?> store, @Nonnull Index index, Object key) {
        return store.getUntypedRecordStore().indexBuildSubspace(index).subspace(Tuple.from(key));
    }

    @Nonnull
    protected static Subspace indexBuildLockSubspace(@Nonnull FDBRecordStoreBase<?> store, @Nonnull Index index) {
        return indexBuildSubspace(store, index, INDEX_BUILD_LOCK_KEY);
    }

    @Nonnull
    protected static Subspace indexBuildScannedRecordsSubspace(@Nonnull FDBRecordStoreBase<?> store, @Nonnull Index index) {
        return indexBuildSubspace(store, index, INDEX_BUILD_SCANNED_RECORDS);
    }

    @Nonnull
    protected static Subspace indexBuildTypeSubspace(@Nonnull FDBRecordStoreBase<?> store, @Nonnull Index index) {
        return indexBuildSubspace(store, index, INDEX_BUILD_TYPE_VERSION);
    }

    @SuppressWarnings("squid:S1452")
    protected CompletableFuture<FDBRecordStore> openRecordStore(@Nonnull FDBRecordContext context) {
        return common.getRecordStoreBuilder().copyBuilder().setContext(context).openAsync();
    }

    // Turn a (possibly null) tuple into a (possibly null) byte array.
    @Nullable
    protected static byte[] packOrNull(@Nullable Tuple tuple) {
        return (tuple == null) ? null : tuple.pack();
    }

    // Turn a (possibly null) key into its tuple representation.
    @Nullable
    protected static Tuple convertOrNull(@Nullable Key.Evaluated key) {
        return (key == null) ? null : key.toTuple();
    }

    // (methods order: as a rule of thumb, let sub-routines follow their callers)

    // buildIndexAsync - the main indexing function. Builds and commits indexes asynchronously; throttling to avoid overloading the system.
    public CompletableFuture<Void> buildIndexAsync(boolean markReadable) {
        KeyValueLogMessage message = KeyValueLogMessage.build("build index online",
                LogMessageKeys.SHOULD_MARK_READABLE, markReadable);
        final CompletableFuture<Void> buildIndexAsyncFuture;
        FDBDatabaseRunner runner = common.getRunner();
        Index index = common.getIndex();
        if (common.isUseSynchronizedSession()) {
            buildIndexAsyncFuture = runner
                    .runAsync(context -> openRecordStore(context).thenApply(store -> indexBuildLockSubspace(store, index)))
                    .thenCompose(lockSubspace -> runner.startSynchronizedSessionAsync(lockSubspace, common.getLeaseLengthMillis()))
                    .thenCompose(synchronizedRunner -> {
                        message.addKeyAndValue(LogMessageKeys.SESSION_ID, synchronizedRunner.getSessionId());
                        return runWithSynchronizedRunnerAndEndSession(synchronizedRunner,
                                () -> handleStateAndDoBuildIndexAsync(markReadable, message));
                    });
        } else {
            message.addKeyAndValue(LogMessageKeys.SESSION_ID, "none");
            common.setSynchronizedSessionRunner(null);
            buildIndexAsyncFuture = handleStateAndDoBuildIndexAsync(markReadable, message);
        }
        return buildIndexAsyncFuture.whenComplete((vignore, ex) -> {
            if (LOGGER.isWarnEnabled() && (ex != null)) {
                message.addKeyAndValue(LogMessageKeys.RESULT, "failure");
                LOGGER.warn(message.toString(), ex);
            } else if (LOGGER.isInfoEnabled()) {
                message.addKeyAndValue(LogMessageKeys.RESULT, "success");
                LOGGER.info(message.toString());
            }
        });
    }

    private <T> CompletableFuture<T> runWithSynchronizedRunnerAndEndSession(
            @Nonnull SynchronizedSessionRunner newSynchronizedRunner, @Nonnull Supplier<CompletableFuture<T>> runnable) {
        final SynchronizedSessionRunner currentSynchronizedRunner1 = common.getSynchronizedSessionRunner();
        if (currentSynchronizedRunner1 == null) {
            common.setSynchronizedSessionRunner(newSynchronizedRunner);
            return MoreAsyncUtil.composeWhenComplete(runnable.get(), (result, ex) -> {
                final SynchronizedSessionRunner currentSynchronizedRunner2 = common.getSynchronizedSessionRunner();
                if (newSynchronizedRunner.equals(currentSynchronizedRunner2)) {
                    common.setSynchronizedSessionRunner(null);
                } else {
                    LOGGER.warn(KeyValueLogMessage.of("synchronizedSessionRunner was modified during the run",
                            LogMessageKeys.SESSION_ID, newSynchronizedRunner.getSessionId(),
                            LogMessageKeys.INDEXER_SESSION_ID, currentSynchronizedRunner2 == null ? null : currentSynchronizedRunner2.getSessionId()));
                }
                return newSynchronizedRunner.endSessionAsync();
            }, getRunner().getDatabase()::mapAsyncToSyncException);
        } else {
            return newSynchronizedRunner.endSessionAsync().thenApply(vignore -> {
                throw new RecordCoreException("another synchronized session is running on the indexer",
                        LogMessageKeys.SESSION_ID, newSynchronizedRunner.getSessionId(),
                        LogMessageKeys.INDEXER_SESSION_ID, currentSynchronizedRunner1.getSessionId());
            });
        }
    }

    @Nonnull
    private CompletableFuture<Void> handleStateAndDoBuildIndexAsync(boolean markReadable, KeyValueLogMessage message) {

        OnlineIndexer.IndexStatePrecondition indexStatePrecondition = common.getIndexStatePrecondition();
        message.addKeyAndValue(LogMessageKeys.INDEX_STATE_PRECONDITION, indexStatePrecondition);
        if (indexStatePrecondition == OnlineIndexer.IndexStatePrecondition.ERROR_IF_DISABLED_CONTINUE_IF_WRITE_ONLY) {
            message.addKeyAndValue(LogMessageKeys.SHOULD_BUILD_INDEX, true);
            return doBuildIndexAsync(markReadable);
        }
        final Index index = common.getIndex();
        return getRunner().runAsync(context -> openRecordStore(context).thenCompose(store -> {
            IndexState indexState = store.getIndexState(index);
            boolean shouldBuild = shouldBuildIndex(indexState, indexStatePrecondition);
            message.addKeyAndValue(LogMessageKeys.INITIAL_INDEX_STATE, indexState);
            message.addKeyAndValue(LogMessageKeys.SHOULD_BUILD_INDEX, shouldBuild);
            if (shouldBuild) {
                boolean shouldClear = shouldClearExistingIndexEntries(indexState, indexStatePrecondition);
                message.addKeyAndValue(LogMessageKeys.SHOULD_CLEAR_EXISTING_DATA, shouldClear);
                if (shouldClear) {
                    store.clearIndexData(index);
                }
                return store.markIndexWriteOnly(index).thenApply(vignore -> true);
            } else {
                return AsyncUtil.READY_FALSE;
            }
        })).thenCompose(shouldBuild -> shouldBuild != null && shouldBuild ? doBuildIndexAsync(markReadable) : AsyncUtil.DONE);
    }

    @SuppressWarnings("fallthrough")
    private boolean shouldBuildIndex(@Nonnull IndexState indexState, @Nonnull OnlineIndexer.IndexStatePrecondition indexStatePrecondition) {
        switch (indexStatePrecondition) {
            case BUILD_IF_DISABLED:
                return indexState == IndexState.DISABLED;

            case BUILD_IF_DISABLED_CONTINUE_BUILD_IF_WRITE_ONLY:
            case BUILD_IF_DISABLED_REBUILD_IF_WRITE_ONLY:
                return indexState == IndexState.DISABLED || indexState == IndexState.WRITE_ONLY;

            case FORCE_BUILD:
                return true;

            default:
                throw new RecordCoreException("unknown index state precondition " + indexStatePrecondition);
        }
    }

    private boolean shouldClearExistingIndexEntries(@Nonnull IndexState indexState,
                                                    @Nonnull OnlineIndexer.IndexStatePrecondition indexStatePrecondition) {
        // If the index state is DISABLED, it is expected that there is no existing index entry. But we would like
        // to clear it anyway to play safe.
        return !(indexState == IndexState.WRITE_ONLY && indexStatePrecondition.isContinueIfWriteOnly());
    }

    @Nonnull
    private CompletableFuture<Void> doBuildIndexAsync(boolean markReadable) {
        CompletableFuture<Void> buildFuture =
                setIndexingTypeOrThrow()
                        .thenCompose(ignore -> buildIndexInternalAsync());

        if (markReadable) {
            return buildFuture.thenCompose(vignore ->
                    getRunner().runAsync(context -> openRecordStore(context)
                            .thenCompose(store -> store.markIndexReadable(common.getIndex()))
                            .thenApply(ignore -> null)));
        } else {
            return buildFuture;
        }
    }

    @Nonnull
    public void setFallbackMode() {
        forceStampOverwrite = true; // must overwrite a previous indexing method's stamp
    }

    @Nonnull
    private CompletableFuture<Void> setIndexingTypeOrThrow() {
        return getRunner().runAsync(context -> openRecordStore(context).thenCompose(store -> {
            Transaction transaction = store.getContext().ensureActive();
            IndexBuildProto.IndexBuildIndexingStamp indexingTypeStamp = getIndexingTypeStamp();
            byte[] stampKey = indexBuildTypeSubspace(store, common.getIndex()).getKey();
            return transaction.get(stampKey)
                    .thenApply( bytes -> {
                        if (bytes == null || forceStampOverwrite) {
                            transaction.set(stampKey, indexingTypeStamp.toByteArray());
                        } else {
                            IndexBuildProto.IndexBuildIndexingStamp savedStamp;
                            try {
                                savedStamp = IndexBuildProto.IndexBuildIndexingStamp.parseFrom(bytes);
                            } catch (InvalidProtocolBufferException ex) {
                                RecordCoreException protoEx = new RecordCoreException("invalid indexing type stamp",
                                        LogMessageKeys.INDEX_NAME, common.getIndex().getName(),
                                        LogMessageKeys.INDEX_VERSION, common.getIndex().getLastModifiedVersion(),
                                        LogMessageKeys.INDEXER_ID, common.getUuid(),
                                        LogMessageKeys.EXPECTED, indexingTypeStamp,
                                        LogMessageKeys.ACTUAL, bytes);
                                protoEx.initCause(ex);
                                throw protoEx;
                            }
                            if ( savedStamp == null || ! matchingIndexingTypeStamp(savedStamp)) {
                                throw new RecordCoreException("This index was partly built by another method",
                                        LogMessageKeys.INDEX_NAME, common.getIndex().getName(),
                                        LogMessageKeys.INDEX_VERSION, common.getIndex().getLastModifiedVersion(),
                                        LogMessageKeys.INDEXER_ID, common.getUuid(),
                                        LogMessageKeys.EXPECTED, indexingTypeStamp,
                                        LogMessageKeys.ACTUAL, savedStamp);
                            }
                        }
                        return null;
                    });
        } ));
    }

    @Nonnull
    abstract IndexBuildProto.IndexBuildIndexingStamp getIndexingTypeStamp();

    @Nonnull
    private boolean matchingIndexingTypeStamp(@Nonnull final IndexBuildProto.IndexBuildIndexingStamp stamp) {
        return getIndexingTypeStamp().equals(stamp);
    }

    abstract CompletableFuture<Void> buildIndexInternalAsync();

    // Helpers for implementing modules. Some of them are public to support unit-testing.
    protected void maybeLogBuildProgress(SubspaceProvider subspaceProvider, List<Object> additionalLogMessageKeyValues) {
        if (LOGGER.isInfoEnabled() && shouldLogBuildProgress()) {
            final Index index = common.getIndex();
            LOGGER.info(KeyValueLogMessage.build("Built Range",
                    LogMessageKeys.INDEX_NAME, index.getName(),
                    LogMessageKeys.INDEX_VERSION, index.getLastModifiedVersion(),
                    subspaceProvider.logKey(), subspaceProvider,
                    LogMessageKeys.RECORDS_SCANNED, common.getTotalRecordsScanned().get(),
                    LogMessageKeys.INDEXER_ID, common.getUuid())
                    .addKeysAndValues(additionalLogMessageKeyValues)
                    .toString());
        }
    }

    private boolean shouldLogBuildProgress() {
        long interval = common.config.getProgressLogIntervalMillis();
        long now = System.currentTimeMillis();
        if (interval == 0 || interval < (now - timeOfLastProgressLogMillis)) {
            return false;
        }
        timeOfLastProgressLogMillis = now;
        return true;
    }

    public int getLimit() {
        // made public to support tests
        return throttle.getLimit();
    }

    protected CompletableFuture<Boolean> throttleDelay() {
        int limit = getLimit();
        int recordsPerSecond = common.config.getRecordsPerSecond();
        int toWait = (recordsPerSecond == IndexingCommon.UNLIMITED) ? 0 : 1000 * limit / recordsPerSecond;
        return MoreAsyncUtil.delayedFuture(toWait, TimeUnit.MILLISECONDS).thenApply(vignore3 -> true);
    }


    public <R> CompletableFuture<R> buildCommitRetryAsync(@Nonnull BiFunction<FDBRecordStore, AtomicLong, CompletableFuture<R>> buildFunction,
                                                          boolean limitControl,
                                                          @Nullable List<Object> additionalLogMessageKeyValues) {
        return throttle.buildCommitRetryAsync(buildFunction, limitControl, additionalLogMessageKeyValues);
    }

    private static void timerIncrement(@Nullable FDBStoreTimer timer, FDBStoreTimer.Counts event) {
        // helper function to reduce complexity
        if (timer != null) {
            timer.increment(event);
        }
    }

    /**
     * iterate cursor's items and index them.
     * @param store the record store.
     * @param cursor iteration items.
     * @param getRecord function to convert cursor's item to a record.
     * @param nextResultCont when return, if hasMore is true, holds the last cursored result - unprocessed - as a continuation item.
     * @param hasMore when return, true if the cursor's source is not exhausted (not more items in range).
     * @param recordsScanned when return, number of scanned records.
     * @param <T> cursor result's type.
     * @return hasMore, nextResultCont, and recordsScanned.
     */
    protected <T> CompletableFuture<Void> iterateRangeOnly(@Nonnull FDBRecordStore store,
                                                           @Nonnull RecordCursor<T> cursor,
                                                           @Nonnull Function<RecordCursorResult<T>, FDBStoredRecord<Message>> getRecord,
                                                           @Nonnull AtomicReference<RecordCursorResult<T>> nextResultCont,
                                                           @Nonnull AtomicBoolean hasMore,
                                                           @Nullable AtomicLong recordsScanned) {
        final FDBStoreTimer timer = getRunner().getTimer();
        final Index index = common.getIndex();
        final IndexMaintainer maintainer = store.getIndexMaintainer(index);
        final boolean isIdempotent = maintainer.isIdempotent();
        final FDBRecordContext context = store.getContext();
        final SyntheticRecordFromStoredRecordPlan syntheticPlan = common.getSyntheticPlan(store);
        // Need to do this each transaction because other index enabled state might have changed. Could cache based on that.
        // Copying the state also guards against changes made by other online building from check version.
        // TODO: need some state to avoid generating the same synthetic record via more than one self-join path for non-idempotent indexes.
        AtomicLong recordsScannedCounter = new AtomicLong();

        final AtomicReference<RecordCursorResult<T>> nextResult = new AtomicReference<>(null);
        return AsyncUtil.whileTrue(() -> cursor.onNext().thenCompose(result -> {
            RecordCursorResult<T> currResult;
            boolean isExhausted = false;
            if (result.hasNext()) {
                // has next, process one previous item (if exists)
                currResult = nextResult.get();
                nextResult.set(result);
                if (currResult == null) {
                    // that was the first item, nothing to process
                    return AsyncUtil.READY_TRUE;
                }
            } else {
                // end of the cursor list
                timerIncrement(timer, FDBStoreTimer.Counts.ONLINE_INDEX_BUILDER_RANGES_BY_COUNT);
                if (! result.getNoNextReason().isSourceExhausted()) {
                    nextResultCont.set(nextResult.get());
                    hasMore.set(true);
                    return AsyncUtil.READY_FALSE;
                }
                // source is exhausted, fall down to handle the last item and return with hasMore=false
                currResult = nextResult.get();
                if (currResult == null) {
                    // there was no data
                    hasMore.set(false);
                    return AsyncUtil.READY_FALSE;
                }
                // here, process the last item and return
                nextResult.set(null);
                isExhausted = true;
            }
            // here: currResult must have value
            final FDBStoredRecord<Message> rec = getRecord.apply(currResult);
            timerIncrement(timer, FDBStoreTimer.Counts.ONLINE_INDEX_BUILDER_RECORDS_SCANNED);
            recordsScannedCounter.incrementAndGet();
            if (!common.recordTypes.contains(rec.getRecordType())) {
                // This record is not our type, swipe left
                if (isExhausted) {
                    hasMore.set(false);
                    return AsyncUtil.READY_FALSE;
                }
                return AsyncUtil.READY_TRUE;
            }
            // add this index to the transaction
            if (isIdempotent) {
                store.addRecordReadConflict(rec.getPrimaryKey());
            }
            timerIncrement(timer, FDBStoreTimer.Counts.ONLINE_INDEX_BUILDER_RECORDS_INDEXED);

            final CompletableFuture<Void> updateMaintainer = updateMaintainerBuilder(syntheticPlan, rec, maintainer, store);
            if (isExhausted) {
                // we've just processed the last item
                hasMore.set(false);
                return updateMaintainer.thenApply(vignore -> false);
            }
            return updateMaintainer.thenCompose(vignore ->
                    context.getApproximateTransactionSize().thenApply(size -> {
                        if (size >= common.config.getMaxWriteLimitBytes()) {
                            // the transaction becomes too big - stop iterating
                            timerIncrement(timer, FDBStoreTimer.Counts.ONLINE_INDEX_BUILDER_RANGES_BY_SIZE);
                            nextResultCont.set(nextResult.get());
                            hasMore.set(true);
                            return false;
                        }
                        return true;
                    }));

        }), cursor.getExecutor()
        ).thenApply(vignore -> {
            long recordsScannedInTransaction = recordsScannedCounter.get();
            if (recordsScanned != null) {
                recordsScanned.addAndGet(recordsScannedInTransaction);
            }
            if (common.isTrackProgress()) {
                final Subspace scannedRecordsSubspace = indexBuildScannedRecordsSubspace(store, index);
                store.context.ensureActive().mutate(MutationType.ADD, scannedRecordsSubspace.getKey(),
                        FDBRecordStore.encodeRecordCount(recordsScannedInTransaction));
            }
            return null;
        });
    }

    private static CompletableFuture<Void> updateMaintainerBuilder(SyntheticRecordFromStoredRecordPlan syntheticPlan,
                                                                   FDBStoredRecord<Message> rec,
                                                                   IndexMaintainer maintainer,
                                                                   FDBRecordStore store) {
        // helper function to reduce complexity
        if (syntheticPlan == null) {
            return maintainer.update(null, rec);
        }
        // Pipeline size is 1, since not all maintainers are thread-safe.
        return syntheticPlan.execute(store, rec).forEachAsync(syntheticRecord -> maintainer.update(null, syntheticRecord), 1);
    }

    // rebuildIndexAsyc - builds the whole index inline (without commiting)
    @Nonnull
    public CompletableFuture<Void> rebuildIndexAsync(@Nonnull FDBRecordStore store) {
        Index index = common.getIndex();
        Transaction tr = store.ensureContextActive();
        store.clearIndexData(index);

        // Clear the associated range set (done as part of clearIndexData above) and make it instead equal to
        // the complete range. This isn't super necessary, but it is done
        // to avoid (1) concurrent OnlineIndexBuilders doing more work and
        // (2) to allow for write-only indexes to continue to do the right thing.
        RangeSet rangeSet = new RangeSet(store.indexRangeSubspace(index));
        CompletableFuture<Boolean> rangeFuture = rangeSet.insertRange(tr, null, null);
        CompletableFuture<Void> buildFuture = rebuildIndexInternalAsync(store);

        return CompletableFuture.allOf(rangeFuture, buildFuture);
    }

    abstract CompletableFuture<Void> rebuildIndexInternalAsync(FDBRecordStore store);

    // These throttle methods are externalized for testing usage only
    @Nonnull
    <R> CompletableFuture<R> throttledRunAsync(@Nonnull final Function<FDBRecordStore, CompletableFuture<R>> function,
                                               @Nonnull final BiFunction<R, Throwable, Pair<R, Throwable>> handlePostTransaction,
                                               @Nullable final BiConsumer<FDBException, List<Object>> handleLessenWork,
                                               @Nullable final List<Object> additionalLogMessageKeyValues) {
        return throttle.throttledRunAsync(function, handlePostTransaction, handleLessenWork, additionalLogMessageKeyValues);
    }

    void decreaseLimit(@Nonnull FDBException fdbException,
                       @Nullable List<Object> additionalLogMessageKeyValues) {
        throttle.decreaseLimit(fdbException, additionalLogMessageKeyValues);
    }
}



