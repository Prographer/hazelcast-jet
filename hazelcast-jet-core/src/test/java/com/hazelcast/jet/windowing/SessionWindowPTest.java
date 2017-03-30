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

import com.hazelcast.jet.Processor;
import com.hazelcast.jet.Punctuation;
import com.hazelcast.jet.impl.util.ArrayDequeInbox;
import com.hazelcast.jet.impl.util.ArrayDequeOutbox;
import com.hazelcast.jet.stream.DistributedCollector;
import com.hazelcast.util.MutableLong;
import org.junit.Before;
import org.junit.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import static com.hazelcast.jet.Projections.entryKey;
import static com.hazelcast.jet.Util.entry;
import static java.util.Arrays.asList;
import static java.util.Collections.shuffle;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.IntStream.range;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class SessionWindowPTest {
    private static final int MAX_SEQ_GAP = 10;
    private SessionWindowP swp;
    private ArrayDequeInbox inbox;
    private ArrayDequeOutbox outbox;

    @Before
    public void before() {
        inbox = new ArrayDequeInbox();
        outbox = new ArrayDequeOutbox(1, new int[] {100});
        swp = new SessionWindowP<Entry<String, Long>, String, MutableLong, Long>(
                MAX_SEQ_GAP,
                Entry::getValue,
                entryKey(),
                DistributedCollector.of(
                        MutableLong::new,
                        (acc, e) -> acc.value++,
                        (a, b) -> MutableLong.valueOf(a.value + b.value),
                        a -> a.value
                ));
        swp.init(outbox, mock(Processor.Context.class));
    }

    @Test
    public void when_orderedEventsWithOneKey() {
        List<Entry<String, Long>> evs = eventsWithKey("a");
        assertCorrectness(evs);
    }

    @Test
    public void when_disorderedEventsWithOneKey() {
        List<Entry<String, Long>> evs = eventsWithKey("a");
        shuffle(evs);
        assertCorrectness(evs);
    }

    @Test
    public void when_orderedEventsWithThreeKeys() {
        List<Entry<String, Long>> evs = new ArrayList<>();
        evs.addAll(eventsWithKey("a"));
        evs.addAll(eventsWithKey("b"));
        evs.addAll(eventsWithKey("c"));
        assertCorrectness(evs);
    }

    @Test
    public void when_disorderedEVentsWithThreeKeys() {
        List<Entry<String, Long>> evs = new ArrayList<>();
        evs.addAll(eventsWithKey("a"));
        evs.addAll(eventsWithKey("b"));
        evs.addAll(eventsWithKey("c"));
        shuffle(evs);
        assertCorrectness(evs);
    }

    private void assertCorrectness(List<Entry<String, Long>> evs) {
        // When
        Set<String> keys = new HashSet<>();
        for (Entry<String, Long> ev : evs) {
            inbox.add(ev);
            keys.add(ev.getKey());
        }
        inbox.add(new Punctuation(100));
        swp.process(0, inbox);

        // Then
        Set<Session> expectedSessions = keys.stream().flatMap(SessionWindowPTest::expectedSessions).collect(toSet());
        Set<Object> actualSessions = range(0, expectedSessions.size()).mapToObj(x -> pollOutbox()).collect(toSet());
        assertEquals(expectedSessions, actualSessions);
        assertNull(pollOutbox());
        // Check against memory leaks
        assertTrue("keyToIvToAcc not empty", swp.keyToIvToAcc.isEmpty());
        assertTrue("deadlineToKeys not empty", swp.deadlineToKeys.isEmpty());
    }

    public static void main(String[] args) {
        for (int i = 0; i < 10; i++) {
            SessionWindowPTest test = new SessionWindowPTest();
            test.before();
            test.runBench();
        }
    }

    private void runBench() {
        Random rnd = ThreadLocalRandom.current();
        long start = System.nanoTime();
        long eventCount = 100_000_000;
        long keyCount = 2000;
        long eventsPerKey = eventCount / keyCount;
        long puncInterval = eventsPerKey / 10;
        int spread = 100;
        System.out.format("keyCount %,d eventsPerKey %,d puncInterval %,d%n", keyCount, eventsPerKey, puncInterval);
        for (long eventId = 0; eventId < eventsPerKey; eventId++) {
            for (long key = (eventId / puncInterval) % 2; key < keyCount; key += 2) {
                while (!swp.tryProcess0(entry(key, eventId + rnd.nextInt(spread))));
                while (!swp.tryProcess0(entry(key, eventId + rnd.nextInt(spread))));
            }
            if (eventId % puncInterval == 0) {
                Punctuation punc = new Punctuation(eventId + 1);
                int winCount = 0;
                while (!swp.tryProcessPunc0(punc)) {
                    while (pollOutbox() != null) winCount++;
                }
                while (pollOutbox() != null) winCount++;
                System.out.print(winCount + " ");
            }
        }
        long took = System.nanoTime() - start;
        System.out.format("%nThroughput %,3d events/second%n", SECONDS.toNanos(1) * eventCount / took);
    }

    private Object pollOutbox() {
        return outbox.queueWithOrdinal(0).poll();
    }

    private static List<Entry<String, Long>> eventsWithKey(String key) {
        return asList(
                // session 1: [12..22]
                entry(key, 1L),
                entry(key, 6L),
                entry(key, 12L),

                // session 2: [30..50]
                entry(key, 30L),
                entry(key, 35L),
                entry(key, 40L)
        );
    }

    private static Stream<Session<String, Long>> expectedSessions(String key) {
        return Stream.of(
                new Session<>(key, 1, 22, 3L),
                new Session<>(key, 30, 50, 3L)
        );
    }
}
