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

import java.io.File;
import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipException;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.SimpleJavaFileObject;

import com.sun.source.tree.ExportsTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.ModuleTree.ModuleKind;
import com.sun.source.tree.ProvidesTree;
import com.sun.source.tree.RequiresTree;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;

final class CompilationContext {
    private final List<String> toolArguments;
    private final List<Path> modulePath;
    private final List<Path> sourcePath;
    private final List<String> moduleSourcePath;
    private final List<String> requestedModules;
    private final Path system;
    private final Path systemSources;
    private final boolean defaultJdk;

    private CompilationContext(
            List<String> toolArguments,
            List<Path> modulePath,
            List<Path> sourcePath,
            List<String> moduleSourcePath,
            List<String> requestedModules,
            Path system,
            Path systemSources,
            boolean defaultJdk) {
        this.toolArguments = List.copyOf(toolArguments);
        this.modulePath = List.copyOf(modulePath);
        this.sourcePath = List.copyOf(sourcePath);
        this.moduleSourcePath = List.copyOf(moduleSourcePath);
        this.requestedModules = List.copyOf(requestedModules);
        this.system = system;
        this.systemSources = systemSources;
        this.defaultJdk = defaultJdk;
    }

    static CompilationContext parse(List<String> arguments) {
        var retained = new ArrayList<String>();
        var classPath = new ArrayList<Path>();
        var modulePath = new ArrayList<Path>();
        var sourcePath = new ArrayList<Path>();
        var moduleSourcePath = new ArrayList<String>();
        var requestedModules = new ArrayList<String>();
        Path system = Path.of(System.getProperty("java.home"));

        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            String inline = inlineValue(argument);
            String option = inline == null ? argument : argument.substring(0, argument.indexOf('='));
            switch (option) {
                case "--class-path", "-classpath", "-cp" -> {
                    String value = inline != null ? inline : requiredValue(arguments, ++i, option);
                    addPaths(classPath, value);
                    retained.add(argument);
                    if (inline == null) {
                        retained.add(value);
                    }
                }
                case "--module-path", "-p", "--upgrade-module-path" -> {
                    String value = inline != null ? inline : requiredValue(arguments, ++i, option);
                    addPaths(modulePath, value);
                    retained.add(argument);
                    if (inline == null) {
                        retained.add(value);
                    }
                }
                case "--source-path", "-sourcepath" -> {
                    String value = inline != null ? inline : requiredValue(arguments, ++i, option);
                    addPaths(sourcePath, value);
                }
                case "--module-source-path" -> {
                    String value = inline != null ? inline : requiredValue(arguments, ++i, option);
                    moduleSourcePath.add(value);
                }
                case "--system" -> {
                    String value = inline != null ? inline : requiredValue(arguments, ++i, option);
                    if (!value.equals("none")) {
                        system = Path.of(value);
                    }
                    retained.add(argument);
                    if (inline == null) {
                        retained.add(value);
                    }
                }
                case "--module", "-m", "--add-modules" -> {
                    String value = inline != null ? inline : requiredValue(arguments, ++i, option);
                    for (String module : value.split(",")) {
                        if (!module.isBlank() && !requestedModules.contains(module)) {
                            requestedModules.add(module);
                        }
                    }
                }
                case "-d", "--module-version", "--expand-requires" -> {
                    if (inline == null) {
                        requiredValue(arguments, ++i, option);
                    }
                }
                default -> retained.add(argument);
            }
        }
        boolean defaultJdk = classPath.isEmpty()
                && modulePath.isEmpty()
                && sourcePath.isEmpty()
                && moduleSourcePath.isEmpty();
        Path systemSources = findSystemSources(system);
        if (defaultJdk && systemSources == null) {
            throw new IllegalArgumentException("JDK sources are unavailable: " + system.resolve("lib/src.zip"));
        }
        if (!defaultJdk && moduleSourcePath.isEmpty()) {
            throw new IllegalArgumentException("no modular documentation sources were provided");
        }
        return new CompilationContext(retained, modulePath, sourcePath, moduleSourcePath, requestedModules, system,
                systemSources, defaultJdk);
    }

    boolean defaultJdk() {
        return defaultJdk;
    }

    Path system() {
        return system;
    }

    Path systemSources() {
        return systemSources;
    }

    ModuleDescriptor systemModule(String name) throws IOException {
        return systemFinder().find(name)
                             .map(ModuleReference::descriptor)
                             .orElseThrow(() -> new IOException("System module not found: " + name));
    }

    List<String> symbolModules(List<String> modules, int release) throws IOException {
        Path archive = system.resolve("lib/ct.sym");
        if (!Files.isRegularFile(archive)) {
            throw new IOException("JDK symbols are unavailable: " + archive);
        }
        char releaseCode = Character.toUpperCase(Character.forDigit(release, Character.MAX_RADIX));
        try (FileSystem fileSystem = FileSystems.newFileSystem(archive);
             var entries = Files.list(fileSystem.getPath("/"))) {
            List<Path> sections = entries.filter(Files::isDirectory)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.indexOf(releaseCode) >= 0 && !name.contains("-");
                    })
                    .toList();
            return modules.stream()
                    .filter(module -> sections.stream().anyMatch(section -> Files.isRegularFile(section.resolve(module)
                            .resolve("module-info.sig"))))
                    .toList();
        }
    }

    List<Path> moduleSourceRoots(String moduleName) throws IOException {
        var roots = new ArrayList<Path>();
        for (String specification : moduleSourcePath) {
            for (Path root : moduleRoots(specification, moduleName)) {
                if (read(root, "module-info.java") != null) {
                    roots.add(root);
                }
            }
        }
        return List.copyOf(roots);
    }

    List<String> requestedModules() {
        return requestedModules;
    }

    List<Path> modulePath() {
        return modulePath;
    }

    List<Path> sourcePath() {
        return sourcePath;
    }

    List<String> startupDiagnostics() {
        var diagnostics = new ArrayList<String>();
        diagnostics.add("JDK home: " + system);
        if (systemSources != null) {
            diagnostics.add("JDK sources: " + systemSources);
        }
        addDiagnostic(diagnostics, "Module path",
                modulePath.stream()
                        .map(Path::toString)
                        .toList());
        addDiagnostic(diagnostics, "Module source path", moduleSourcePath);
        addDiagnostic(diagnostics, "Source path",
                sourcePath.stream()
                        .map(Path::toString)
                        .toList());
        return List.copyOf(diagnostics);
    }

    Overview overview() throws IOException {
        ModuleFinder application = ModuleFinder.of(modulePath.toArray(Path[]::new));
        ModuleFinder systemModules = systemFinder();
        var sourceModules = new ArrayList<ModuleDescriptor>();
        if (requestedModules.isEmpty()) {
            sourceModules.addAll(discoverSourceModules());
        } else {
            for (String name : requestedModules) {
                if (systemModules.find(name).isPresent() && application.find(name).isEmpty()) {
                    continue;
                }
                Optional<ModuleDescriptor> source = sourceModule(name);
                source.or(() -> application.find(name).map(ModuleReference::descriptor)).ifPresent(sourceModules::add);
            }
        }
        sourceModules.sort(Comparator.comparing(ModuleDescriptor::name));
        String javaVersion = optionValue("--release");
        if (javaVersion == null) {
            javaVersion = optionValue("--source");
        }
        if (javaVersion == null) {
            javaVersion = Integer.toString(Runtime.version()
                    .feature());
        }
        var runtimeAccess = new ArrayList<String>();
        for (int i = 0; i < toolArguments.size(); i++) {
            String option = toolArguments.get(i);
            if (Set.of("--add-exports", "--add-reads", "--add-opens", "--enable-native-access", "--enable-final-field-mutation").contains(option) && i + 1 < toolArguments.size()) {
                runtimeAccess.add(option + " " + toolArguments.get(++i));
            }
        }
        List<ModuleDescriptor> distinctSourceModules = sourceModules.stream()
                .distinct()
                .toList();
        return new Overview(javaVersion, distinctSourceModules, requiredSystemModules(distinctSourceModules, application, systemModules),
                List.copyOf(runtimeAccess));
    }

    private List<String> requiredSystemModules(List<ModuleDescriptor> roots, ModuleFinder application, ModuleFinder systemModules) throws IOException {
        var descriptors = new HashMap<String, ModuleDescriptor>();
        roots.forEach(descriptor -> descriptors.put(descriptor.name(), descriptor));
        var pending = new ArrayDeque<String>();
        roots.forEach(descriptor -> pending.add(descriptor.name()));
        requestedModules.stream()
                .filter(name -> systemModules.find(name).isPresent())
                .forEach(pending::add);

        var visited = new HashSet<String>();
        var requiredSystemModules = new TreeSet<String>();
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!visited.add(name)) {
                continue;
            }

            ModuleDescriptor descriptor = descriptors.get(name);
            if (descriptor == null) {
                Optional<ModuleDescriptor> source = sourceModule(name);
                Optional<ModuleReference> applicationModule = application.find(name);
                Optional<ModuleReference> systemModule = systemModules.find(name);
                descriptor = source.or(() -> applicationModule.map(ModuleReference::descriptor))
                                   .or(() -> systemModule.map(ModuleReference::descriptor))
                                   .orElse(null);
                if (descriptor == null) {
                    continue;
                }
                descriptors.put(name, descriptor);
                if (source.isEmpty() && applicationModule.isEmpty()) {
                    requiredSystemModules.add(name);
                }
            }
            descriptor.requires().stream()
                    .map(Requires::name)
                    .forEach(pending::addLast);
        }
        return List.copyOf(requiredSystemModules);
    }

    private List<ModuleDescriptor> discoverSourceModules() throws IOException {
        var modules = new TreeMap<String, ModuleDescriptor>();
        for (String specification : moduleSourcePath) {
            int equals = specification.indexOf('=');
            if (equals > 0) {
                for (Path root : splitPaths(specification.substring(equals + 1))) {
                    addSourceModule(root, modules);
                }
                continue;
            }
            for (String value : specification.split(Pattern.quote(File.pathSeparator))) {
                for (Path root : discoverModuleRoots(value)) {
                    addSourceModule(root, modules);
                }
            }
        }
        return List.copyOf(modules.values());
    }

    private static void addSourceModule(Path root, Map<String, ModuleDescriptor> modules) throws IOException {
        byte[] source = read(root, "module-info.java");
        if (source == null) {
            return;
        }
        ModuleDescriptor module = parseModuleDescriptor(source);
        modules.putIfAbsent(module.name(), module);
    }

    private static List<Path> discoverModuleRoots(String value) throws IOException {
        Path pattern = Path.of(value);
        int wildcard = -1;
        for (int i = 0; i < pattern.getNameCount(); i++) {
            if (pattern.getName(i)
                       .toString()
                       .contains("*")) {
                wildcard = i;
                break;
            }
        }
        if (wildcard >= 0) {
            Path parent = pattern.getRoot() == null ? Path.of("") : pattern.getRoot();
            for (int i = 0; i < wildcard; i++) {
                parent = parent.resolve(pattern.getName(i));
            }
            if (!Files.isDirectory(parent)) {
                return List.of();
            }
            var matcher = pattern.getFileSystem().getPathMatcher("glob:" + pattern.getName(wildcard));
            var roots = new ArrayList<Path>();
            try (var entries = Files.list(parent)) {
                for (Path entry : entries.filter(path -> matcher.matches(path.getFileName()))
                        .sorted()
                        .toList()) {
                    Path root = entry;
                    for (int i = wildcard + 1; i < pattern.getNameCount(); i++) {
                        root = root.resolve(pattern.getName(i));
                    }
                    roots.add(root);
                }
            }
            return roots;
        }
        if (read(pattern, "module-info.java") != null) {
            return List.of(pattern);
        }
        if (!Files.isDirectory(pattern)) {
            return List.of();
        }
        try (var entries = Files.list(pattern)) {
            return entries.filter(Files::isDirectory)
                          .sorted()
                          .toList();
        }
    }

    private Optional<ModuleDescriptor> sourceModule(String name) throws IOException {
        for (String specification : moduleSourcePath) {
            for (Path root : moduleRoots(specification, name)) {
                byte[] source = read(root, "module-info.java");
                if (source != null) {
                    return Optional.of(parseModuleDescriptor(source));
                }
            }
        }
        return Optional.empty();
    }

    private static ModuleDescriptor parseModuleDescriptor(byte[] bytes) throws IOException {
        String content = new String(bytes, StandardCharsets.UTF_8);
        JavaFileObject source = new SimpleJavaFileObject(URI.create("memory:///module-info.java"), Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
        JavacTask task = JavacTool.create().getTask(null, null, null, List.of("-proc:none"), null, List.of(source));
        ModuleTree module = null;
        for (var unit : task.parse()) {
            if (unit.getModule() != null) {
                module = unit.getModule();
            }
        }
        if (module == null) {
            throw new IOException("Source does not contain a module declaration");
        }

        var descriptor = module.getModuleType() == ModuleKind.OPEN ? ModuleDescriptor.newOpenModule(module.getName()
                .toString())
                : ModuleDescriptor.newModule(module.getName()
                .toString());
        for (var directive : module.getDirectives()) {
            if (directive instanceof RequiresTree requirement) {
                var modifiers = EnumSet.noneOf(Modifier.class);
                if (requirement.isStatic()) {
                    modifiers.add(Modifier.STATIC);
                }
                if (requirement.isTransitive()) {
                    modifiers.add(Modifier.TRANSITIVE);
                }
                descriptor.requires(modifiers, requirement.getModuleName()
                        .toString());
            } else if (directive instanceof ProvidesTree provided) {
                descriptor.provides(
                        provided.getServiceName().toString(),
                        provided.getImplementationNames().stream()
                                .map(Object::toString)
                                .toList());
            } else if (directive instanceof ExportsTree exported) {
                String packageName = exported.getPackageName().toString();
                if (exported.getModuleNames() == null) {
                    descriptor.exports(packageName);
                } else {
                    descriptor.exports(packageName,
                            exported.getModuleNames().stream()
                                    .map(Object::toString)
                                    .collect(Collectors.toSet()));
                }
            }
        }
        return descriptor.build();
    }

    private String optionValue(String expected) {
        for (int i = 0; i < toolArguments.size(); i++) {
            String argument = toolArguments.get(i);
            if (argument.equals(expected) && i + 1 < toolArguments.size()) {
                return toolArguments.get(i + 1);
            }
            if (argument.startsWith(expected + "=")) {
                return argument.substring(expected.length() + 1);
            }
        }
        return null;
    }

    ResolvedType resolve(String name) throws IOException {
        for (String resource : classResources(name)) {
            ResolvedType type = resolveModules(resource, ModuleFinder.of(modulePath.toArray(Path[]::new)));
            if (type != null) {
                return type;
            }
            type = resolveModules(resource, systemFinder());
            if (type != null) {
                return type;
            }
        }
        return resolveSourceOnly(name);
    }

    SourceContent source(ResolvedType type) throws IOException {
        for (Path root : sourcePath) {
            byte[] bytes = read(root, type.sourceRelativePath());
            if (bytes != null) {
                return new SourceContent(bytes, root.toString());
            }
            if (type.moduleName() != null) {
                bytes = read(root, type.moduleName() + "/" + type.sourceRelativePath());
                if (bytes != null) {
                    return new SourceContent(bytes, root.toString());
                }
            }
        }
        if (type.moduleName() != null) {
            for (String specification : moduleSourcePath) {
                for (Path root : moduleRoots(specification, type.moduleName())) {
                    byte[] bytes = read(root, type.sourceRelativePath());
                    if (bytes != null) {
                        return new SourceContent(bytes, root.toString());
                    }
                }
            }
        }
        if (type.systemModule()) {
            Path archive = system.resolve("lib/src.zip");
            byte[] bytes = read(archive, type.moduleName() + "/" + type.sourceRelativePath());
            if (bytes != null) {
                return new SourceContent(bytes, archive.toString());
            }
        }
        return null;
    }

    List<String> javadocArguments(ResolvedType type, Path sourceRoot) {
        var result = new ArrayList<String>();
        for (int i = 0; i < toolArguments.size(); i++) {
            String argument = toolArguments.get(i);
            String inline = inlineValue(argument);
            String option = inline == null ? argument : argument.substring(0, argument.indexOf('='));
            if (option.equals("--add-modules") || option.equals("--limit-modules")) {
                if (inline == null) {
                    i++;
                }
                continue;
            }
            result.add(argument);
        }
        if (type.moduleName() == null) {
            result.add("--source-path");
            result.add(sourceRoot.toString());
        } else {
            result.add("--patch-module");
            result.add(type.moduleName() + "=" + sourceRoot);
        }
        if (type.preview() && !containsOption(result, "--enable-preview")) {
            result.add("--enable-preview");
            if (!containsOption(result, "--source") && !containsOption(result, "--release")) {
                result.add("--source");
                result.add(Integer.toString(Runtime.version()
                        .feature()));
            }
        }
        result.add("--ignore-source-errors");
        result.add("-Xdoclint:none");
        result.add("-quiet");
        return List.copyOf(result);
    }

    private ResolvedType resolveSourceOnly(String requestedName) throws IOException {
        for (String classResource : classResources(requestedName)) {
            String internalName = classResource.substring(0, classResource.length() - ".class".length());
            int slash = internalName.lastIndexOf('/');
            String packagePath = slash < 0 ? "" : internalName.substring(0, slash + 1);
            String simpleName = slash < 0 ? internalName : internalName.substring(slash + 1);
            int nested = simpleName.indexOf('$');
            String sourceName = (nested < 0 ? simpleName : simpleName.substring(0, nested)) + ".java";
            String sourceRelative = packagePath + sourceName;
            for (Path root : sourcePath) {
                if (read(root, sourceRelative) != null) {
                    String binaryName = internalName.replace('/', '.');
                    return new ResolvedType(null, false, binaryName, sourceRelative, null, false);
                }
            }
        }
        return null;
    }

    private ResolvedType resolveModules(String resource, ModuleFinder finder) throws IOException {
        for (ModuleReference reference : finder.findAll().stream()
                .sorted(Comparator.comparing(module -> module.descriptor().name()))
                .toList()) {
            try (var reader = reference.open()) {
                Optional<ByteBuffer> contents = reader.read(resource);
                if (contents.isEmpty()) {
                    continue;
                }
                ByteBuffer buffer = contents.get();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                reader.release(buffer);
                boolean systemModule = reference.location()
                        .map(uri -> uri.getScheme().equals("jrt"))
                        .orElse(false);
                return resolvedType(reference.descriptor().name(), systemModule,
                        bytes);
            }
        }
        return null;
    }

    private static Path findSystemSources(Path system) {
        Path sourceArchive = system.resolve("lib/src.zip");
        return Files.isRegularFile(sourceArchive) ? sourceArchive : null;
    }

    private static void addDiagnostic(List<String> diagnostics, String name, List<String> values) {
        if (!values.isEmpty()) {
            diagnostics.add(name + ": " + String.join(File.pathSeparator, values));
        }
    }

    private ModuleFinder systemFinder() {
        Path running = Path.of(System.getProperty("java.home"))
                .toAbsolutePath()
                .normalize();
        if (system.toAbsolutePath()
                  .normalize()
                  .equals(running)) {
            return ModuleFinder.ofSystem();
        }
        Path jmods = system.resolve("jmods");
        if (!Files.isDirectory(jmods)) {
            return ModuleFinder.of();
        }
        try (var paths = Files.list(jmods)) {
            return ModuleFinder.of(paths.filter(path -> path.toString().endsWith(".jmod"))
                    .toArray(Path[]::new));
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read system modules from " + system, failure);
        }
    }

    private static ResolvedType resolvedType(String moduleName, boolean systemModule, byte[] bytes) {
        ClassModel model = ClassFile.of().parse(bytes);
        String internalName = model.thisClass().asInternalName();
        String binaryName = internalName.replace('/', '.');
        String sourceFile = model.findAttribute(Attributes.sourceFile())
                .map(attribute -> attribute.sourceFile().stringValue())
                .orElseGet(() -> {
                    int slash = internalName.lastIndexOf('/');
                    String simple = slash < 0 ? internalName : internalName.substring(slash + 1);
                    int nested = simple.indexOf('$');
                    return (nested < 0 ? simple : simple.substring(0, nested)) + ".java";
                });
        int slash = internalName.lastIndexOf('/');
        String relative = (slash < 0 ? "" : internalName.substring(0, slash + 1)) + sourceFile;
        boolean preview = bytes.length >= 8 && (bytes[4] & 0xff) == 0xff && (bytes[5] & 0xff) == 0xff;
        return new ResolvedType(moduleName, systemModule, binaryName, relative, bytes, preview);
    }

    private static List<String> classResources(String name) {
        var resources = new ArrayList<String>();
        String candidate = name;
        while (true) {
            resources.add(candidate.replace('.', '/') + ".class");
            int dot = candidate.lastIndexOf('.');
            if (dot < 0) {
                break;
            }
            candidate = candidate.substring(0, dot) + '$' + candidate.substring(dot + 1);
        }
        return List.copyOf(resources);
    }

    private static byte[] read(Path root, String relative) throws IOException {
        if (!Files.exists(root)) {
            return null;
        }
        if (Files.isDirectory(root)) {
            Path candidate = root.resolve(relative);
            return Files.isRegularFile(candidate) ? Files.readAllBytes(candidate) : null;
        }
        try (FileSystem fileSystem = FileSystems.newFileSystem(root)) {
            Path candidate = fileSystem.getPath("/" + relative);
            return Files.isRegularFile(candidate) ? Files.readAllBytes(candidate) : null;
        } catch (ZipException failure) {
            return null;
        }
    }

    private static List<Path> moduleRoots(String specification, String moduleName) {
        int equals = specification.indexOf('=');
        if (equals > 0) {
            if (!specification.substring(0, equals).equals(moduleName)) {
                return List.of();
            }
            return splitPaths(specification.substring(equals + 1));
        }
        var roots = new ArrayList<Path>();
        for (String value : specification.split(Pattern.quote(File.pathSeparator))) {
            int star = value.indexOf('*');
            if (star < 0) {
                roots.add(Path.of(value)
                        .resolve(moduleName));
            } else {
                String expanded = value.substring(0, star) + moduleName + value.substring(star + 1);
                roots.add(Path.of(expanded));
            }
        }
        return List.copyOf(roots);
    }

    private static void addPaths(List<Path> paths, String value) {
        paths.addAll(splitPaths(value));
    }

    private static List<Path> splitPaths(String value) {
        return Pattern.compile(Pattern.quote(File.pathSeparator))
                .splitAsStream(value)
                .filter(path -> !path.isBlank())
                .map(Path::of)
                .toList();
    }

    private static String requiredValue(List<String> arguments, int index, String option) {
        if (index >= arguments.size()) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return arguments.get(index);
    }

    private static String inlineValue(String argument) {
        int equals = argument.indexOf('=');
        return equals > 0 && argument.startsWith("-")
                ? argument.substring(equals + 1)
                : null;
    }

    private static boolean containsOption(List<String> arguments, String option) {
        return arguments.stream().anyMatch(value -> value.equals(option) || value.startsWith(option + "="));
    }

    record ResolvedType(String moduleName, boolean systemModule, String binaryName,
                        String sourceRelativePath, byte[] classBytes, boolean preview) {}

    record SourceContent(byte[] bytes, String origin) {}

    record Overview(String javaVersion, List<ModuleDescriptor> sourceModules, List<String> systemModules,
                    List<String> runtimeAccess) {}
}
