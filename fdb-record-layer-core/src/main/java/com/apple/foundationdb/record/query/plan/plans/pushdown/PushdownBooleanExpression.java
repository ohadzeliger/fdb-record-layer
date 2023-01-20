/*
 * PushdownBooleanExpression.java
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
 * A predicate implementation that has a boolean operator and operands.
 */
public class PushdownBooleanExpression implements PushdownPredicate {
    @Nonnull
    private final PushdownBooleanOperator operator;
    @Nonnull
    private final List<PushdownPredicate> operands;

    private PushdownBooleanExpression(@Nonnull final PushdownBooleanOperator operator, @Nonnull final List<PushdownPredicate> operands) {
        this.operator = operator;
        this.operands = operands;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static PushdownBooleanExpression and(PushdownPredicate op1, PushdownPredicate op2) {
        return newBuilder().withOperator(PushdownBooleanOperator.AND).withOperand(op1).withOperand(op2).build();
    }

    public static PushdownBooleanExpression or(PushdownPredicate op1, PushdownPredicate op2) {
        return newBuilder().withOperator(PushdownBooleanOperator.OR).withOperand(op1).withOperand(op2).build();
    }

    public static PushdownBooleanExpression not(PushdownPredicate op1, PushdownPredicate op2) {
        return newBuilder().withOperator(PushdownBooleanOperator.NOT).withOperand(op1).build();
    }

    @Nonnull
    public PushdownBooleanOperator getOperator() {
        return operator;
    }

    @Nonnull
    public List<PushdownPredicate> getOperands() {
        return operands;
    }

    public static class Builder {
        private PushdownBooleanOperator operator;
        private final List<PushdownPredicate> operands = new ArrayList<>(2);

        public PushdownBooleanExpression build() {
            if (operator == null) {
                throw new IllegalArgumentException("Operator cannot be null");
            }
            if (operator.getRequiredOperands() != operands.size()) {
                throw new IllegalArgumentException("Number of operands mismatch. Expected " + operator.getRequiredOperands() + " but got " + operands.size());
            }

            return new PushdownBooleanExpression(operator, Collections.unmodifiableList(operands));
        }

        public Builder withOperator(PushdownBooleanOperator operator) {
            this.operator = operator;
            return this;
        }

        public Builder withOperand(PushdownPredicate operand) {
            operands.add(operand);
            return this;
        }
    }
}
