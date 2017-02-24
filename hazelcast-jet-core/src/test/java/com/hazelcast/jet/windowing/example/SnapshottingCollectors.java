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

package com.hazelcast.jet.windowing.example;

import com.hazelcast.jet.Distributed;

public final class SnapshottingCollectors {

    private SnapshottingCollectors() {
    }

    public static <T> SnapshottingCollector<T, ?, Long>
    summingLong(Distributed.ToLongFunction<? super T> mapper) {
        return SnapshottingCollector.of(
                () -> new long[1],
                (a, t) -> {
                    a[0] += mapper.applyAsLong(t);
                },
                (a, b) -> {
                    a[0] += b[0];
                    return a;
                },
                a -> new long[]{a[0]},
                a -> a[0]);
    }
}
