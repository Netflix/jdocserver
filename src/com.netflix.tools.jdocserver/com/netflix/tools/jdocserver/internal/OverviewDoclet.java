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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import javax.lang.model.element.Element;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.ModuleElement.ExportsDirective;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileManager.Location;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardLocation;

import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.internal.doclets.formats.html.HtmlDoclet;
import jdk.javadoc.internal.doclets.toolkit.util.ClassTree;
import jdk.javadoc.internal.tool.DocEnvImpl;

public final class OverviewDoclet extends HtmlDoclet {
    private static final ThreadLocal<BinaryConfiguration> binaryConfiguration = new ThreadLocal<>();

    public OverviewDoclet() {
        super(null);
    }

    public static void configureBinaryElements(List<String> modules, boolean includeSourceElements) {
        binaryConfiguration.set(new BinaryConfiguration(modules.stream()
                .distinct()
                .sorted()
                .toList(),
                includeSourceElements));
    }

    public static void clearBinaryElements() {
        binaryConfiguration.remove();
    }

    @Override
    public boolean run(DocletEnvironment environment) {
        BinaryConfiguration configuration = binaryConfiguration.get();
        if (configuration == null) {
            return super.run(environment);
        }

        var original = (DocEnvImpl) environment;
        var specified = new LinkedHashSet<Element>();
        var included = new LinkedHashSet<Element>();
        if (configuration.includeSourceElements()) {
            specified.addAll(original.getSpecifiedElements());
            included.addAll(original.getIncludedElements());
        }

        var binaryElements = new LinkedHashSet<Element>();
        var binaryTypes = new LinkedHashSet<TypeElement>();
        var elements = original.getElementUtils();
        JavaFileManager fileManager = original.getJavaFileManager();
        try {
            for (String moduleName : configuration.modules()) {
                ModuleElement module = elements.getModuleElement(moduleName);
                if (module == null) {
                    throw new IllegalStateException("Module not found: " + moduleName);
                }
                included.add(module);
                binaryElements.add(module);
                Location location = fileManager.getLocationForModule(StandardLocation.SYSTEM_MODULES, moduleName);
                if (location == null) {
                    throw new IllegalStateException("System module location not found: " + moduleName);
                }

                for (String packageName : exportedPackages(module)) {
                    Iterable<JavaFileObject> files = fileManager instanceof BinaryFileManager binary ? binary.listPrimary(location, packageName, Set.of(Kind.CLASS), false) : fileManager.list(location, packageName, Set.of(Kind.CLASS), false);
                    for (JavaFileObject file : files) {
                        String typeName = fileManager.inferBinaryName(location, file);
                        int dot = typeName.lastIndexOf('.');
                        String simpleName = dot < 0 ? typeName : typeName.substring(dot + 1);
                        NestingKind nesting = file.getNestingKind();
                        if (simpleName.equals("package-info")
                                || nesting != null && nesting != NestingKind.TOP_LEVEL
                                || nesting == null && simpleName.contains("$")) {
                            continue;
                        }
                        TypeElement type = elements.getTypeElement(module, typeName);
                        if (type == null) {
                            throw new IllegalStateException("Type not found: " + typeName);
                        }
                        if (original.etable.isSelected(type)) {
                            specified.add(type);
                        }
                        addType(type, original, included, binaryElements, binaryTypes);
                    }
                    var packageElement = elements.getPackageElement(module, packageName);
                    if (packageElement != null) {
                        included.add(packageElement);
                        binaryElements.add(packageElement);
                    }
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return super.run(new BinaryEnvironment(original, specified, included, binaryElements, binaryTypes));
    }

    private static List<String> exportedPackages(ModuleElement module) {
        return module.getDirectives().stream()
                .filter(directive -> directive instanceof ExportsDirective exported && (exported.getTargetModules() == null || exported.getTargetModules().isEmpty()))
                .map(directive -> ((ExportsDirective) directive).getPackage()
                        .getQualifiedName()
                        .toString())
                .sorted()
                .toList();
    }

    private static void addType(TypeElement type, DocEnvImpl original, Set<Element> included,
            Set<Element> binaryElements, Set<TypeElement> binaryTypes) {
        if (!original.etable.isSelected(type) || !binaryTypes.add(type)) {
            return;
        }
        included.add(type);
        binaryElements.add(type);
        for (Element child : type.getEnclosedElements()) {
            if (child instanceof TypeElement nested) {
                addType(nested, original, included, binaryElements, binaryTypes);
            }
        }
    }

    @Override
    public void generateClassFiles(ClassTree tree) {}

    @Override
    protected Function<String, String> getResourceKeyMapper(DocletEnvironment environment) {
        var parent = super.getResourceKeyMapper(environment);
        return key -> {
            String mapped = parent.apply(key);
            return mapped.equals("doclet.All_Modules") ? "doclet.navModules" : mapped;
        };
    }

    private record BinaryConfiguration(List<String> modules, boolean includeSourceElements) {}

    private static final class BinaryEnvironment extends DocEnvImpl {
        private final DocEnvImpl original;
        private final Set<? extends Element> specified;
        private final Set<? extends Element> included;
        private final Set<? extends Element> binaryElements;
        private final Set<? extends TypeElement> binaryTypes;

        BinaryEnvironment(DocEnvImpl original, Set<? extends Element> specified, Set<? extends Element> included,
                          Set<? extends Element> binaryElements, Set<? extends TypeElement> binaryTypes) {
            super(original.toolEnv, original.etable);
            this.original = original;
            this.specified = Set.copyOf(specified);
            this.included = Set.copyOf(included);
            this.binaryElements = Set.copyOf(binaryElements);
            this.binaryTypes = Set.copyOf(binaryTypes);
        }

        @Override
        public Set<? extends Element> getSpecifiedElements() {
            return specified;
        }

        @Override
        public Set<? extends Element> getIncludedElements() {
            return included;
        }

        @Override
        public boolean isIncluded(Element element) {
            if (binaryElements.contains(element)) {
                return true;
            }
            Element enclosing = element.getEnclosingElement();
            if (enclosing != null && isBinaryElement(enclosing)) {
                return etable.isSelected(element);
            }
            return original.isIncluded(element);
        }

        private boolean isBinaryElement(Element element) {
            if (binaryElements.contains(element)) {
                return true;
            }
            Element enclosing = element.getEnclosingElement();
            return enclosing != null && isBinaryElement(enclosing);
        }

        @Override
        public boolean isSelected(Element element) {
            return etable.isSelected(element);
        }

        @Override
        public Kind getFileKind(TypeElement type) {
            return binaryTypes.contains(type) ? Kind.CLASS : original.getFileKind(type);
        }
    }
}
