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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.ToolProvider;

import com.netflix.tools.jdocserver.DocumentationHandler;
import com.netflix.tools.jdocserver.JdocServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class JdocServerTest {
    @Test
    void handlerExposesItsJavaOptionContract() {
        var options = DocumentationHandler.optionChecker();

        assertEquals(1, options.isSupportedOption("--module-path"));
        assertEquals(1, options.isSupportedOption("--module-source-path"));
        assertEquals(1, options.isSupportedOption("--source-path"));
        assertEquals(1, options.isSupportedOption("--system"));
        assertEquals(1, options.isSupportedOption("--module"));
        assertEquals(0, options.isSupportedOption("--enable-preview"));
        assertEquals(1, options.isSupportedOption("-d"));
        assertEquals(-1, options.isSupportedOption("-proc:none"));
        assertEquals(-1, options.isSupportedOption("--browse"));
    }

    @Test
    void providerExposesServerAndJavaOptionContracts() {
        var options = new JdocServer();

        assertEquals(1, options.isSupportedOption("--bind-address"));
        assertEquals(1, options.isSupportedOption("--port"));
        assertEquals(0, options.isSupportedOption("--browse"));
        assertEquals(0, options.isSupportedOption("--aot-warmup"));
        assertEquals(0, options.isSupportedOption("--version"));
        assertEquals(1, options.isSupportedOption("--module-path"));
        assertEquals(-1, options.isSupportedOption("--unknown"));
    }

    @Test
    void providerDeclaresDocumentationToolOptions() throws Exception {
        try (var input = JdocServer.class.getModule().getResourceAsStream("META-INF/com.netflix.tools/tools/jdocserver.properties")) {
            assertNotNull(input);
            var properties = new Properties();
            properties.load(input);
            assertEquals("doc", properties.getProperty("type"));
            assertEquals(
                    Set.of("module-path", "processor-module-path", "upgrade-module-path", "module-source-path", "module=list", "module-version",
                            "patch-module", "release", "enable-preview", "add-exports"),
                    Set.of(properties.getProperty("options").split(",")));
            assertEquals("--aot-warmup", properties.getProperty("warmup"));
        }
    }

    @Test
    void explicitAotWarmupCompletesWithoutStartingTheServer() {
        var output = new StringWriter();
        var error = new StringWriter();

        int result = new JdocServer().run(new PrintWriter(output), new PrintWriter(error), "--aot-warmup");

        assertEquals(0, result, error.toString());
        assertEquals("", output.toString());
        assertEquals("", error.toString());
    }

    @Test
    void printsVersion() {
        var output = new StringWriter();
        var error = new StringWriter();
        String version = JdocServer.class
                .getModule()
                .getDescriptor()
                .rawVersion()
                .orElse("dev");

        int result = new JdocServer().run(new PrintWriter(output), new PrintWriter(error), "--version");

        assertEquals(0, result, error.toString());
        assertEquals("jdocserver " + version + "\n", output.toString());
        assertEquals("", error.toString());
    }

    @Test
    void commandHelpDescribesServerOptions() {
        var output = new StringWriter();
        var error = new StringWriter();
        var tool = new JdocServer();
        int result = tool.run(new PrintWriter(output), new PrintWriter(error), "--help");
        assertEquals(0, result, "help command failed: " + error);
        assertEquals("jdocserver", tool.name());
        assertTrue(output.toString()
                         .contains("-b, --bind-address"),
                "help omits the bind address option");
        assertTrue(output.toString()
                         .contains("--port <port>"),
                "help omits the port option");
        assertTrue(output.toString()
                         .contains("--browse[=<type>]"),
                "help omits the browse target option");
        assertTrue(output.toString()
                         .contains("--version"),
                "help omits the version option");
        assertFalse(output.toString()
                          .contains("--class-path"),
                "help advertises classpath documentation");
    }

    @Test
    void commandRejectsAnEmptyBrowseTarget() {
        var output = new StringWriter();
        var error = new StringWriter();

        int result = new JdocServer().run(new PrintWriter(output), new PrintWriter(error), "--help", "--browse=");

        assertEquals(2, result);
        assertTrue(error.toString().contains("--browse requires a type"), error.toString());
    }

    @Test
    void commandReportsStartupDetails() throws Exception {
        Path temporary = Files.createTempDirectory("jdocserver-command-test-");
        try {
            Path module = temporary.resolve("src/example.module");
            Files.createDirectories(module.resolve("example"));
            Files.writeString(module.resolve("module-info.java"), "module example.module { requires java.logging; requires java.sql; exports example; }\n");
            Files.writeString(module.resolve("example/Api.java"), "package example; public class Api {}\n");

            var output = new LatchingWriter("URL ");
            var error = new StringWriter();
            var result = new AtomicInteger(-1);
            Thread command = Thread.startVirtualThread(
                    () -> result.set(
                            new JdocServer().run(
                                    new PrintWriter(output),
                                    new PrintWriter(error),
                                    "--port",
                                    "0",
                                    "--module-source-path",
                                    temporary.resolve("src").toString(),
                                    "--add-modules",
                                    "example.module,jdk.httpserver,java.management")));
            assertTrue(output.await(10, TimeUnit.SECONDS), "server did not start: " + error);
            String url = output.toString()
                               .lines()
                               .filter(line -> line.startsWith("URL "))
                               .map(line -> line.substring("URL ".length()))
                               .findFirst()
                               .orElseThrow();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> index = get(client, URI.create(url));
            assertEquals(200, index.statusCode(), "overview request failed: " + index.body());
            assertTrue(index.body()
                            .contains("name=\"generator\" content=\"javadoc/"),
                    "overview request did not join standard Javadoc generation");
            assertTrue(index.body()
                            .contains("java.base/module-summary.html"),
                    "overview omits the implicitly required java.base module");
            assertTrue(index.body()
                            .contains("java.logging/module-summary.html"),
                    "overview omits a directly required system module");
            assertTrue(index.body()
                            .contains("java.xml/module-summary.html"),
                    "overview omits a transitively required system module");
            assertTrue(index.body()
                            .contains("jdk.httpserver/module-summary.html"),
                    "overview omits the first added module");
            assertTrue(index.body()
                            .contains("java.management/module-summary.html"),
                    "overview omits the second added module");
            assertTrue(index.body()
                            .contains("example.module API Documentation"),
                    "source module does not determine the overview title");
            assertTrue(index.body()
                            .contains("<li><code>example.module</code></li>"),
                    "overview does not identify the requested source module");
            assertTrue(index.body()
                            .contains("<code>java.logging</code>"),
                    "overview omits the source module's direct requirements");
            HttpResponse<String> moduleSummary = get(client, URI.create(url)
                    .resolve("java.logging/module-summary.html"));
            assertEquals(200, moduleSummary.statusCode(), "module summary request failed: " + moduleSummary.body());
            assertTrue(moduleSummary.body()
                                    .contains("Defines the Java Logging API."),
                    "module summary omits the module source documentation");
            HttpResponse<String> packageSummary = get(client, URI.create(url)
                    .resolve("java.logging/java/util/logging/package-summary.html"));
            assertEquals(200, packageSummary.statusCode(), "package summary request failed: " + packageSummary.body());
            assertTrue(packageSummary.body()
                    .contains("core logging facilities"),
                    "package summary omits the package source documentation");
            assertFalse(packageSummary.body()
                    .contains("generate brief summaries"),
                    "package summary includes class source documentation");
            command.interrupt();
            command.join(5_000);
            assertFalse(command.isAlive(), "server did not stop after interruption");
            assertEquals(130, result.get(), "unexpected command result: " + error);
            assertFalse(output.toString()
                              .contains("Documentation context:"),
                    "startup output includes a redundant documentation context");
            assertTrue(output.toString()
                             .contains("Temporary workspace: "),
                    "startup output omits the temporary workspace");
            assertTrue(output.toString()
                             .contains("Temporary workspace is removed on shutdown."),
                    "startup output omits temporary workspace cleanup");
            assertTrue(output.toString()
                             .contains("Generating standard Javadoc overview in the background..."),
                    "startup output omits overview generation");
            assertTrue(output.toString()
                             .contains("Type documentation is generated on demand."),
                    "startup output omits lazy type generation behavior");
        } finally {
            deleteTree(temporary);
        }
    }

    @Test
    void commandStopsWhenOverviewGenerationFails() throws Exception {
        Path temporary = Files.createTempDirectory("jdocserver-overview-failure-test-");
        Thread command = null;
        try {
            Path module = temporary.resolve("src/example.module");
            Files.createDirectories(module.resolve("example"));
            Files.writeString(module.resolve("module-info.java"), "module example.module { exports example; }\n");
            Files.writeString(module.resolve("example/Api.java"), "package example; public class Api {}\n");

            var output = new LatchingWriter("URL ");
            var error = new LatchingWriter("jdocserver:");
            var result = new AtomicInteger(-1);
            command = Thread.startVirtualThread(
                    () -> result.set(
                            new JdocServer().run(
                                    new PrintWriter(output),
                                    new PrintWriter(error),
                                    "--port",
                                    "0",
                                    "--system",
                                    temporary.resolve("missing-jdk").toString(),
                                    "--module-source-path",
                                    temporary.resolve("src").toString(),
                                    "--module",
                                    "example.module")));
            assertTrue(output.await(10, TimeUnit.SECONDS), "server did not start: " + error);
            assertTrue(error.await(10, TimeUnit.SECONDS), "overview generation failure did not stop the command");
            command.join(5_000);
            assertFalse(command.isAlive(), "command remained alive after overview failure");
            assertEquals(1, result.get(), "unexpected command result: " + error);
        } finally {
            if (command != null && command.isAlive()) {
                command.interrupt();
                command.join(5_000);
            }
            deleteTree(temporary);
        }
    }

    @Test
    void defaultJdkRequiresSources() throws Exception {
        Path temporary = Files.createTempDirectory("jdocserver-system-test-");
        try {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> DocumentationHandler.create("--system", temporary.toString()));
            assertEquals("JDK sources are unavailable: " + temporary.resolve("lib/src.zip"), failure.getMessage());
        } finally {
            deleteTree(temporary);
        }
    }

    @Test
    void modularDocumentationIsGeneratedOnDemand() throws Exception {
        Path temporary = Files.createTempDirectory("jdocserver-module-test-");
        try {
            Path sources = temporary.resolve("src/example.module");
            Files.createDirectories(sources.resolve("example/module"));
            Files.writeString(sources.resolve("module-info.java"),
                    """
                    module example.module { exports example.module; }
                    """);
            Files.writeString(sources.resolve("example/module/Api.java"),
                    """
                    package example.module;

                    /** A modular API. */
                    public final class Api {}
                    """);
            Path modules = temporary.resolve("modules");
            int compilation = ToolProvider.getSystemJavaCompiler().run(
                    null,
                    System.out,
                    System.err,
                    "--module-source-path",
                    temporary.resolve("src").toString(),
                    "--module",
                    "example.module",
                    "-d",
                    modules.toString());
            assertEquals(0, compilation, "module fixture compilation failed");

            try (var handler = DocumentationHandler.create(List.of(
                 "--module-path", modules.toString(),
                 "--module-source-path", temporary.resolve("src/*")
                         .toString()));
                 var server = RunningServer.start(handler)) {
                assertEquals(URI.create("/"), handler.indexUri());
                assertEquals(URI.create("/type?name=example.module.Api"), handler.typeUri("example.module.Api"));
                assertThrows(IllegalArgumentException.class, () -> handler.typeUri(" "));

                HttpClient client = HttpClient.newBuilder()
                        .followRedirects(Redirect.NORMAL)
                        .build();
                HttpResponse<String> type = get(client, server.uri(handler.typeUri("example.module.Api")));
                assertEquals(200, type.statusCode(), "module type request failed: " + type.body());
                assertTrue(type.body()
                               .contains("Class Api"),
                        "module documentation is missing");
            }
        } finally {
            deleteTree(temporary);
        }
    }

    private static HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri)
                .GET()
                .build(),
                BodyHandlers.ofString());
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class LatchingWriter extends StringWriter {
        private final String marker;
        private final CountDownLatch reached = new CountDownLatch(1);

        private LatchingWriter(String marker) {
            this.marker = marker;
        }

        @Override
        public void write(String value, int offset, int length) {
            super.write(value, offset, length);
            if (getBuffer().indexOf(marker) >= 0) {
                reached.countDown();
            }
        }

        @Override
        public void write(char[] value, int offset, int length) {
            super.write(value, offset, length);
            if (getBuffer().indexOf(marker) >= 0) {
                reached.countDown();
            }
        }

        private boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return reached.await(timeout, unit);
        }
    }

    private record RunningServer(HttpServer server) implements AutoCloseable {
        static RunningServer start(DocumentationHandler handler) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", handler);
            server.start();
            return new RunningServer(server);
        }

        URI uri(URI request) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort()).resolve(request);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
