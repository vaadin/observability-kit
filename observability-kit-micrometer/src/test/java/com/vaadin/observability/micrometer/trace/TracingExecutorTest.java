/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.trace;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TracingExecutorTest {

    @Test
    void delegatesToWrappedExecutor() {
        AtomicBoolean ran = new AtomicBoolean();
        Executor inline = Runnable::run;
        new TracingExecutor(inline).execute(() -> ran.set(true));
        Assertions.assertTrue(ran.get(), "wrapped runnable should have run");
    }

    @Test
    void capturesAndForwardsCommandToDelegate() {
        AtomicReference<Runnable> captured = new AtomicReference<>();
        Executor capturing = captured::set;

        Runnable original = () -> {
        };
        new TracingExecutor(capturing).execute(original);

        // The delegate should have received SOMETHING (the snapshot-wrapped
        // command), not necessarily the original instance: a context-aware
        // wrapper is expected.
        Assertions.assertNotNull(captured.get());
    }

    @Test
    void rejectsNullDelegate() {
        Assertions.assertThrows(NullPointerException.class,
                () -> new TracingExecutor(null));
    }
}
