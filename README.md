# jdocserver

[![Maven Central](https://img.shields.io/maven-central/v/com.netflix/com.netflix.tools.jdocserver)](https://central.sonatype.com/artifact/com.netflix/com.netflix.tools.jdocserver)
![JDK 25+](https://img.shields.io/badge/JDK-25%2B-blue)

`jdocserver` provides browsable API documentation for Java compilation contexts. It resolves classes and sources from standard JDK tool arguments and renders requested types with the standard doclet.

> [!IMPORTANT]
> This tool is currently in preview. We are collecting all preview feedback in the [`ja` repository](https://github.com/Netflix/ja): use [Issues](https://github.com/Netflix/ja/issues) to report problems and [Discussions](https://github.com/Netflix/ja/discussions) for feedback, questions, and suggestions.

## Installation

Follow the `ja` [Installation Guide](https://github.com/Netflix/ja#installation) to install the bundled tools, including `jdocserver`.

For standalone use, `jar` and `jmod` artifacts for the tool are available on Maven Central.

## Quick Start

Start JdocServer without compilation arguments to browse the running JDK. This requires the selected
JDK's source archive at `lib/src.zip`:

```sh
jdocserver
```

To browse an application compilation context, supply an existing argument file:

```sh
jdocserver @main.args
```

JdocServer reports the selected context and inputs, lazy generation behavior, binding, and URL:

```text
JDK home: /path/to/jdk
Module path: build/modules:lib/example.jar
Module source path: src
Source path: lib/example-sources.jar
Temporary workspace: /tmp/jdocserver-1234567890
Temporary workspace is removed on shutdown.
Generating standard Javadoc overview in the background...
Type documentation is generated on demand.
Binding to loopback by default. For all interfaces use "-b 0.0.0.0" or "-b ::".
Serving Java API documentation on 127.0.0.1 port 8000
URL http://127.0.0.1:8000/
```

Browse it automatically with:

```sh
jdocserver --browse @main.args
```

For the default JDK and modular contexts, the index is the standard Javadoc module overview. Repeated `-group` options provide the familiar module tabs:

```sh
-group "Source Modules" 'com.example.*' \
-group "Java SE" 'java.*' \
-group "JDK" 'jdk.*' \
-group "Other" '*'
```

The overview, module pages, package pages, navigation, search data, styles, and scripts are generated in the background by the selected JDK's HTML doclet. Requests for those resources wait for that shared generation task. Class pages are generated when their standard links are followed. Links between generated pages target the local server, including links into locally supplied JDK sources.

## Compilation Contexts

JdocServer accepts modular compilation options:

```sh
jdocserver \
  --module-path build/modules:lib/example.jar \
  --module-source-path src \
  --source-path lib/example-sources.jar \
  --system "$JAVA_HOME"
```

Module-path, module-source-path, source-path, and system entries retain their standard JDK ordering and lookup semantics. Argument files use the same one-argument-per-line form produced by Jig, Jist workspace generation, and other JDK tooling.

## Server

JdocServer listens on `127.0.0.1` port `8000` by default. Select the binding or port explicitly with:

```sh
jdocserver -b 0.0.0.0 --port 8080 @main.args
```

The short option `-p` retains its standard Java meaning, `--module-path`; server ports therefore use `--port`.

Overview generation begins during startup without delaying the server binding. Requests join that generation if it is still running. Type documentation is generated on demand; concurrent requests for the same type share one generation, and generated resources remain available for the lifetime of the server.

## Embedding

`DocumentationHandler` implements the JDK `HttpHandler` API and can be mounted in any `jdk.httpserver` server:

```java
try (var docs = DocumentationHandler.create(arguments)) {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", docs);
    server.start();
}
```

`DocumentationHandler.optionChecker()` exposes the standard documentation and file-manager options accepted by `create`. A host can use `indexUri()` or `typeUri(qualifiedName)` to obtain a request URI without depending on the handler's route layout. Resolve that request URI against the hosting server's base URI before presenting or opening it.

## Build and Test

JdocServer is a JA source workspace:

```sh
./build.sh
./test.sh
```

`build.sh` exports the modular JAR and platform JMODs containing the native `jdocserver` command under
`build/repository`.
