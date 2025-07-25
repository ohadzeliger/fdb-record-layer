/*
 * ExpressionPartitions.java
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

package com.apple.foundationdb.record.query.plan.cascades;

import com.apple.foundationdb.record.query.plan.cascades.expressions.RelationalExpression;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helpers for collections of {@link ExpressionPartition}s.
 */
public class ExpressionPartitions {
    private ExpressionPartitions() {
        // do not instantiate
    }

    @Nonnull
    public static <E extends RelationalExpression> List<ExpressionPartition<E>> rollUpTo(@Nonnull final Collection<ExpressionPartition<E>> expressionPartitions,
                                                                                         @Nonnull final ExpressionProperty<?> property) {
        return rollUpTo(expressionPartitions,
                ImmutableSet.of(property),
                (PartitionCreator<E, ExpressionPartition<E>>)ExpressionPartition::new);
    }

    @Nonnull
    protected static <E extends RelationalExpression, P extends ExpressionPartition<E>> List<P> rollUpTo(@Nonnull final Collection<P> expressionPartitions,
                                                                                                         @Nonnull final ExpressionProperty<?> property,
                                                                                                         @Nonnull final PartitionCreator<E, P> partitionCreator) {
        return rollUpTo(expressionPartitions, ImmutableSet.of(property), partitionCreator);
    }

    @Nonnull
    public static <E extends RelationalExpression> List<ExpressionPartition<E>> rollUpTo(@Nonnull final Collection<ExpressionPartition<E>> expressionPartitions,
                                                                                         @Nonnull final Set<ExpressionProperty<?>> rollupProperties) {
        return rollUpTo(expressionPartitions, rollupProperties,
                (PartitionCreator<E, ExpressionPartition<E>>)ExpressionPartition::new);
    }

    @Nonnull
    static <E extends RelationalExpression, P extends ExpressionPartition<E>> List<P> rollUpTo(@Nonnull final Collection<P> partitions,
                                                                                               @Nonnull final Set<ExpressionProperty<?>> rollupProperties,
                                                                                               @Nonnull final PartitionCreator<E, P> partitionCreator) {
        final Map<Map<ExpressionProperty<?>, ?>, Map<E, Map<ExpressionProperty<?>, ?>>> rolledUpMap =
                new LinkedHashMap<>();
        for (final P partition : partitions) {
            final var groupingPropertyMap = partition.getPartitionPropertiesMap();
            final Map<ExpressionProperty<?>, ?> filteredPropertiesMap =
                    groupingPropertyMap
                            .entrySet()
                            .stream()
                            .filter(attributeEntry ->
                                    rollupProperties.contains(attributeEntry.getKey()))
                            .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
            rolledUpMap.compute(filteredPropertiesMap, (key, oldValue) -> {
                if (oldValue == null) {
                    return new LinkedIdentityMap<>(partition.getNonPartitioningPropertiesMap());
                }
                oldValue.putAll(partition.getNonPartitioningPropertiesMap());
                return oldValue;
            });
        }

        final var resultsBuilder = ImmutableList.<P>builder();
        for (final var entry : rolledUpMap.entrySet()) {
            resultsBuilder.add(partitionCreator.create(entry.getKey(), entry.getValue()));
        }
        return resultsBuilder.build();
    }

    @Nonnull
    protected static <E extends RelationalExpression> List<ExpressionPartition<E>> toPartitions(@Nonnull final ExpressionPropertiesMap<E> propertiesMap) {
        return toPartitions(propertiesMap, (PartitionCreator<E, ExpressionPartition<E>>)ExpressionPartition::new);
    }

    @Nonnull
    protected static <E extends RelationalExpression, P extends ExpressionPartition<E>> List<P> toPartitions(@Nonnull final ExpressionPropertiesMap<E> propertiesMap,
                                                                                                             @Nonnull final PartitionCreator<E, P> partitionCreator) {
        return toPartitions(propertiesMap.getPartitioningPropertiesExpressionsMap(),
                propertiesMap.computeNonPartitioningPropertiesMap(), partitionCreator);
    }

    @Nonnull
    private static <E extends RelationalExpression, P extends ExpressionPartition<E>> List<P> toPartitions(@Nonnull final Map<Map<ExpressionProperty<?>, ?>, ? extends Set<E>> partitioningPropertiesMap,
                                                                                                           @Nonnull Map<E, Map<ExpressionProperty<?>, ?>> nonPartitioningPropertiesMap,
                                                                                                           @Nonnull final PartitionCreator<E, P> partitionCreator) {
        return partitioningPropertiesMap
                .entrySet()
                .stream()
                .map(entry -> {
                    final var partitioningPropertyMap = entry.getKey();
                    final var expressions = entry.getValue();
                    final var nonPartitioningPropertyMap = new LinkedIdentityMap<E, Map<ExpressionProperty<?>, ?>>();
                    for (final var expression : expressions) {
                        final var propertiesMapForExpression =
                                nonPartitioningPropertiesMap.get(expression);
                        nonPartitioningPropertyMap.put(expression, ImmutableMap.copyOf(propertiesMapForExpression));
                    }

                    //
                    // Note that the creator is not expected to blindly copy the maps handed in, however, the partition
                    // needs to own these maps. The grouping property map is not necessarily immutable nor is it owned
                    // by the partition. We need to defensively copy that map.
                    //
                    return partitionCreator.create(ImmutableMap.copyOf(partitioningPropertyMap), nonPartitioningPropertyMap);
                })
                .collect(ImmutableList.toImmutableList());
    }

    @FunctionalInterface
    protected interface PartitionCreator<E extends RelationalExpression, P extends ExpressionPartition<E>> {
        P create(@Nonnull Map<ExpressionProperty<?>, ?> groupingPropertyMap,
                 @Nonnull Map<E, Map<ExpressionProperty<?>, ?>> groupedPropertyMap);
    }
}
