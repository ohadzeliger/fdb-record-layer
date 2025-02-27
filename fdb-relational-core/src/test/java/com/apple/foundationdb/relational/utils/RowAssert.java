/*
 * RowAssert.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2021-2024 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.relational.utils;

import com.apple.foundationdb.relational.api.Row;
import com.apple.foundationdb.relational.api.RelationalStruct;
import com.apple.foundationdb.relational.api.exceptions.InvalidColumnReferenceException;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.ByteArrayAssert;

import java.sql.Array;
import java.util.Collection;

public class RowAssert extends AbstractAssert<RowAssert, Row> {
    protected RowAssert(Row row) {
        super(row, RowAssert.class);
    }

    @Override
    public RowAssert isEqualTo(Object expected) {
        if (expected instanceof Row) {
            //do row comparison
            Row other = (Row) expected;
            //make sure that they have the same number of fields
            extracting(Row::getNumFields, Assertions::assertThat).isEqualTo(other.getNumFields());
            for (int i = 0; i < other.getNumFields(); i++) {
                try {
                    Object actualO = getObject(actual, i);
                    Object otherO = other.getObject(i);
                    extractAssert(actualO).isEqualTo(otherO);
                    //                        final int p = i; //use a temporary final variable so that the lambda doesn't object
                    //                        extracting(row -> getObject(row,p), this::extractAssert).isEqualTo(other.getObject(i));
                } catch (InvalidColumnReferenceException e) {
                    throw new RuntimeException(e);
                }
            }
        } else if (expected instanceof RelationalStruct) {
            try {
                RelationalStruct other = (RelationalStruct) expected;
                int colCount = other.getMetaData().getColumnCount();
                Assertions.assertThat(actual.getNumFields()).isEqualTo(colCount);
                for (int i = 1; i <= colCount; i++) {
                    Object actualO = getObject(actual, i - 1);
                    Object otherO = other.getObject(i);
                    extractAssert(actualO).isEqualTo(otherO);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            return super.isEqualTo(expected);
        }
        return this;
    }

    private AbstractAssert<? extends AbstractAssert<?, ?>, ?> extractAssert(Object o) {
        if (o instanceof Message) {
            return new MessageAssert((Message) o);
        } else if (o instanceof byte[]) {
            return new ByteArrayAssert((byte[]) o) {
                @Override
                public ByteArrayAssert isEqualTo(Object expected) {
                    if (expected instanceof byte[]) {
                        return containsExactly((byte[]) expected);
                    } else if (expected instanceof ByteString) {
                        return containsExactly(((ByteString) expected).toByteArray());
                    } else {
                        return super.isEqualTo(expected);
                    }
                }
            };
        } else if (o instanceof ByteString) {
            byte[] bytes = ((ByteString) o).toByteArray();
            return new ByteArrayAssert(bytes) {
                @Override
                public ByteArrayAssert isEqualTo(Object expected) {
                    if (expected instanceof byte[]) {
                        return containsExactly((byte[]) expected);
                    } else if (expected instanceof ByteString) {
                        return containsExactly(((ByteString) expected).toByteArray());
                    } else {
                        return super.isEqualTo(expected);
                    }
                }
            };
        } else if (o instanceof Array) {
            return new ArrayAssert((Array) o);
        } else if (o instanceof Iterable) {
            Assertions.fail("Should not have an iterable return type, use Arrays instead");
            return Assertions.assertThat(o); //won't happen, but makes the java compiler happy
        } else {
            return Assertions.assertThat(o);
        }
    }

    private Object getObject(Row row, int position) {
        //wrapper for the runtime exception handling so that our assertions are prettier
        try {
            return row.getObject(position);
        } catch (InvalidColumnReferenceException e) {
            //shouldn't happen, but you never know
            throw new RuntimeException(e);
        }
    }

    public RowAssert isContainedIn(Collection<Row> value) {
        return isContainedIn(value, "other collection");
    }

    public RowAssert isContainedIn(Collection<Row> value, String collectionName) {
        for (Row other : value) {
            //TODO(bfines) this is not ideal, do better
            try {
                isEqualTo(other);
                //we found one!
                return this;
            } catch (AssertionError error) {
                //nope, not equals, keep going
            }
        }
        throw failure("Did not find row %s within %s", actual, collectionName);
    }
}
