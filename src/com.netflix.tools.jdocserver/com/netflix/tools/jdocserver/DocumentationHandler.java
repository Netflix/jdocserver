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

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleDescriptor.Provides;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import javax.tools.DocumentationTool;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.OptionChecker;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

import com.netflix.tools.jdocserver.CompilationContext.Overview;
import com.netflix.tools.jdocserver.CompilationContext.ResolvedType;
import com.netflix.tools.jdocserver.CompilationContext.SourceContent;
import com.netflix.tools.jdocserver.internal.BinaryFileManager;
import com.netflix.tools.jdocserver.internal.OverviewDoclet;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.tools.javac.util.Context;
import jdk.javadoc.internal.api.JavadocTool;

/**
 * Serves standard-doclet overviews and on-demand class documentation for a
 * Java compilation context.
 *
 * <p>A handler owns process-temporary documentation output for its lifetime.
 * Concurrent requests for the same type share one generation. Closing the
 * handler removes all generated output.
 */
public final class DocumentationHandler implements HttpHandler, AutoCloseable {
    private static final OptionChecker OPTION_CHECKER = option -> {
        DocumentationTool documentation = ServiceLoader.load(DocumentationTool.class)
                .findFirst()
                .orElse(null);
        if (documentation == null) {
            return -1;
        }
        int operands = documentation.isSupportedOption(option);
        if (operands >= 0) {
            return operands;
        }
        try (var fileManager = documentation.getStandardFileManager(null, null, null)) {
            return fileManager.isSupportedOption(option);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    };

    private final CompilationContext context;
    private final Provider<DocumentationTool> documentationTool;
    private final List<String> inputArguments;
    private final Path workspace;
    private final Path generatedRoot;
    private final Path overviewRoot;
    private final ExecutorService overviewExecutor;
    private final Map<String, CompletableFuture<GeneratedDocumentation>> generated = new ConcurrentHashMap<>();
    private CompletableFuture<Void> overview;
    private volatile String documentationBase;
    private volatile boolean closed;

    private DocumentationHandler(List<String> arguments) throws IOException {
        documentationTool = ServiceLoader.load(DocumentationTool.class)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IOException("The running JDK does not provide javadoc"));
        inputArguments = expandArgumentFiles(arguments);
        context = CompilationContext.parse(inputArguments);
        workspace = Files.createTempDirectory("jdocserver-");
        generatedRoot = workspace.resolve("generated");
        overviewRoot = workspace.resolve("overview");
        Files.createDirectories(generatedRoot);
        Files.createDirectories(overviewRoot);
        overviewExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Creates a handler for the supplied standard Java compilation arguments.
     *
     * @param arguments module-path, module-source-path, source-path, release,
     *     and access options
     * @return a documentation handler
     * @throws IOException if temporary output cannot be initialized
     */
    public static DocumentationHandler create(List<String> arguments) throws IOException {
        return new DocumentationHandler(arguments);
    }

    /**
     * Creates a handler for the supplied standard Java compilation arguments.
     *
     * @param arguments module-path, module-source-path, source-path, release,
     *     and access options
     * @return a documentation handler
     * @throws IOException if temporary output cannot be initialized
     */
    public static DocumentationHandler create(String... arguments) throws IOException {
        return create(List.of(arguments));
    }

    /**
     * Returns the standard Java options accepted when creating a handler.
     *
     * @return the documentation and file-manager option contract
     */
    public static OptionChecker optionChecker() {
        return OPTION_CHECKER;
    }

    /**
     * Returns the request URI for the documentation index.
     *
     * @return the index request URI
     */
    public URI indexUri() {
        return URI.create("/");
    }

    /**
     * Returns the request URI that resolves documentation for a qualified type name.
     *
     * @param qualifiedName the qualified type name
     * @return the type documentation request URI
     */
    public URI typeUri(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            throw new IllegalArgumentException("Type name must not be empty");
        }
        return URI.create("/type?name=" + URLEncoder.encode(qualifiedName, StandardCharsets.UTF_8));
    }

    /**
     * Returns the process-temporary generated documentation directory.
     *
     * @return the generated documentation directory
     */
    public Path outputDirectory() {
        return generatedRoot;
    }

