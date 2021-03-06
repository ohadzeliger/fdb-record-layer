/*
 * AdjustMatchRule.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2019 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.query.plan.temp.rules;

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.record.query.plan.temp.ExpressionRef;
import com.apple.foundationdb.record.query.plan.temp.MatchCandidate;
import com.apple.foundationdb.record.query.plan.temp.MatchInfo;
import com.apple.foundationdb.record.query.plan.temp.PartialMatch;
import com.apple.foundationdb.record.query.plan.temp.PlannerRule;
import com.apple.foundationdb.record.query.plan.temp.PlannerRuleCall;
import com.apple.foundationdb.record.query.plan.temp.RelationalExpression;
import com.apple.foundationdb.record.query.plan.temp.matchers.ExpressionMatcher;
import com.apple.foundationdb.record.query.plan.temp.matchers.PartialMatchMatcher;
import com.apple.foundationdb.record.query.plan.temp.matchers.PlannerBindings;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.SetMultimap;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A rule that attempts to improve an existing {@link PartialMatch} by <em>absorbing</em> an expression on the
 * candidate side.
 *
 * Currently the only such expression that can be absorbed is
 * {@link com.apple.foundationdb.record.query.plan.temp.expressions.MatchableSortExpression}.
 * TODO Maybe that expression should just be a generic property-defining expression or properties should be kept
 *      on quantifiers.
 * It is special in a way that there is no corresponding expression on the query side that is subsumed by that
 * expression. Absorbing such a candidate-side-only expression into the match allows us to fine-tune interesting
 * provided properties guaranteed by the candidate side.
 */
@API(API.Status.EXPERIMENTAL)
public class AdjustMatchRule extends PlannerRule<PartialMatch> {
    private static final ExpressionMatcher<PartialMatch> rootMatcher = PartialMatchMatcher.incompleteMatch();

    public AdjustMatchRule() {
        super(rootMatcher);
    }

    @Override
    @SuppressWarnings("java:S135")
    public void onMatch(@Nonnull PlannerRuleCall call) {
        final PlannerBindings bindings = call.getBindings();
        final PartialMatch incompleteMatch = bindings.get(rootMatcher);

        final ExpressionRef<? extends RelationalExpression> queryReference = incompleteMatch.getQueryRef();
        final MatchCandidate matchCandidate = incompleteMatch.getMatchCandidate();

        // for the already matching candidates
        final Set<ExpressionRef<? extends RelationalExpression>> matchedRefsForCandidate =
                queryReference.getPartialMatchesForCandidate(matchCandidate)
                        .stream()
                        .map(PartialMatch::getCandidateRef)
                        .collect(ImmutableSet.toImmutableSet());

        final SetMultimap<ExpressionRef<? extends RelationalExpression>, RelationalExpression> refToExpressionMap =
                matchCandidate.findReferencingExpressions(ImmutableList.of(queryReference));

        for (final Map.Entry<ExpressionRef<? extends RelationalExpression>, RelationalExpression> entry : refToExpressionMap.entries()) {
            final ExpressionRef<? extends RelationalExpression> candidateReference = entry.getKey();
            final RelationalExpression candidateExpression = entry.getValue();
            if (!matchedRefsForCandidate.contains(candidateReference)) {
                matchWithCandidate(incompleteMatch.getQueryRef(),
                        incompleteMatch.getQueryExpression(),
                        incompleteMatch.getMatchCandidate(),
                        candidateExpression).forEach(matchInfo ->
                        call.yieldPartialMatch(incompleteMatch.getBoundAliasMap(),
                                matchCandidate,
                                incompleteMatch.getQueryExpression(),
                                candidateReference,
                                matchInfo));
            }
        }
    }

    @Nonnull
    private Iterable<MatchInfo> matchWithCandidate(@Nonnull ExpressionRef<? extends RelationalExpression> group,
                                                   @Nonnull RelationalExpression expression,
                                                   @Nonnull MatchCandidate matchCandidate,
                                                   @Nonnull RelationalExpression candidateExpression) {
        Verify.verify(!candidateExpression.getQuantifiers().isEmpty());

        if (candidateExpression.getQuantifiers().size() > 1) {
            return ImmutableList.of();
        }

        final ExpressionRef<? extends RelationalExpression> otherRangesOver = Iterables.getOnlyElement(candidateExpression.getQuantifiers()).getRangesOver();

        if (!candidateExpression.getCorrelatedTo().equals(otherRangesOver.getCorrelatedTo())) {
            return ImmutableList.of();
        }

        final Set<PartialMatch> partialMatchesForCandidate = group.getPartialMatchesForCandidate(matchCandidate);
        return partialMatchesForCandidate.stream()
                .filter(partialMatch -> partialMatch.getCandidateRef() == otherRangesOver)
                .map(partialMatch ->
                        candidateExpression.adjustMatch(expression, partialMatch))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(ImmutableList.toImmutableList());
    }
}
