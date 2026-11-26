/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.pbx3cx.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * One JAR for openHAB 5.2 and 5.3: no class of the binding may use the servlet API or Jetty.
 * <p>
 * openHAB 5.3 replaced Jetty 9 and {@code javax.servlet} with Jetty 12 and {@code jakarta.servlet}; a bundle
 * that imports either side starts on one version only (on 5.3 a {@code javax.servlet} import leaves it INSTALLED
 * with "Unresolved requirement"). The binding talks to 3CX with {@code java.net.http} alone, so it needs neither.
 * Until 1.1.0 the JAR imported {@code javax.servlet} for a CRM webhook servlet that was never registered.
 *
 * @author Nanna Agesen - Initial contribution
 */
@NonNullByDefault
class Pbx3cxNoServletApiTest {

    private static final String[] FORBIDDEN = { "javax/servlet", "jakarta/servlet", "org/eclipse/jetty" };

    @Test
    void noClassUsesTheServletApiOrJetty() throws IOException, URISyntaxException {
        Path classes = Path.of(Pbx3cxServerHandler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(classes), "expected the compiled classes directory, got " + classes);
        List<String> found = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))::iterator) {
                checked++;
                // class names in the constant pool are plain modified UTF-8: ASCII for these names
                String bytes = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                for (String f : FORBIDDEN) {
                    if (bytes.contains(f)) {
                        found.add(classes.relativize(p) + " -> " + f);
                    }
                }
            }
        }
        assertTrue(checked > 10, "only " + checked + " classes found in " + classes);
        assertEquals(List.of(), found);
    }
}
