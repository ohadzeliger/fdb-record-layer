/*
 * PushdownComparison.java
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

/**
 * A Comparison operator.
 * Note that the semantics of comparisons require that the types of the operands match (no implicit type conversions
 * is expected to be performed).
 */
public enum PushdownComparison {
    EQUALS(2),
    NOT_EQUALS(2),
    GTE(2),
    LTE(2),
    GT(2),
    LT(2),
    IS_NULL(1),
    IS_NOT_NULL(1)
    ;

    private final int requiredOperands;

    PushdownComparison(final int requiredOperands) {

        this.requiredOperands = requiredOperands;
    }

    public int getRequiredOperands() {
        return requiredOperands;
    }
}
