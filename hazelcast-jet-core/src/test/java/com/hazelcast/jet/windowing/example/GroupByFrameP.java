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

import com.hazelcast.jet.AbstractProcessor;
import com.hazelcast.jet.Distributed.LongUnaryOperator;
import com.hazelcast.jet.Distributed.ToLongFunction;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;

/**
 * TODO javadoc
 * @param <T> Ingested type
 * @param <B> Accumulator type
 * @param <R> Accumulator result type
 */
public class GroupByFrameP<T, K, B, R> extends AbstractProcessor {
    private final SnapshottingCollector<T, B, R> tc;
    private final ToLongFunction<? super T> extractTimestampF;
    private final Function<? super T, K> extractKeyF;
    private final LongUnaryOperator toFrameSeqF;
    private final int bucketCount;
    private final Map<K, B>[] keyToBucketMaps;
    private long currentFrameSeq;
    private long frameSeqBase;

    private GroupByFrameP(
            int bucketCount,
            ToLongFunction<? super T> extractTimestampF,
            Function<? super T, K> extractKeyF,
            LongUnaryOperator toFrameSeqF,
            SnapshottingCollector<T, B, R> tc
    ) {
        this.tc = tc;
        this.extractTimestampF = extractTimestampF;
        this.extractKeyF = extractKeyF;
        this.toFrameSeqF = toFrameSeqF;
        this.bucketCount = bucketCount;
        this.keyToBucketMaps = new Map[bucketCount];
        Arrays.setAll(keyToBucketMaps, i -> new HashMap<>());
    }

    public static <T, B, R> GroupByFrameP groupByFrame(
            int bucketCount,
            ToLongFunction<? super T> extractTimestampF,
            LongUnaryOperator toFrameSeqF,
            SnapshottingCollector<T, B, R> tc) {
        return new GroupByFrameP<>(bucketCount, extractTimestampF, x -> true, toFrameSeqF, tc);
    }

    public static <T, K, B, R> GroupByFrameP groupByFrameAndKey(
            int bucketCount,
            ToLongFunction<? super T> extractTimestampF,
            Function<? super T, K> extractKeyF,
            LongUnaryOperator toFrameSeqF,
            SnapshottingCollector<T, B, R> tc) {
        return new GroupByFrameP<>(bucketCount, extractTimestampF, extractKeyF, toFrameSeqF, tc);
    }

    @Override
    protected boolean tryProcess0(@Nonnull Object item) {
        T t = (T) item;
        K key = extractKeyF.apply(t);
        final long itemFrameSeq = toFrameSeqF.applyAsLong(extractTimestampF.applyAsLong(t));
        ensureFrameSeqInitialized(itemFrameSeq);
        if (itemFrameSeq <= currentFrameSeq - bucketCount) {
            System.out.println("Late event: " + t);
            return true;
        }
        if (itemFrameSeq > currentFrameSeq) {
            slideTo(itemFrameSeq);
        }
        B bucket = keyToBucketMaps[toBucketIndex(itemFrameSeq)].computeIfAbsent(key, x -> tc.supplier().get());
        tc.accumulator().accept(bucket, t);
        return true;
    }

    private void slideTo(long itemFrameSeq) {
        final long evictFrom = Math.max(frameSeqBase, currentFrameSeq - bucketCount + 1);
        final long evictUntil = itemFrameSeq - bucketCount + 1;
        for (long seq = evictFrom; seq < evictUntil; seq++) {
            final int bucketIndex = toBucketIndex(seq);
            for (Entry<K, B> e : keyToBucketMaps[bucketIndex].entrySet()) {
                B bucket = e.getValue();
                emit(new KeyedFrame<>(seq, e.getKey(), bucket));
            }
           keyToBucketMaps[bucketIndex] = new HashMap<>();
        }
        currentFrameSeq = itemFrameSeq;
    }

    private int toBucketIndex(long tsPeriod) {
        return (int) Math.floorMod(tsPeriod, bucketCount);
    }

    private void ensureFrameSeqInitialized(long frameSeq) {
        if (currentFrameSeq == 0) {
            currentFrameSeq = frameSeq;
            frameSeqBase = frameSeq;
        }
    }

    public static final class KeyedFrame<K, V> {
        private final long seq;
        private final K key;
        private final V value;

        KeyedFrame(long seq, K key, V value) {
            this.seq = seq;
            this.key = key;
            this.value = value;
        }

        public long getSeq() {
            return seq;
        }

        public K getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }

        @Override
        public String toString() {
            return "KeyedWindowEntry{seq=" + seq + ", key=" + key + ", value=" + value + '}';
        }
    }
}