/*
 * PushdownFieldDesignator.java
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

package com.apple.foundationdb.record.query.plan.plans.pushdown;

import javax.annotation.Nonnull;

/**
 * Designator of a field value: what part of the record (key/value) and its enumerator within the list.
 */
public class PushdownFieldDesignator {
    public enum FieldType {KEY, VALUE}

    @Nonnull
    private final FieldType fieldType;
    private final int enumerator;

    public PushdownFieldDesignator(@Nonnull final FieldType fieldType, final int enumerator) {
        this.fieldType = fieldType;
        this.enumerator = enumerator;
    }

    public static PushdownFieldDesignator of(final FieldType fieldType, final int enumerator) {
        return new PushdownFieldDesignator(fieldType, enumerator);
    }

    @Override
    public String toString() {
        return "FieldDesignator{" +
               "fieldType=" + fieldType +
               ", enumerator=" + enumerator +
               '}';
    }
}
