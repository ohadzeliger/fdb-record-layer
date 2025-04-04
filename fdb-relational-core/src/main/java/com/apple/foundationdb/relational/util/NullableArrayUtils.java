/*
 * NullableArrayUtils.java
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

package com.apple.foundationdb.relational.util;

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.record.expressions.RecordKeyExpressionProto;
import com.apple.foundationdb.record.query.plan.cascades.typing.Type;
import com.apple.foundationdb.record.query.plan.cascades.typing.TypeRepository;

import com.google.protobuf.Descriptors;

import javax.annotation.Nonnull;

/**
 * A Utils class that holds logic related to nullable arrays.
 * Nullable Arrays are arrays that, if unset, will be NULL.
 * Non-nullable arrays are arrays that, if unset, will be empty list.
 */
@API(API.Status.EXPERIMENTAL)
public final class NullableArrayUtils {
    public static final String REPEATED_FIELD_NAME = "values";

    private NullableArrayUtils() {
        throw new IllegalStateException("Utility class");
    }

    public static String getRepeatedFieldName() {
        return REPEATED_FIELD_NAME;
    }

    public static boolean isWrappedArrayDescriptor(String fieldName, Descriptors.Descriptor parentDescriptor) {
        try {
            Descriptors.Descriptor childDescriptor = parentDescriptor.findFieldByName(fieldName).getMessageType();
            return isWrappedArrayDescriptor(childDescriptor);
        } catch (Exception ex) {
            return false;
        }
    }

    public static boolean isWrappedArrayDescriptor(@Nonnull final Descriptors.Descriptor descriptor) {
        return descriptor.getFields().size() == 1 && REPEATED_FIELD_NAME.equals(descriptor.getFields().get(0).getName()) && descriptor.findFieldByName(REPEATED_FIELD_NAME).isRepeated();
    }

    /**
     * Adds the wrapped array structure in key expressions if the schema doesn't contain non-nullable array.
     * For example, reviews.rating will change to reviews.values.rating
     * @param keyExpression The key expression to modify.
     * @param record The record of the table.
     * @param containsNullableArray true if nullable arrays are to be found, otherwise false.
     * @return modified key expression where any nullable array is wrapped.
     *
     * TODO Add the wrapped array structure for nullable arrays.
     */
    public static RecordKeyExpressionProto.KeyExpression wrapArray(RecordKeyExpressionProto.KeyExpression keyExpression,
                                                                   final Type.Record record,
                                                                   boolean containsNullableArray) {
        if (!containsNullableArray) {
            return keyExpression;
        }
        final var typeRepositoryBuilder = TypeRepository.newBuilder();
        record.defineProtoType(typeRepositoryBuilder);
        final var parentDescriptor = typeRepositoryBuilder.build().getMessageDescriptor(record);
        return wrapArray(keyExpression, parentDescriptor, containsNullableArray);
    }

    /*
    Add the wrapped array structure in key expressions if the schema doesn't contain non-nullable array.
    For example, reviews.rating -> reviews.values.rating
    (TODO): Add the wrapped array structure for nullable arrays.
     */
    public static RecordKeyExpressionProto.KeyExpression wrapArray(RecordKeyExpressionProto.KeyExpression keyExpression,
                                                              Descriptors.Descriptor parentDescriptor,
                                                              boolean containsNullableArray) {
        if (!containsNullableArray) {
            return keyExpression;
        }

        return wrapArrayInternal(keyExpression, parentDescriptor);
    }

