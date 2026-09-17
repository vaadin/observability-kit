/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.UI;

class InteractionTrailTest {

    private static InteractionStep step(String caption, String value) {
        return new InteractionStep("com.example.Field", caption, "value",
                "mSync", value);
    }

    private static InteractionStep click(String caption) {
        return new InteractionStep("com.example.Button", caption, "click",
                "event", null);
    }

    @Test
    void oneTrailPerUi() {
        UI ui = new UI();
        Assertions.assertSame(InteractionTrail.of(ui), InteractionTrail.of(ui));
        Assertions.assertNotSame(InteractionTrail.of(ui),
                InteractionTrail.of(new UI()));
    }

    @Test
    void noUiNoTrail() {
        Assertions.assertNull(InteractionTrail.of(null));
    }

    @Test
    void keepsTheStepsInTheOrderTheyHappened() {
        InteractionTrail trail = InteractionTrail.of(new UI());
        trail.add(step("Order number", "AC-10482"));
        trail.add(step("Reason", "Defective"));
        trail.add(click("Process return"));

        Assertions.assertEquals(
                List.of("Order number", "Reason", "Process return"),
                trail.snapshot().stream().map(InteractionStep::caption)
                        .toList());
    }

    @Test
    void oldestStepFallsOffOnceTheTrailIsFull() {
        InteractionTrail trail = InteractionTrail.of(new UI());
        for (int i = 0; i <= InteractionTrail.MAX_STEPS; i++) {
            trail.add(click("Button " + i));
        }

        List<InteractionStep> steps = trail.snapshot();
        Assertions.assertEquals(InteractionTrail.MAX_STEPS, steps.size());
        Assertions.assertEquals("Button 1", steps.get(0).caption(),
                "the first click should have been evicted");
    }

    @Test
    void typingIntoOneFieldIsOneStepWithTheLastValue() {
        // Eager value-change mode reports an invocation per keystroke; eight
        // of them must not push the rest of the trail out.
        InteractionTrail trail = InteractionTrail.of(new UI());
        trail.add(click("Process return"));
        trail.add(step("Order number", "A"));
        trail.add(step("Order number", "AC"));
        trail.add(step("Order number", "AC-1"));

        List<InteractionStep> steps = trail.snapshot();
        Assertions.assertEquals(2, steps.size(),
                "repeats of the same action should collapse, got: " + steps);
        Assertions.assertEquals("AC-1", steps.get(1).value(),
                "and keep the value the user ended up with");
    }

    @Test
    void comingBackToAFieldAfterAnotherIsItsOwnStep() {
        InteractionTrail trail = InteractionTrail.of(new UI());
        trail.add(step("Order number", "AC-1"));
        trail.add(step("Reason", "Defective"));
        trail.add(step("Order number", "AC-2"));

        Assertions.assertEquals(3, trail.snapshot().size(),
                "only consecutive repeats collapse; the order matters");
    }
}
