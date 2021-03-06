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

import com.hazelcast.jet.function.DistributedBinaryOperator;
import com.hazelcast.jet.function.DistributedBiFunction;
import com.hazelcast.jet.function.DistributedFunction;
import com.hazelcast.jet.function.DistributedSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class WindowOperationImpl<T, A, R> implements WindowOperation<T, A, R> {
    private final DistributedSupplier<A> createAccumulatorF;
    private final DistributedBiFunction<A, T, A> accumulateItemF;
    private final DistributedBinaryOperator<A> combineAccumulatorsF;
    private final DistributedBinaryOperator<A> deductAccumulatorF;
    private final DistributedFunction<A, R> finishAccumulationF;

    WindowOperationImpl(DistributedSupplier<A> createAccumulatorF,
                        DistributedBiFunction<A, T, A> accumulateItemF,
                        DistributedBinaryOperator<A> combineAccumulatorsF,
                        DistributedBinaryOperator<A> deductAccumulatorF,
                        DistributedFunction<A, R> finishAccumulationF
    ) {
        this.createAccumulatorF = createAccumulatorF;
        this.accumulateItemF = accumulateItemF;
        this.combineAccumulatorsF = combineAccumulatorsF;
        this.deductAccumulatorF = deductAccumulatorF;
        this.finishAccumulationF = finishAccumulationF;
    }

    @Override @Nonnull
    public DistributedSupplier<A> createAccumulatorF() {
        return createAccumulatorF;
    }

    @Override @Nonnull
    public DistributedBiFunction<A, T, A> accumulateItemF() {
        return accumulateItemF;
    }

    @Override @Nonnull
    public DistributedBinaryOperator<A> combineAccumulatorsF() {
        return combineAccumulatorsF;
    }

    @Override @Nullable
    public DistributedBinaryOperator<A> deductAccumulatorF() {
        return deductAccumulatorF;
    }

    @Override @Nonnull
    public DistributedFunction<A, R> finishAccumulationF() {
        return finishAccumulationF;
    }
}