    List<String> startupDiagnostics() {
        var diagnostics = new ArrayList<>(context.startupDiagnostics());
        diagnostics.add("Temporary workspace: " + workspace);
        diagnostics.add("Temporary workspace is removed on shutdown.");
        return List.copyOf(diagnostics);
    }

    boolean hasOverview() {
        return true;
    }

    CompletableFuture<Void> startOverview() {
        return overviewFuture();
    }

    /**
     * Handles an HTTP request for the overview, generated resources, or a type.
     *
     * @param exchange the HTTP exchange
     * @throws IOException if the request or documentation output cannot be handled
     */
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            handleRequest(exchange);
        } catch (IOException failure) {
            sendHtml(exchange, 500, errorPage(failure), exchange.getRequestMethod().equals("HEAD"));
        } catch (RuntimeException | Error failure) {
            failure.printStackTrace();
            throw failure;
        }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        if (closed) {
            sendText(exchange, 503, "Documentation handler is closed");
            return;
        }
        if (documentationBase == null) {
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (host != null && host.matches("[A-Za-z0-9.\\-\\[\\]:]+")) {
                documentationBase = "http://" + host + "/";
            }
        }
        String method = exchange.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if (path.equals("/type")) {
            handleType(exchange, method.equals("HEAD"));
            return;
        }
        ensureOverview();
        String relative = path.equals("/") ? "index.html" : path.substring(1);
        if (serveFile(exchange, overviewRoot, relative, method.equals("HEAD"))) {
            return;
        }
        if (relative.endsWith(".html")) {
            String typeName = typeNameFromOverviewPath(relative);
            if (typeName != null && materializeOverviewClass(typeName, relative) && serveFile(exchange, overviewRoot, relative, method.equals("HEAD"))) {
                return;
            }
        }
        sendText(exchange, 404, "Not found");
    }

    /**
     * Stops this handler and removes its process-temporary documentation output.
     *
     * @throws IOException if generated output cannot be removed
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        overviewExecutor.close();
        deleteTree(workspace);
    }

    private synchronized CompletableFuture<Void> overviewFuture() {
        if (overview == null) {
            overview = CompletableFuture.runAsync(
                    () -> {
                        try {
                            generateOverview();
                        } catch (IOException failure) {
                            throw new CompletionException(failure);
                        }
                    },
                    overviewExecutor);
        }
        return overview;
    }

    private void ensureOverview() throws IOException {
        try {
            overviewFuture().join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Overview generation failed", cause);
        }
    }

    private void generateOverview() throws IOException {
        Overview overview = context.overview();
        boolean defaultJdk = context.defaultJdk();
        Path overviewFile = defaultJdk ? null : writeOverview(overview);
        String title = defaultJdk
                ? "Java Development Kit Version " + overview.javaVersion() + " API Specification"
                : overview.sourceModules().size() == 1 ? overview.sourceModules()
                        .getFirst()
                        .name()
                        + " API Documentation"
                        : "Project API Documentation";

        var arguments = new ArrayList<String>();
        for (int i = 0; i < inputArguments.size(); i++) {
            String argument = inputArguments.get(i);
            int equals = argument.indexOf('=');
            String option = equals < 0 ? argument : argument.substring(0, equals);
            if (Set.of("-d", "--module-version", "--module-source-path", "--module", "-m", "--add-modules").contains(option)) {
                if (equals < 0) {
                    i++;
                }
            } else {
                arguments.add(argument);
            }
        }
        List<String> systemModules;
        if (defaultJdk) {
            List<String> availableModules = systemSourceModules(context.systemSources());
            systemModules = context.requestedModules().isEmpty() ? availableModules : context.requestedModules();
            if (!availableModules.containsAll(systemModules)) {
                var missing = new TreeSet<>(systemModules);
                missing.removeAll(availableModules);
                throw new IOException("JDK sources do not contain modules: " + String.join(", ", missing));
            }
            if (!inputArguments.contains("-group")) {
                arguments.add("-group");
                arguments.add("Java SE");
                arguments.add("java.*");
                arguments.add("-group");
                arguments.add("JDK");
                arguments.add("jdk.*");
                arguments.add("-group");
                arguments.add("Other Modules");
                arguments.add("*");
            }
        } else {
            if (context.systemSources() == null) {
                throw new IOException("JDK sources are unavailable: " + context.system().resolve("lib/src.zip"));
            }
            systemModules = overview.systemModules();
            arguments.add("-overview");
            arguments.add(overviewFile.toString());
        }
        String symbolRelease = optionValue(inputArguments, "--release");
        if (symbolRelease == null && !hasOption(inputArguments, "--source") && !hasOption(inputArguments, "--system")) {
            symbolRelease = Integer.toString(Runtime.version()
                    .feature());
            arguments.add("--release");
            arguments.add(symbolRelease);
        }
        boolean binarySymbols = symbolRelease != null && Integer.parseInt(symbolRelease) == Runtime.version().feature() && !hasOption(inputArguments, "--system");
        if (binarySymbols) {
            systemModules = context.symbolModules(systemModules, Runtime.version()
                    .feature());
        }
        var documentedModules = new TreeSet<String>(systemModules);
        overview.sourceModules().stream()
                .map(ModuleDescriptor::name)
                .forEach(documentedModules::add);
        if (documentedModules.isEmpty()) {
            throw new IOException("No modules were found to document");
        }
        arguments.add("--add-modules");
        arguments.add(String.join(",", documentedModules));
        arguments.add("-doctitle");
        arguments.add(defaultJdk ? title : title + "<br><span>Java " + overview.javaVersion() + "</span>");
        arguments.add("-windowtitle");
        arguments.add(title + " - Java " + overview.javaVersion());
        arguments.add("--ignore-source-errors");
        arguments.add("-Xdoclint:none");
        arguments.add("-quiet");
        arguments.add("-d");
        arguments.add(overviewRoot.toString());

        DocumentationTool javadoc = documentationTool.get();
        var diagnostics = new StringWriter();
        try (var systemSources = FileSystems.newFileSystem(context.systemSources());
             var fileManager = javadoc.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Path classes = workspace.resolve("overview-classes");
            Files.createDirectories(classes);
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            var units = new LinkedHashMap<URI, JavaFileObject>();
            for (var module : overview.sourceModules()) {
                addModuleSources(fileManager, module, context.moduleSourceRoots(module.name()), units);
            }
            boolean includeSourceElements = !units.isEmpty();
            for (String name : systemModules) {
                var module = context.systemModule(name);
                var roots = List.of(systemSources.getPath("/" + name));
                if (binarySymbols) {
                    addModuleAndPackageSources(fileManager, module, roots,
                            workspace.resolve("overview-sources").resolve(name), units);
                } else {
                    addModuleSources(fileManager, module, roots, units);
                }
            }
            if (units.isEmpty()) {
                JavaFileObject anchor = new SimpleJavaFileObject(URI.create("string:///JavadocOverviewAnchor.java"), Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return "final class JavadocOverviewAnchor {}";
                    }
                };
                units.put(anchor.toUri(), anchor);
            }
            if (binarySymbols) {
                OverviewDoclet.configureBinaryElements(systemModules, includeSourceElements);
            }
            try {
                var taskContext = new Context();
                var task = ((JavadocTool) javadoc).getTask(diagnostics, fileManager, null, OverviewDoclet.class, arguments,
                        units.values(), taskContext);
                if (binarySymbols) {
                    BinaryFileManager.installWhenReady(taskContext);
                }
                if (!task.call()) {
                    throw new IOException(diagnostics.toString()
                            .strip());
                }
            } finally {
                if (binarySymbols) {
                    OverviewDoclet.clearBinaryElements();
                }
            }
        }
    }

    private static List<String> systemSourceModules(Path archive) throws IOException {
        try (FileSystem fileSystem = FileSystems.newFileSystem(archive);
             var entries = Files.list(fileSystem.getPath("/"))) {
            return entries.filter(Files::isDirectory)
                          .filter(path -> Files.isRegularFile(path.resolve("module-info.java")))
                          .map(path -> path.getFileName().toString())
                          .sorted()
                          .toList();
        }
    }

    private static void addModuleSources(StandardJavaFileManager fileManager, ModuleDescriptor module, List<Path> roots,
            Map<URI, JavaFileObject> units)
            throws IOException {
        if (roots.isEmpty()) {
            throw new IOException("Sources are unavailable for module " + module.name());
        }
        fileManager.setLocationForModule(StandardLocation.MODULE_SOURCE_PATH, module.name(), roots);
        var location = fileManager.getLocationForModule(StandardLocation.MODULE_SOURCE_PATH, module.name());
        int initialSize = units.size();
        for (String packageName : module.exports().stream()
                .filter(exported -> !exported.isQualified())
                .map(Exports::source)
                .sorted()
                .toList()) {
            for (JavaFileObject source : fileManager.list(location, packageName, Set.of(Kind.SOURCE), false)) {
                units.put(source.toUri(), source);
            }
        }
        if (units.size() == initialSize) {
            JavaFileObject moduleInfo = fileManager.getJavaFileForInput(location, "module-info", Kind.SOURCE);
            if (moduleInfo != null) {
                units.put(moduleInfo.toUri(), moduleInfo);
            }
        }
    }

    private static void addModuleAndPackageSources(StandardJavaFileManager fileManager, ModuleDescriptor module, List<Path> roots,
            Path destination, Map<URI, JavaFileObject> units)
            throws IOException {
        var relativePaths = new ArrayList<String>();
        relativePaths.add("module-info.java");
        module.exports().stream()
                .filter(exported -> !exported.isQualified())
                .map(Exports::source)
                .sorted()
                .map(packageName -> packageName.replace('.', '/') + "/package-info.java")
                .forEach(relativePaths::add);

        var paths = new ArrayList<Path>();
        for (String relative : relativePaths) {
            Path source = roots.stream()
                    .map(root -> root.resolve(relative))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElse(null);
            if (source == null) {
                continue;
            }
            Path copy = destination.resolve(relative);
            Files.createDirectories(copy.getParent());
            Files.copy(source, copy);
            copySnippetFiles(source.getParent(), copy.getParent());
            paths.add(copy);
        }
        fileManager.setLocationForModule(StandardLocation.MODULE_SOURCE_PATH, module.name(), List.of(destination));
        for (JavaFileObject source : fileManager.getJavaFileObjectsFromPaths(paths)) {
            units.put(source.toUri(), source);
        }
    }

    private static void copySnippetFiles(Path sourceDirectory, Path destinationDirectory) throws IOException {
        Path sourceRoot = sourceDirectory.resolve("snippet-files");
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        Path destinationRoot = destinationDirectory.resolve("snippet-files");
        try (var files = Files.walk(sourceRoot)) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                Path destination = destinationRoot.resolve(sourceRoot.relativize(source)
                        .toString());
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination);
            }
        }
    }

    private Path writeOverview(Overview overview) throws IOException {
        StringBuilder modules = new StringBuilder("<ul>");
        StringBuilder requirements = new StringBuilder("<table><thead><tr><th>Source module</th>" + "<th>Direct requirements</th></tr></thead><tbody>");
        StringBuilder services = new StringBuilder();
        for (var module : overview.sourceModules()) {
            modules.append("<li><code>")
                   .append(escapeHtml(module.name()))
                   .append("</code></li>");
            String direct = module.requires().stream()
                    .filter(requirement -> !requirement.modifiers().contains(Modifier.MANDATED))
                    .sorted(Comparator.comparing(Requires::name))
                    .map(requirement -> {
                        String suffix = requirement.modifiers().contains(Modifier.STATIC)
                                ? " (static)"
                                : requirement.modifiers().contains(Modifier.TRANSITIVE) ? " (transitive)" : "";
                        return "<code>" + escapeHtml(requirement.name()) + "</code>" + suffix;
                    })
                    .collect(Collectors.joining(", "));
            requirements.append("<tr><th><code>")
                        .append(escapeHtml(module.name()))
                        .append("</code></th><td>")
                        .append(direct)
                        .append("</td></tr>");
            module.mainClass().ifPresent(main -> services.append("<li>Main class: <code>")
                    .append(escapeHtml(main))
                    .append("</code></li>"));
            module.provides().stream()
                    .sorted(Comparator.comparing(Provides::service))
                    .forEach(provided -> services.append("<li>Provides <code>")
                            .append(escapeHtml(provided.service()))
                            .append("</code></li>"));
        }
        modules.append("</ul>");
        requirements.append("</tbody></table>");

        String runtimeAccess = overview.runtimeAccess().isEmpty() ? "" : "<h2>Runtime Access</h2><ul>" + overview.runtimeAccess().stream()
                .map(value -> "<li><code>" + escapeHtml(value) + "</code></li>")
                .collect(Collectors.joining())
                + "</ul>";
        String serviceContent = services.isEmpty() ? "" : "<h2>Commands and Services</h2><ul>" + services + "</ul>";
        String
                html = """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><title>Overview</title></head>
                <body><p>This documentation covers the project's source modules and the APIs available to them.</p>
                <dl>
                <dt>Source Modules</dt><dd>APIs defined by the project's source modules.</dd>
                <dt>Java SE</dt><dd>Core Java platform APIs provided by modules
                whose names begin with <code>java.</code>.</dd>
                <dt>JDK</dt><dd>JDK-specific APIs provided by modules whose names begin with <code>jdk.</code>.</dd>
                <dt>Other Modules</dt><dd>APIs provided by other modules used by the project.</dd>
                </dl>
                <h2>Source Modules</h2>%s
                <h2>Direct Requirements</h2>%s
                %s%s
                </body></html>
                """
                                .formatted(modules, requirements, runtimeAccess, serviceContent);
        Path file = workspace.resolve("overview.html");
        Files.writeString(file, html, StandardCharsets.UTF_8);
        return file;
    }

    private boolean materializeOverviewClass(String typeName, String relative) throws IOException {
        GeneratedDocumentation documentation = documentation(typeName);
        if (documentation.output() == null) {
            return false;
        }
        Path source = documentation.output().resolve(documentation.page());
        Path destination = overviewRoot.resolve(relative).normalize();
        if (!destination.startsWith(overviewRoot)) {
            return false;
        }
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    private static String typeNameFromOverviewPath(String relative) {
        int firstSlash = relative.indexOf('/');
        if (firstSlash < 0 || !relative.endsWith(".html")) {
            return null;
        }
        String typePath = relative.substring(firstSlash + 1, relative.length() - ".html".length());
        if (typePath.endsWith("package-summary") || typePath.endsWith("package-tree") || typePath.endsWith("module-summary")) {
            return null;
        }
        return typePath.replace('/', '.');
    }

    private GeneratedDocumentation documentation(String name) throws IOException {
        try {
            return generated.computeIfAbsent(name, ignored -> CompletableFuture.supplyAsync(() -> {
                try {
                    return generate(name);
                } catch (IOException failure) {
                    throw new CompletionException(failure);
                }
            }))
                            .join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Documentation generation failed", cause);
        }
    }

    private void handleType(HttpExchange exchange, boolean head) throws IOException {
        ensureOverview();
        String name = queryParameter(exchange.getRequestURI()
                .getRawQuery(),
                "name");
        if (name == null || name.isBlank()) {
            sendText(exchange, 400, "Missing type name");
            return;
        }

        GeneratedDocumentation documentation;
        try {
            documentation = documentation(name);
        } catch (IOException failure) {
            sendHtml(exchange, 500, errorPage(failure), head);
            return;
        }

        if (documentation.inlineHtml() != null) {
            sendHtml(exchange, 200, documentation.inlineHtml(), head);
            return;
        }
        Path destination = overviewRoot.resolve(documentation.page());
        Files.createDirectories(destination.getParent());
        Files.copy(documentation.output().resolve(documentation.page()), destination,
                StandardCopyOption.REPLACE_EXISTING);
        exchange.getResponseHeaders().set("Location", "/" + documentation.page());
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_SEE_OTHER, -1);
        exchange.close();
    }

    private GeneratedDocumentation generate(String requestedName) throws IOException {
        ResolvedType type = context.resolve(requestedName);
        if (type == null) {
            return new GeneratedDocumentation(key(requestedName), null, null, messagePage("Type not found", requestedName));
        }
        SourceContent source = context.source(type);
        if (source == null) {
            return new GeneratedDocumentation(key(type.binaryName()), null, null, signaturePage(type, "Source is unavailable"));
        }

        String key = key(type.binaryName());
        Path root = generatedRoot.resolve(key);
        Path sourceRoot = root.resolve("src");
        Path output = root.resolve("docs");
        Path sourceFile = sourceRoot.resolve(type.sourceRelativePath());
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(output);
        Files.write(sourceFile, source.bytes());

        DocumentationTool javadoc = documentationTool.get();
        var arguments = new ArrayList<>(context.javadocArguments(type, sourceRoot));
        if (documentationBase != null && Files.isRegularFile(overviewRoot.resolve("element-list"))) {
            arguments.add("-linkoffline");
            arguments.add(documentationBase);
            arguments.add(overviewRoot.toString());
        }
        arguments.add("-d");
        arguments.add(output.toString());
        var diagnostics = new StringWriter();
        try (var fileManager = javadoc.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var units = fileManager.getJavaFileObjects(sourceFile);
            if (!javadoc.getTask(diagnostics, fileManager, null, null, arguments, units).call()) {
                String detail = diagnostics.toString().strip();
                return new GeneratedDocumentation(key, null, null,
                        signaturePage(type, detail.isEmpty() ? "Javadoc generation failed" : detail));
            }
        }

        String page = generatedPage(type);
        if (!Files.isRegularFile(output.resolve(page))) {
            return new GeneratedDocumentation(key, null, null, signaturePage(type, "Javadoc did not generate the requested type page"));
        }
        return new GeneratedDocumentation(key, output, page, null);
    }

    private static boolean serveFile(HttpExchange exchange, Path root, String relative,
            boolean head)
            throws IOException {
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return false;
        }
        String contentType = URLConnection.guessContentTypeFromName(file.getFileName()
                .toString());
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        long size = Files.size(file);
        exchange.sendResponseHeaders(200, head ? -1 : size);
        if (!head) {
            Files.copy(file, exchange.getResponseBody());
        }
        exchange.close();
        return true;
    }

    private static String generatedPage(ResolvedType type) {
        String binaryName = type.binaryName();
        int packageEnd = binaryName.lastIndexOf('.');
        String packagePath = packageEnd < 0 ? "" : binaryName.substring(0, packageEnd).replace('.', '/') + "/";
        String simpleName = packageEnd < 0 ? binaryName : binaryName.substring(packageEnd + 1);
        String page = packagePath + simpleName.replace('$', '.') + ".html";
        return type.moduleName() == null ? page : type.moduleName() + "/" + page;
    }

    private static String signaturePage(ResolvedType type, String explanation) {
        return messagePage(type.binaryName(), explanation + "\n\n" + type.binaryName());
    }

    private static String errorPage(Throwable failure) {
        return messagePage("Documentation generation failed", failure.toString());
    }

    private static String messagePage(String title, String message) {
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><title>%s</title></head>
                <body><h1>%s</h1><pre>%s</pre><p><a href="/">Back</a></p></body></html>
                """
                .formatted(escapeHtml(title), escapeHtml(title), escapeHtml(message));
    }

    private static String queryParameter(String query, String expected) {
        if (query == null) {
            return null;
        }
        for (String field : query.split("&")) {
            int equals = field.indexOf('=');
            String name = equals < 0 ? field : field.substring(0, equals);
            if (URLDecoder.decode(name, StandardCharsets.UTF_8).equals(expected)) {
                return URLDecoder.decode(equals < 0 ? "" : field.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String key(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    private static boolean hasOption(List<String> arguments, String option) {
        return arguments.stream().anyMatch(argument -> argument.equals(option) || argument.startsWith(option + "="));
    }

    private static String optionValue(List<String> arguments, String option) {
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (argument.equals(option) && i + 1 < arguments.size()) {
                return arguments.get(i + 1);
            }
            if (argument.startsWith(option + "=")) {
                return argument.substring(option.length() + 1);
            }
        }
        return null;
    }

    private static List<String> expandArgumentFiles(List<String> arguments) throws IOException {
        var expanded = new ArrayList<String>();
        for (String argument : arguments) {
            if (!argument.startsWith("@")) {
                expanded.add(argument);
                continue;
            }
            for (String line : Files.readAllLines(Path.of(argument.substring(1)), StandardCharsets.UTF_8)) {
                String value = line.strip();
                if (!value.isEmpty() && !value.startsWith("#")) {
                    expanded.add(value);
                }
            }
        }
        return List.copyOf(expanded);
    }

    private static void sendHtml(HttpExchange exchange, int status, String body,
            boolean head)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, head ? -1 : bytes.length);
        if (!head) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record GeneratedDocumentation(String key, Path output, String page,
            String inlineHtml) {}
}
