/*
 * PushdownNumericExpression.java
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
 * A numeric expression that contains an operator adn 2 operands.
 */
public class PushdownNumericExpression implements PushdownValue {
    @Nonnull
    private final PushdownNumericOperator operator;
    @Nonnull
    private final List<? extends PushdownValue> operands;

    private PushdownNumericExpression(@Nonnull final PushdownNumericOperator operator, @Nonnull final List<? extends PushdownValue> operands) {
        this.operator = operator;
        this.operands = operands;
    }

    public static PushdownNumericExpression plus(PushdownValue op1, PushdownValue op2) {
        return newBuilder().withOperator(PushdownNumericOperator.PLUS).withOperand(op1).withOperand(op2).build();
    }

    public static PushdownNumericExpression minus(PushdownValue op1, PushdownValue op2) {
        return newBuilder().withOperator(PushdownNumericOperator.MINUS).withOperand(op1).withOperand(op2).build();
    }

    public static PushdownNumericExpression times(PushdownValue op1, PushdownValue op2) {
        return newBuilder().withOperator(PushdownNumericOperator.TIMES).withOperand(op1).withOperand(op2).build();
    }

    public static PushdownNumericExpression divide(PushdownValue op1, PushdownValue op2) {
        return newBuilder().withOperator(PushdownNumericOperator.DIVIDE).withOperand(op1).withOperand(op2).build();
    }

    public static PushdownNumericExpression mod(PushdownValue op1, PushdownValue op2) {
        return newBuilder().withOperator(PushdownNumericOperator.MOD).withOperand(op1).withOperand(op2).build();
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    @Nonnull
    public PushdownNumericOperator getOperator() {
        return operator;
    }

    @Nonnull
    public List<? extends PushdownValue> getOperands() {
        return operands;
    }

    public static class Builder {
        private PushdownNumericOperator operator;
        private final List<PushdownValue> operands = new ArrayList<>(2);

        public PushdownNumericExpression build() {
            if (operator == null) {
                throw new IllegalArgumentException("Operator cannot be null");
            }
            if (operands.size() != 2) {
                throw new IllegalArgumentException("Number of operands mismatch. Expected 2 " + " but got " + operands.size());
            }

            return new PushdownNumericExpression(operator, Collections.unmodifiableList(operands));
        }

        public Builder withOperator(PushdownNumericOperator operator) {
            this.operator = operator;
            return this;
        }

        public Builder withOperand(PushdownValue operand) {
            operands.add(operand);
            return this;
        }
    }

}
