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

package com.netflix.tools.jdocserver.internal;

import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleDescriptor.Exports;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileManager.Location;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

import com.sun.tools.javac.main.DelegatingJavaFileManager;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Options;

/**
 * Keeps release symbols as the primary system-module input while completing
 * implementation-only modules and types from the running image. {@code ct.sym}
 * intentionally omits those symbols even when a source module descriptor
 * requires an internal module or refers to an internal service type; the
 * standard doclet completes such directives while writing module summaries.
 */
public final class BinaryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> implements StandardJavaFileManager {
    private final StandardJavaFileManager fallback;
    private final Map<Location, Location> fallbackLocations = new IdentityHashMap<>();
    private final Map<JavaFileObject, Location> fallbackFiles = new IdentityHashMap<>();
    private final Set<Location> fallbackModuleLocations = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, String> systemPackageModules;
    private final Set<String> systemExportedPackages;

    private BinaryFileManager(StandardJavaFileManager release, StandardJavaFileManager fallback) {
        super(release);
        this.fallback = fallback;
        var packageModules = new LinkedHashMap<String, String>();
        var exportedPackages = new HashSet<String>();
        ModuleLayer.boot()
                .modules()
                .forEach(module -> {
                    module.getDescriptor()
                          .packages()
                          .forEach(packageName -> packageModules.put(packageName, module.getName()));
                    module.getDescriptor().exports().stream()
                            .filter(exported -> !exported.isQualified())
                            .map(Exports::source)
                            .forEach(exportedPackages::add);
                });
        systemPackageModules = Map.copyOf(packageModules);
        systemExportedPackages = Set.copyOf(exportedPackages);
    }

    public static void installWhenReady(Context context) {
        Options.instance(context).whenReady(ignored -> {
            JavaFileManager manager = context.get(JavaFileManager.class);
            if (!(manager instanceof DelegatingJavaFileManager delegating) || !(manager instanceof StandardJavaFileManager release) || !(delegating.getBaseFileManager() instanceof StandardJavaFileManager fallback)) {
                return;
            }
            context.put(JavaFileManager.class, (JavaFileManager) null);
            context.put(JavaFileManager.class, new BinaryFileManager(release, fallback));
        });
    }

