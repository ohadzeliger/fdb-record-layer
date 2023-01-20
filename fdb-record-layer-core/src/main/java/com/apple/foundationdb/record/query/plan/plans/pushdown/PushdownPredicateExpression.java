/*
 * PushdownPredicateExpression.java
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An expression that contains a comparison: operator and operands.
 */
public class PushdownPredicateExpression {
    @Nonnull
    private final PushdownComparison comparison;
    @Nonnull
    private final List<PushdownValue<?>> operands;

    private PushdownPredicateExpression(@Nonnull final PushdownComparison comparison, @Nonnull final List<PushdownValue<?>> operands) {
        this.comparison = comparison;
        this.operands = operands;
    }

    public static PushdownPredicateExpression equals(PushdownValue<?> op1, PushdownValue<?> op2) {
        return newBuilder().withComparison(PushdownComparison.EQUALS).withOperand(op1).withOperand(op2).build();
    }

    public static PushdownPredicateExpression notEquals(PushdownValue<?> op1, PushdownValue<?> op2) {
        return newBuilder().withComparison(PushdownComparison.NOT_EQUALS).withOperand(op1).withOperand(op2).build();
    }

    public static PushdownPredicateExpression greaterThanOrEquals(PushdownValue<?> op1, PushdownValue<?> op2) {
        return newBuilder().withComparison(PushdownComparison.GTE).withOperand(op1).withOperand(op2).build();
    }

    public static PushdownPredicateExpression greaterThan(PushdownValue<?> op1, PushdownValue<?> op2) {
        return newBuilder().withComparison(PushdownComparison.GT).withOperand(op1).withOperand(op2).build();
    }

    public static PushdownPredicateExpression lessThanOrEquals(PushdownValue<?> op1, PushdownValue<?> op2) {
        return newBuilder().withComparison(PushdownComparison.LTE).withOperand(op1).withOperand(op2).build();
    }

    public static PushdownPredicateExpression lessThan(PushdownValue<?> op1, PushdownValue<?> op2) {
        return newBuilder().withComparison(PushdownComparison.LT).withOperand(op1).withOperand(op2).build();
    }

    public static PushdownPredicateExpression isNull(PushdownValue<?> op1) {
        return newBuilder().withComparison(PushdownComparison.IS_NULL).withOperand(op1).build();
    }

    public static PushdownPredicateExpression isNotNull(PushdownValue<?> op1) {
        return newBuilder().withComparison(PushdownComparison.IS_NOT_NULL).withOperand(op1).build();
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    @Nonnull
    public PushdownComparison getComparison() {
        return comparison;
    }

    @Nonnull
    public List<PushdownValue<?>> getOperands() {
        return operands;
    }

    public static class Builder {
        private PushdownComparison comparison;
        private final List<PushdownValue<?>> operands = new ArrayList<>(2);

        public PushdownPredicateExpression build() {
            if (comparison == null) {
                throw new IllegalArgumentException("Comparison cannot be null");
            }
            if (comparison.getRequiredOperands() != operands.size()) {
                throw new IllegalArgumentException("Number of operands mismatch. Expected " + comparison.getRequiredOperands() + " but got " + operands.size());
            }
            // TODO: Check for types of operands

            return new PushdownPredicateExpression(comparison, Collections.unmodifiableList(operands));
        }

        public Builder withComparison(PushdownComparison comparison) {
            this.comparison = comparison;
            return this;
        }

        public Builder withOperand(PushdownValue<?> operand) {
            operands.add(operand);
            return this;
        }
    }
}
