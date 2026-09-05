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

/**
 * Provides browsable API documentation for modular Java applications.
 *
 * <p>JdocServer accepts the standard modular compilation options used by {@code javac}, including
 * module paths, source paths, module source paths, release selection, preview features, and runtime
 * access options. This allows build tools and resolvers to supply the same arguments used for
 * compilation without defining a separate documentation model.
 *
 * <p>For modular applications, JdocServer presents the resolved modules using the selected
 * JDK's standard HTML doclet. Module and package summaries are generated together,
 * while class pages are generated when followed. Locally available JDK sources are
 * part of the same navigation and link space.
 *
 * <h2>Embedding</h2>
 *
 * <p>{@link com.netflix.tools.jdocserver.DocumentationHandler} implements
 * {@link com.sun.net.httpserver.HttpHandler} and can be mounted in an existing
 * {@link com.sun.net.httpserver.HttpServer}. The {@code jdocserver} command provides a
 * ready-to-use local server.
 *
 * @mainClass com.netflix.tools.jdocserver.JdocServer
 * @provides java.util.spi.ToolProvider the {@code jdocserver} documentation server
 * @addExports jdk.javadoc/jdk.javadoc.internal.doclets.formats.html=com.netflix.tools.jdocserver
 * @addExports jdk.javadoc/jdk.javadoc.internal.api=com.netflix.tools.jdocserver
 * @addExports jdk.javadoc/jdk.javadoc.internal.doclets.toolkit.util=com.netflix.tools.jdocserver
 * @addExports jdk.javadoc/jdk.javadoc.internal.tool=com.netflix.tools.jdocserver
 * @addExports jdk.compiler/com.sun.tools.javac.main=com.netflix.tools.jdocserver
 * @addExports jdk.compiler/com.sun.tools.javac.util=com.netflix.tools.jdocserver
 */
module com.netflix.tools.jdocserver {
    requires java.compiler;
    requires java.desktop;
    requires jdk.compiler;
    requires jdk.httpserver;
    requires jdk.javadoc;

    exports com.netflix.tools.jdocserver;
    exports com.netflix.tools.jdocserver.internal to jdk.javadoc;

    provides java.util.spi.ToolProvider with com.netflix.tools.jdocserver.JdocServer;
}
