/*
 * LuceneOptimizedBlockTermState.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2023 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.lucene.codec;

import com.apple.foundationdb.KeyValue;
import com.apple.foundationdb.record.lucene.LucenePostingsProto;
import com.apple.foundationdb.tuple.Tuple;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.lucene.codecs.BlockTermState;
import org.apache.lucene.index.TermState;
import org.apache.lucene.util.BytesRef;

import javax.annotation.Nonnull;
import java.io.IOException;

//TODO: Do we need to extend BlockTermState?
public class LuceneOptimizedBlockTermState extends BlockTermState {
    private BytesRef term;
    private LucenePostingsProto.TermInfo termInfo;

    public LuceneOptimizedBlockTermState(@Nonnull final KeyValue keyValue) throws IOException {
        Tuple tuple = Tuple.fromBytes(keyValue.getKey());
        this.term = new BytesRef(tuple.getBytes(tuple.size() - 1));
        this.termInfo = LucenePostingsProto.TermInfo.parseFrom(keyValue.getValue());
        assert term != null: "Term Cannot Be Null";
        assert termInfo != null: "Term Cannot Be Null";
        this.docFreq = termInfo.getDocFreq();
        this.totalTermFreq = termInfo.getTotalTermFreq();
        this.ord = termInfo.getOrd();
    }

    // TODO: @Nonull params?
    public void copyFrom(final BytesRef term, byte[] termBytes) throws IOException {
        final LucenePostingsProto.TermInfo termInfo = LucenePostingsProto.TermInfo.parseFrom(termBytes);
        this.term = term;
        this.termInfo = termInfo;
        this.docFreq = termInfo.getDocFreq();
        this.totalTermFreq = termInfo.getTotalTermFreq();
        this.ord = termInfo.getOrd();
    }

    @Override
    public void copyFrom(final TermState other) {
        this.term = ((LuceneOptimizedBlockTermState)other).term;
        this.termInfo = ((LuceneOptimizedBlockTermState)other).termInfo;
        assert term != null: "Term Cannot Be Null";
        assert termInfo != null: "TermInfo Cannot Be Null";
        this.docFreq = termInfo.getDocFreq();
        this.totalTermFreq = termInfo.getTotalTermFreq();
        this.ord = termInfo.getOrd();
    }

    public int compareTermTo(BytesRef text) {
        return text.compareTo(term);
    }

    public BytesRef getTerm() {
        return term;
    }

    public LucenePostingsProto.TermInfo getTermInfo() {
        return termInfo;
    }

    public int getDocFreq() {
        return termInfo.getDocFreq();
    }

    public long getTotalTermFreq() {
        return termInfo.getTotalTermFreq();
    }

    public long getOrd() {
        return ord;
    }
}