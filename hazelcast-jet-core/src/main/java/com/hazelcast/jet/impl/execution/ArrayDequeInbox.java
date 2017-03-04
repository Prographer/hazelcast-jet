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

package com.hazelcast.jet.impl.execution;

import com.hazelcast.jet.Inbox;
import com.hazelcast.jet.Watermark;

import java.util.ArrayDeque;

/**
 * Implements {@link Inbox} in terms of an {@link ArrayDeque}.
 */
public final class ArrayDequeInbox extends ArrayDeque<Object> implements Inbox {
    @Override
    public Object peek() {
        final Object item = super.peek();
        return size() != 1 || !(item instanceof Watermark) ? item : null;
    }

    @Override
    public Object poll() {
        return size() != 1 || !(super.peek() instanceof Watermark) ? super.poll() : null;
    }

    @Override
    public Object remove() {
        return Inbox.super.remove();
    }
}
