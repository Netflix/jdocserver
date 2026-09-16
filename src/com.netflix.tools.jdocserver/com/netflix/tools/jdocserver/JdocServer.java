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

package com.netflix.tools.jdocserver;

import java.awt.Desktop;
import java.awt.Desktop.Action;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.spi.ToolProvider;
import javax.tools.OptionChecker;

import com.sun.net.httpserver.HttpServer;

/**
 * Hosts a {@link DocumentationHandler} using the JDK HTTP server.
 *
 * <p>The tool is available through {@link java.util.spi.ToolProvider} with the
 * name {@code jdocserver}. It prints the selected documentation URL and remains
 * in the foreground until interrupted.
 */
public final class JdocServer implements ToolProvider, OptionChecker {
    private static final CommandLine COMMAND_LINE = CommandLine.builder()
            .version(JdocServer.class.getModule())
            .build();

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "jdocserver";
    }

    /** {@inheritDoc} */
    @Override
    public int isSupportedOption(String option) {
        return switch (option) {
            case "-b", "--bind-address", "--port" -> 1;
            case "--browse", "--aot-warmup", "-h", "--help", "--version" -> 0;
            default -> DocumentationHandler.optionChecker().isSupportedOption(option);
        };
    }

    /**
     * Starts a documentation server for the supplied Java tool arguments.
     *
     * @param out standard output, which receives the documentation URL
     * @param err standard error
     * @param arguments server options followed by Java compilation options
     * @return zero on normal termination, or a non-zero status on failure
     */
    @Override
    public int run(PrintWriter out, PrintWriter err, String... arguments) {
        var version = COMMAND_LINE.runVersion("jdocserver", out, arguments);
        if (version.isPresent()) {
            return version.orElseThrow();
        }
        try {
            if (arguments.length == 1 && arguments[0].equals("--aot-warmup")) {
                warmup();
                return 0;
            }
            Options options = Options.parse(arguments);
            if (options.help()) {
                printHelp(out);
                return 0;
            }

            try (var handler = DocumentationHandler.create(options.documentationArguments())) {
                handler.startupDiagnostics().forEach(out::println);
                CompletableFuture<Void> overview = null;
                if (handler.hasOverview()) {
                    out.println("Generating standard Javadoc overview in the background...");
                    out.flush();
                    overview = handler.startOverview();
                }
                out.println("Type documentation is generated on demand.");
                try (var running = RunningServer.start(options.address(), handler)) {
                    URI uri = running.uri();
                    if (options.defaultBinding()) {
                        out.println("Binding to loopback by default. For all interfaces use " + "\"-b 0.0.0.0\" or \"-b ::\".");
                    }
                    out.println("Serving Java API documentation on " + running.host() + " port " + running.port());
                    out.println("URL " + uri);
                    out.flush();
                    if (options.browse()) {
                        URI target = options.browseType()
                                .map(handler::typeUri)
                                .map(uri::resolve)
                                .orElse(uri);
                        browse(target);
                    }

                    var shutdownHook = new Thread(running::close, "jdocserver-shutdown");
                    Runtime.getRuntime().addShutdownHook(shutdownHook);
                    try {
                        running.await(overview);
                        return 0;
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return 130;
                    } finally {
                        try {
                            Runtime.getRuntime().removeShutdownHook(shutdownHook);
                        } catch (IllegalStateException ignored) {
                            // JVM shutdown is already running the hook.
                        }
                    }
                }
            }
        } catch (IllegalArgumentException failure) {
            err.println("jdocserver: " + failure.getMessage());
            return 2;
        } catch (IOException failure) {
            err.println("jdocserver: " + failure.getMessage());
            return 1;
        }
    }

    /**
     * Runs the {@code jdocserver} command.
     *
     * @param arguments command-line arguments
     */
    public static void main(String[] arguments) {
        int result = new JdocServer().run(new PrintWriter(System.out, true), new PrintWriter(System.err, true), arguments);
        System.exit(result);
    }

    private static void warmup() throws IOException {
        try (var handler = DocumentationHandler.create()) {
            try {
                handler.startOverview().get();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("AOT warmup interrupted", failure);
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                while (cause instanceof CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw new IOException("AOT warmup failed", cause);
            }
        }
    }

    private static void browse(URI uri) throws IOException {
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("desktop browsing is not supported");
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Action.BROWSE)) {
                throw new IOException("no browser handler is available");
            }
            desktop.browse(uri);
        } catch (IOException failure) {
            throw new IOException("Cannot browse " + uri + ": " + failure.getMessage(), failure);
        } catch (UnsupportedOperationException | SecurityException failure) {
            throw new IOException("Cannot browse " + uri + ": " + failure, failure);
        }
    }

    private static void printHelp(PrintWriter out) {
        out
                .print("""
                Usage: jdocserver [server-options] [java-tool-options]

                Serves API documentation on demand for a modular Java compilation context.
                Without Java tool options, serves the running JDK's standard module overview
                from its lib/src.zip source archive.

                Server options:
                  -b, --bind-address <address>  Address to bind (default: 127.0.0.1)
                      --port <port>             Port to listen on (default: 8000)
                      --browse[=<type>]         Browse the index or a qualified type
                  -h, --help                    Print this help message
                      --version                 Print version information

                Documentation input:
                  @<file>                       Read one argument per line
                  -p, --module-path <path>      Where to find application modules
                  --module-source-path <path>   Where to find local module sources
                  --source-path <path>          Ordered source archives/directories
                  -m, --module <M,...>          Root modules to document
                  --system <jdk>|none           System module location
                  --add-modules <M,...>         Additional root modules
                  --limit-modules <M,...>       Restrict observable modules

                Qualified type requests resolve binary and source input using these paths.
                JdocServer renders the selected source with the standard doclet and links platform
                API references to documentation for the selected JDK release.
                """);
        out.flush();
    }

    private record Options(InetSocketAddress address, boolean defaultBinding, boolean browse,
                           Optional<String> browseType, boolean help, List<String> documentationArguments) {
        static Options parse(String[] arguments) {
            String bindAddress = null;
            String port = null;
            boolean browse = false;
            String browseType = null;
            boolean help = false;
            var documentation = new ArrayList<String>();
            for (int i = 0; i < arguments.length; i++) {
                String argument = arguments[i];
                switch (argument) {
                    case "-b", "--bind-address" -> {
                        if (++i >= arguments.length) {
                            throw new IllegalArgumentException(argument + " requires an address");
                        }
                        bindAddress = arguments[i];
                    }
                    case "--port" -> {
                        if (++i >= arguments.length) {
                            throw new IllegalArgumentException("--port requires a port");
                        }
                        port = arguments[i];
                    }
                    case "--browse" -> browse = true;
                    case "-h", "--help" -> help = true;
                    default -> {
                        if (argument.startsWith("--bind-address=")) {
                            bindAddress = argument.substring("--bind-address=".length());
                        } else if (argument.startsWith("--port=")) {
                            port = argument.substring("--port=".length());
                        } else if (argument.startsWith("--browse=")) {
                            browseType = argument.substring("--browse=".length());
                            if (browseType.isBlank()) {
                                throw new IllegalArgumentException("--browse requires a type after =");
                            }
                            browse = true;
                        } else {
                            documentation.add(argument);
                        }
                    }
                }
            }
            return new Options(parseAddress(bindAddress, port), bindAddress == null, browse,
                    Optional.ofNullable(browseType), help, List.copyOf(documentation));
        }

        private static InetSocketAddress parseAddress(String address, String value) {
            String host = address == null || address.isBlank()
                    ? "127.0.0.1"
                    : address;
            String portValue = value == null || value.isBlank()
                    ? "8000"
                    : value;
            int port;
            try {
                port = Integer.parseInt(portValue);
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("invalid listen port: " + portValue, failure);
            }
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("listen port out of range: " + port);
            }
            return new InetSocketAddress(host, port);
        }
    }

    private static final class RunningServer implements AutoCloseable {
        private final HttpServer server;
        private final DocumentationHandler handler;
        private final ExecutorService executor;
        private final CompletableFuture<Void> stopped = new CompletableFuture<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        private RunningServer(HttpServer server, DocumentationHandler handler, ExecutorService executor) {
            this.server = server;
            this.handler = handler;
            this.executor = executor;
        }

        static RunningServer start(InetSocketAddress address, DocumentationHandler handler) throws IOException {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            var server = HttpServer.create(address, 0);
            server.createContext("/",
                    exchange -> {
                        exchange.getResponseHeaders().set("Server", "jdocserver");
                        handler.handle(exchange);
                    });
            server.setExecutor(executor);
            server.start();
            return new RunningServer(server, handler, executor);
        }

        String host() {
            InetSocketAddress address = server.getAddress();
            if (address.getAddress() != null && address.getAddress().isAnyLocalAddress()) {
                return address.getAddress() instanceof Inet6Address ? "::" : "0.0.0.0";
            }
            return address.getAddress() == null ? address.getHostString() : address.getAddress().getHostAddress();
        }

        int port() {
            return server.getAddress().getPort();
        }

        URI uri() {
            String uriHost = server.getAddress().getAddress() != null && server.getAddress()
                            .getAddress()
                            .isAnyLocalAddress()
                    ? server.getAddress().getAddress() instanceof Inet6Address ? "::1" : "127.0.0.1"
                    : host();
            try {
                return new URI("http", null, uriHost, port(), "/",
                        null, null);
            } catch (URISyntaxException failure) {
                throw new IllegalStateException("Invalid server address", failure);
            }
        }

        void await(CompletableFuture<Void> overview) throws InterruptedException, IOException {
            var overviewFailure = new CompletableFuture<Void>();
            if (overview != null) {
                overview.whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        overviewFailure.completeExceptionally(failure);
                    }
                });
            }
            try {
                CompletableFuture.anyOf(stopped, overviewFailure).get();
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                while (cause instanceof CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw new IOException("Overview generation failed", cause);
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            server.stop(0);
            executor.close();
            try {
                handler.close();
            } catch (IOException ignored) {
                // Temporary output is best-effort cleanup during server shutdown.
            }
            stopped.complete(null);
        }
    }
}
