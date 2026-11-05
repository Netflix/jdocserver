/*
 * Copyright 2026 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.netflix.tools.jdocserver.test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.netflix.tools.jdocserver.JdocServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class BrowserTest {
    private static final URI TARGET = URI.create("http://127.0.0.1:12345/type?name=java.util.Map.Entry&test=value");

    @Test
    void moduleDoesNotRequireJavaDesktop() {
        assertFalse(JdocServer.class.getModule().getDescriptor().requires().stream()
                .anyMatch(requirement -> requirement.name().equals("java.desktop")));
    }

    @Test
    void usesMacUrlOpener() throws Exception {
        assertEquals(List.of("open", TARGET.toASCIIString()), command("Mac OS X"));
    }

    @Test
    void usesLinuxUrlOpener() throws Exception {
        assertEquals(List.of("xdg-open", TARGET.toASCIIString()), command("Linux"));
    }

    @Test
    void usesWindowsUrlHandlerWithoutAShell() throws Exception {
        assertEquals(
                List.of("rundll32", "url.dll,FileProtocolHandler", TARGET.toASCIIString()),
                command("Windows 11"));
        assertEquals(
                List.of("rundll32", "url.dll,FileProtocolHandler", TARGET.toASCIIString()),
                command("Windows Server 2025"));
    }

    @Test
    void rejectsUnsupportedOperatingSystems() {
        var failure = assertThrows(InvocationTargetException.class, () -> command("Unknown OS"));
        var cause = assertInstanceOf(IOException.class, failure.getCause());
        assertEquals("Browser launching is not supported on Unknown OS", cause.getMessage());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void launchesUrlWithoutJavaDesktop(@TempDir Path temporary) throws Exception {
        createOpener(temporary, 0);
        assertEquals(0, launch(temporary), readOutput(temporary));
        assertEquals(List.of(TARGET.toASCIIString()), Files.readAllLines(temporary.resolve("target")));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void reportsOpenerFailure(@TempDir Path temporary) throws Exception {
        createOpener(temporary, 7);
        assertEquals(1, launch(temporary));
        assertTrue(readOutput(temporary).contains("Cannot browse " + TARGET + ": URL opener exited with status 7"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void reportsMissingOpener(@TempDir Path temporary) throws Exception {
        assertEquals(1, launch(temporary));
        assertTrue(readOutput(temporary).contains("Cannot browse " + TARGET));
        assertTrue(readOutput(temporary).contains("xdg-open"));
    }

    public static void main(String[] arguments) throws Exception {
        var method = JdocServer.class.getDeclaredMethod("browse", URI.class);
        method.setAccessible(true);
        method.invoke(null, TARGET);
    }

    private static void createOpener(Path directory, int status) throws IOException {
        Path opener = Files.writeString(directory.resolve("xdg-open"), "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$BROWSE_TARGET\"\nexit " + status + "\n");
        assertTrue(opener.toFile()
                         .setExecutable(true));
    }

    private static int launch(Path directory) throws Exception {
        String classPath = Path.of(BrowserTest.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI())
                + File.pathSeparator + Path.of(JdocServer.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java")
                .toString(),
                        "--limit-modules", "java.base,java.compiler", "--class-path", classPath, BrowserTest.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(directory.resolve("output")
                        .toFile());
        builder.environment().put("PATH", directory.toString());
        builder.environment().put("BROWSE_TARGET", directory.resolve("target")
                .toString());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "URL opener did not finish");
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor();
            }
        }
    }

    private static String readOutput(Path directory) throws IOException {
        return Files.readString(directory.resolve("output"));
    }

    private static Object command(String osName) throws Exception {
        var method = JdocServer.class.getDeclaredMethod("browseCommand", String.class, URI.class);
        method.setAccessible(true);
        return method.invoke(null, osName, TARGET);
    }
}