    private static RecordKeyExpressionProto.KeyExpression wrapArrayInternal(RecordKeyExpressionProto.KeyExpression keyExpression,
                                                                      Descriptors.Descriptor parentDescriptor) {
        // handle concat (straightforward recursion)
        if (keyExpression.hasThen()) {
            final var newThenBuilder = RecordKeyExpressionProto.Then.newBuilder();
            for (final var child : keyExpression.getThen().getChildList()) {
                newThenBuilder.addChild(wrapArrayInternal(child, parentDescriptor));
            }
            return RecordKeyExpressionProto.KeyExpression.newBuilder().setThen(newThenBuilder).build();
        }

        // handle nested field
        if (keyExpression.hasNesting()) {
            final var parent = keyExpression.getNesting().getParent();
            final var parentFieldName = parent.getFieldName();
            final var child = keyExpression.getNesting().getChild();
            // check if the PB descriptor has the parent represented as field -> VALUES, if so, align the key expression.
            if (isWrappedArrayDescriptor(parentFieldName, parentDescriptor)) {
                final var wrappedParent = splitFieldIntoNestedWithValues(parent);
                // recurse for child.
                final var wrappedChild = wrapArrayInternal(child,
                        parentDescriptor.findFieldByName(parentFieldName).getMessageType().findFieldByName(REPEATED_FIELD_NAME).getMessageType());
                // the child is actually a grand child (since parent->child is actually parent->values->child), fix that.
                final var newChild = RecordKeyExpressionProto.KeyExpression.newBuilder()
                        .setNesting(RecordKeyExpressionProto.Nesting.newBuilder()
                                .setParent(wrappedParent.getChild().getField())
                                .setChild(wrappedChild))
                        .build();
                return RecordKeyExpressionProto.KeyExpression.newBuilder()
                        .setNesting(RecordKeyExpressionProto.Nesting.newBuilder()
                                .setParent(wrappedParent.getParent())
                                .setChild(newChild))
                        .build();
            } else {
                final var wrappedChild = wrapArrayInternal(child, parentDescriptor.findFieldByName(parentFieldName).getMessageType());
                return RecordKeyExpressionProto.KeyExpression.newBuilder()
                        .setNesting(RecordKeyExpressionProto.Nesting.newBuilder()
                                .setParent(parent)
                                .setChild(wrappedChild))
                        .build();
            }
        }

        // check key expression's field
        if (keyExpression.hasField()) {
            if (NullableArrayUtils.isWrappedArrayDescriptor(keyExpression.getField().getFieldName(), parentDescriptor)) {
                return RecordKeyExpressionProto.KeyExpression.newBuilder().setNesting(splitFieldIntoNestedWithValues(keyExpression.getField())).build();
            } else {
                return keyExpression;
            }
        }

        // grouping key expression
        if (keyExpression.hasGrouping()) {
            final var newWholeKey = wrapArrayInternal(keyExpression.getGrouping().getWholeKey(), parentDescriptor);
            return RecordKeyExpressionProto.KeyExpression.newBuilder().setGrouping(keyExpression.getGrouping().toBuilder().setWholeKey(newWholeKey)).build();
        }

        // split key expression
        if (keyExpression.hasSplit()) {
            final var newJoined = wrapArrayInternal(keyExpression.getSplit().getJoined(), parentDescriptor);
            return RecordKeyExpressionProto.KeyExpression.newBuilder().setSplit(keyExpression.getSplit().toBuilder().setJoined(newJoined)).build();
        }

        // function key expression.
        if (keyExpression.hasFunction()) {
            final var newArguments = wrapArrayInternal(keyExpression.getFunction().getArguments(), parentDescriptor);
            return RecordKeyExpressionProto.KeyExpression.newBuilder().setFunction(keyExpression.getFunction().toBuilder().setArguments(newArguments)).build();
        }

        // covering key expression.
        if (keyExpression.hasKeyWithValue()) {
            final var newInnerKey = wrapArrayInternal(keyExpression.getKeyWithValue().getInnerKey(), parentDescriptor);
            return RecordKeyExpressionProto.KeyExpression.newBuilder().setKeyWithValue(keyExpression.getKeyWithValue().toBuilder().setInnerKey(newInnerKey)).build();
        }

        // key expression containing list.
        if (keyExpression.hasList()) {
            final var newListBuilder = RecordKeyExpressionProto.List.newBuilder();
            for (final var listItem : keyExpression.getList().getChildList()) {
                newListBuilder.addChild(wrapArrayInternal(listItem, parentDescriptor));
            }
            return RecordKeyExpressionProto.KeyExpression.newBuilder().setList(newListBuilder).build();
        }

        return keyExpression;
    }

    // wrap repeated fields in a Field type keyExpression
    private static RecordKeyExpressionProto.Nesting splitFieldIntoNestedWithValues(@Nonnull final RecordKeyExpressionProto.Field original) {
        final var nestedArrayBuilder = RecordKeyExpressionProto.Field.newBuilder()
                .setFieldName(original.getFieldName())
                .setFanType(RecordKeyExpressionProto.Field.FanType.SCALAR)
                .setNullInterpretation(original.getNullInterpretation());
        final var arrayValueBuilder = RecordKeyExpressionProto.KeyExpression.newBuilder()
                .setField(RecordKeyExpressionProto.Field.newBuilder()
                        .setFieldName(REPEATED_FIELD_NAME)
                        .setFanType(original.getFanType())
                        .setNullInterpretation(original.getNullInterpretation()));
        return RecordKeyExpressionProto.Nesting.newBuilder().setParent(nestedArrayBuilder).setChild(arrayValueBuilder).build();
    }
}
