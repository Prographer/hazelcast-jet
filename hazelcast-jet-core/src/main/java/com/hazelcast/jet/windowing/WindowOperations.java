/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.windowing;

import com.hazelcast.jet.accumulator.LinTrendAccumulator;
import com.hazelcast.jet.accumulator.LongAccumulator;
import com.hazelcast.jet.accumulator.MutableReference;
import com.hazelcast.jet.function.DistributedBinaryOperator;
import com.hazelcast.jet.function.DistributedFunction;
import com.hazelcast.jet.function.DistributedToLongFunction;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Utility class with factory methods for several useful windowing
 * operations.
 */
public final class WindowOperations {

    private WindowOperations() {
    }

    /**
     * Returns an operation that tracks the count of items in the window.
     */
    public static WindowOperation<Object, LongAccumulator, Long> counting() {
        return WindowOperation.of(
                LongAccumulator::new,
                (a, item) -> a.addExact(1),
                LongAccumulator::addExact,
                LongAccumulator::subtract,
                LongAccumulator::get
        );
    }

    /**
     * Returns an operation that tracks the sum of {@code Long}-typed items in
     * the window.
     */
    public static WindowOperation<Long, LongAccumulator, Long> summingToLong() {
        return WindowOperations.summingToLong(Long::longValue);
    }

    /**
     * Returns an operation that tracks the sum of the quantity returned by
     * {@code getValueF} applied to each item in the window.
     */
    public static <T> WindowOperation<T, LongAccumulator, Long> summingToLong(
            @Nonnull DistributedToLongFunction<T> getValueF
    ) {
        return WindowOperation.of(
                LongAccumulator::new,
                (a, item) -> a.addExact(getValueF.applyAsLong(item)),
                LongAccumulator::addExact,
                LongAccumulator::subtractExact,
                LongAccumulator::get
        );
    }

    /**
     * Returns an operation that computes a linear trend on the items in the
     * window. The operation will produce a {@code double}-valued coefficient
     * that approximates the rate of change of {@code y} as a function of
     * {@code x}, where {@code x} and {@code y} are {@code long} quantities
     * extracted from each item by the two provided functions.
     */
    public static <T> WindowOperation<T, LinTrendAccumulator, Double> linearTrend(
            @Nonnull DistributedToLongFunction<T> getX,
            @Nonnull DistributedToLongFunction<T> getY
    ) {
        return WindowOperation.of(
                LinTrendAccumulator::new,
                (a, item) -> a.accumulate(getX.applyAsLong(item), getY.applyAsLong(item)),
                LinTrendAccumulator::combine,
                LinTrendAccumulator::deduct,
                LinTrendAccumulator::finish
        );
    }

    /**
     * Returns an operation, that calculates multiple aggregations and returns their value in
     * {@code List<Object>}.
     * <p>
     * Useful, if you want to calculate multiple values for the same window.
     *
     * @param operations Operations to calculate.
     */
    @SafeVarargs
    public static <T> WindowOperation<T, List<Object>, List<Object>> allOf(
            WindowOperation<? super T, ?, ?> ... operations
    ) {
        WindowOperation[] untypedOps = operations;

        return WindowOperation.of(
                () -> {
                    Object[] res = new Object[untypedOps.length];
                    for (int i = 0; i < untypedOps.length; i++) {
                        res[i] = untypedOps[i].createAccumulatorF().get();
                    }
                    // wrap to List to have equals() implemented
                    return Arrays.asList(res);
                },
                (accs, item) -> {
                    for (int i = 0; i < untypedOps.length; i++) {
                        accs.set(i, untypedOps[i].accumulateItemF().apply(accs.get(i), item));
                    }
                    return accs;
                },
                (accs1, accs2) -> {
                    for (int i = 0; i < untypedOps.length; i++) {
                        accs1.set(i, untypedOps[i].combineAccumulatorsF().apply(accs1.get(i), accs2.get(i)));
                    }
                    return accs1;
                },
                // we support deduct, only if all operations do
                Stream.of(untypedOps).allMatch(o -> o.deductAccumulatorF() != null)
                        ? (accs1, accs2) -> {
                            for (int i = 0; i < untypedOps.length; i++) {
                                    accs1.set(i, untypedOps[i].deductAccumulatorF().apply(accs1.get(i), accs2.get(i)));
                                }
                                return accs1;
                            }
                        : null,
                accs -> {
                    Object[] res = new Object[untypedOps.length];
                    for (int i = 0; i < untypedOps.length; i++) {
                        res[i] = untypedOps[i].finishAccumulationF().apply(accs.get(i));
                    }
                    return Arrays.asList(res);
                }
        );
    }

    /**
     * A reducing operation maintains a value that starts out as the
     * operation's <em>identity</em> value and is being iteratively transformed
     * by applying the <em>combining</em> function to the current value and a
     * new item's value. The item is first passed through the provided <em>
     * mapping</em> function. Since the order of application of the function to
     * stream items is unspecified, the combining function must be commutative
     * and associative to produce meaningful results.
     * <p>
     * To support O(1) maintenance of a sliding window, a <em>deducting</em>
     * function should be supplied whose effect is the opposite of the
     * combining function, removing the contribution of an item to the reduced
     * result:
     * <pre>
     *     U acc = ... // any possible value
     *     U val = mapF.apply(item);
     *     U combined = combineF.apply(acc, val);
     *     U deducted = deductF.apply(combined, val);
     *     assert deducted.equals(acc);
     * </pre>
     *
     * @param identity the reducing operation's identity element
     * @param mapF a function to apply to the item before passing it to the combining
     *             function
     * @param combineF a function that combines a new item with the current result
     *                 and returns the new result
     * @param deductF an optional function that deducts the contribution of an
     *                item from the current result and returns the new result
     * @param <T> type of the stream item
     * @param <U> type of the reduced result
     */
    public static <T, U> WindowOperation<T, MutableReference<U>, U> reducing(
            U identity,
            DistributedFunction<? super T, ? extends U> mapF,
            DistributedBinaryOperator<U> combineF,
            DistributedBinaryOperator<U> deductF
    ) {
        return new WindowOperationImpl<>(
                () -> new MutableReference<>(identity),
                (a, t) -> a.set(combineF.apply(a.get(), mapF.apply(t))),
                (a, b) -> a.set(combineF.apply(a.get(), b.get())),
                deductF != null ? (a, b) -> a.set(deductF.apply(a.get(), b.get())) : null,
                MutableReference::get);
    }
}
