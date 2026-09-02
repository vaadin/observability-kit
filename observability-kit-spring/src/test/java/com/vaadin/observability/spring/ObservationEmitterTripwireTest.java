/**
 * Copyright (C) 2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * This module currently emits no Observations of its own — it only enriches the
 * HTTP observation Spring already emits. This tripwire fails when that stops
 * being true, so a new emitter cannot appear without its span surface being
 * pinned in a contract test, mirroring {@code ObservationContractTest} in
 * {@code observability-kit-micrometer} and
 * {@code DatabaseQueryObservationContractTest} in the starter.
 * <p>
 * Detection is a static-call regex over the sources — a deliberate 80% tool
 * whose failure modes (an emitter hiding the static call behind another name, a
 * javadoc spelling out a creation call) are loud rather than silent.
 */
class ObservationEmitterTripwireTest {

    @Test
    void noObservationEmittersWithoutAContractTest() throws IOException {
        Path root = Path.of("src/main/java");
        Assertions.assertTrue(Files.isDirectory(root),
                root + " not found: this test resolves sources against the "
                        + "module directory, so run it with the module as "
                        + "working directory (as Surefire does)");
        Pattern creation = Pattern.compile(
                "Observation\\s*\\.\\s*(createNotStarted|start)\\s*\\(");
        Set<String> emitters;
        try (Stream<Path> sources = Files.walk(root)) {
            emitters = sources.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> creation.matcher(read(p)).find())
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        Assertions.assertEquals(Set.of(), emitters,
                "this module gained an observation emitter: pin its span "
                        + "surface in a contract test before shipping it");
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