    public Iterable<JavaFileObject> listPrimary(Location location, String packageName, Set<Kind> kinds,
            boolean recurse)
            throws IOException {
        return fileManager.list(location, packageName, kinds, recurse);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromFiles(Iterable<? extends File> files) {
        return fileManager.getJavaFileObjectsFromFiles(files);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromPaths(Collection<? extends Path> paths) {
        return fileManager.getJavaFileObjectsFromPaths(paths);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjects(File... files) {
        return fileManager.getJavaFileObjects(files);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjects(Path... paths) {
        return fileManager.getJavaFileObjects(paths);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromStrings(Iterable<String> names) {
        return fileManager.getJavaFileObjectsFromStrings(names);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjects(String... names) {
        return fileManager.getJavaFileObjects(names);
    }

    @Override
    public void setLocation(Location location, Iterable<? extends File> files) throws IOException {
        fileManager.setLocation(location, files);
    }

    @Override
    public void setLocationFromPaths(Location location, Collection<? extends Path> paths) throws IOException {
        fileManager.setLocationFromPaths(location, paths);
    }

    @Override
    public void setLocationForModule(Location location, String moduleName, Collection<? extends Path> paths) throws IOException {
        fileManager.setLocationForModule(location, moduleName, paths);
    }

    @Override
    public Iterable<? extends File> getLocation(Location location) {
        return fileManager.getLocation(location);
    }

    @Override
    public Iterable<? extends Path> getLocationAsPaths(Location location) {
        return fileManager.getLocationAsPaths(location);
    }

    @Override
    public Path asPath(FileObject file) {
        Location fallbackLocation = file instanceof JavaFileObject javaFile ? fallbackFiles.get(javaFile) : null;
        return fallbackLocation == null ? fileManager.asPath(file) : fallback.asPath(file);
    }

    @Override
    public void setPathFactory(PathFactory factory) {
        fileManager.setPathFactory(factory);
    }

    @Override
    public Iterable<JavaFileObject> list(Location location, String packageName, Set<Kind> kinds,
            boolean recurse)
            throws IOException {
        Iterable<JavaFileObject> primary = fileManager.list(location, packageName, kinds, recurse);
        Location fallbackLocation = fallbackLocation(location);
        if (fallbackLocation == null || !kinds.contains(Kind.CLASS)) {
            return primary;
        }

        var files = new LinkedHashMap<String, JavaFileObject>();
        for (JavaFileObject file : primary) {
            files.put(fileManager.inferBinaryName(location, file), file);
        }
        if (files.isEmpty() || !systemExportedPackages.contains(packageName)) {
            addFallbackFiles(files, fallbackLocation, packageName, kinds, recurse);
        }
        if (files.isEmpty()) {
            String moduleName = systemPackageModules.get(packageName);
            if (moduleName != null) {
                Location owner = fallback.getLocationForModule(StandardLocation.SYSTEM_MODULES, moduleName);
                if (owner != null && owner != fallbackLocation) {
                    addFallbackFiles(files, owner, packageName, kinds, recurse);
                }
            }
        }
        return new ArrayList<>(files.values());
    }

    @Override
    public Location getLocationForModule(Location location, String moduleName) throws IOException {
        Location result = fileManager.getLocationForModule(location, moduleName);
        if (result != null || location != StandardLocation.SYSTEM_MODULES) {
            return result;
        }
        result = fallback.getLocationForModule(location, moduleName);
        if (result != null) {
            fallbackModuleLocations.add(result);
        }
        return result;
    }

    @Override
    public Location getLocationForModule(Location location, JavaFileObject file) throws IOException {
        Location fallbackLocation = fallbackFiles.get(file);
        return fallbackLocation == null ? fileManager.getLocationForModule(location, file) : fallbackLocation;
    }

    @Override
    public Iterable<Set<Location>> listLocationsForModules(Location location) throws IOException {
        Iterable<Set<Location>> primary = fileManager.listLocationsForModules(location);
        if (location != StandardLocation.SYSTEM_MODULES) {
            return primary;
        }

        var locations = new LinkedHashMap<String, Set<Location>>();
        for (Set<Location> candidates : primary) {
            for (Location candidate : candidates) {
                locations.put(fileManager.inferModuleName(candidate), candidates);
            }
        }
        for (Set<Location> candidates : fallback.listLocationsForModules(location)) {
            for (Location candidate : candidates) {
                String moduleName = fallback.inferModuleName(candidate);
                if (!locations.containsKey(moduleName)) {
                    locations.put(moduleName, candidates);
                    fallbackModuleLocations.addAll(candidates);
                }
            }
        }
        return locations.values();
    }

    @Override
    public String inferModuleName(Location location) throws IOException {
        return fallbackModuleLocations.contains(location) ? fallback.inferModuleName(location) : fileManager.inferModuleName(location);
    }

    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, Kind kind) throws IOException {
        JavaFileObject file = fileManager.getJavaFileForInput(location, className, kind);
        if (file != null || kind != Kind.CLASS) {
            return file;
        }
        Location fallbackLocation = fallbackLocation(location);
        if (fallbackLocation == null) {
            return null;
        }
        file = fallback.getJavaFileForInput(fallbackLocation, className, kind);
        if (file != null) {
            fallbackFiles.put(file, fallbackLocation);
        }
        return file;
    }

    @Override
    public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
        FileObject file = fileManager.getFileForInput(location, packageName, relativeName);
        if (file != null) {
            return file;
        }
        Location fallbackLocation = fallbackLocation(location);
        return fallbackLocation == null ? null : fallback.getFileForInput(fallbackLocation, packageName, relativeName);
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        Location fallbackLocation = fallbackFiles.get(file);
        return fallbackLocation == null ? fileManager.inferBinaryName(location, file) : fallback.inferBinaryName(fallbackLocation, file);
    }

    @Override
    public boolean contains(Location location, FileObject file) throws IOException {
        if (file instanceof JavaFileObject javaFile && fallbackFiles.containsKey(javaFile)) {
            return fallback.contains(fallbackFiles.get(javaFile), file);
        }
        return fileManager.contains(location, file);
    }

    private void addFallbackFiles(Map<String, JavaFileObject> files, Location location, String packageName,
            Set<Kind> kinds, boolean recurse)
            throws IOException {
        for (JavaFileObject file : fallback.list(location, packageName, kinds, recurse)) {
            String name = fallback.inferBinaryName(location, file);
            if (!files.containsKey(name)) {
                files.put(name, file);
                fallbackFiles.put(file, location);
            }
        }
    }

    private Location fallbackLocation(Location location) throws IOException {
        if (fallbackModuleLocations.contains(location)) {
            return location;
        }
        Location cached = fallbackLocations.get(location);
        if (cached != null) {
            return cached;
        }
        String moduleName = fileManager.inferModuleName(location);
        Location result = moduleName == null ? null : fallback.getLocationForModule(StandardLocation.SYSTEM_MODULES, moduleName);
        if (result != null) {
            fallbackLocations.put(location, result);
        }
        return result;
    }
}
