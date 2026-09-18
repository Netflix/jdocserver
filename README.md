# jdocserver

[![Maven Central](https://img.shields.io/maven-central/v/com.netflix/com.netflix.tools.jdocserver)](https://central.sonatype.com/artifact/com.netflix/com.netflix.tools.jdocserver)
![JDK 25+](https://img.shields.io/badge/JDK-25%2B-blue)

`jdocserver` turns a modular Java compilation context into a local, browsable API reference. It uses the selected JDK's standard HTML doclet, so project and platform documentation has the same navigation, search, and presentation as generated Javadoc without requiring the complete site to be built before the server starts.

- Browse the JDK without additional configuration
- Browse the modules selected by a `ja` workspace
- Reuse standard module, source, release, system, and access options from compilation
- Generate the overview in the background and type pages on demand
- Follow links between project and JDK documentation within one local server
- Embed the documentation handler in another JDK HTTP server

> [!IMPORTANT]
> This tool is currently in preview. We are collecting feedback for all of the tools together in [Discussions](https://github.com/Netflix/ja/discussions).

## Installation

> [!NOTE]
> Netflix engineers should use the internally bundled toolchain rather than installing this tool separately.

Follow the `ja` [Installation Guide](https://github.com/Netflix/ja#installation) to install the bundled tools, including `jdocserver`.

For standalone use, `jar` and `jmod` artifacts are available on Maven Central. We require JDK 25 or later.

## Quick start

From a `ja` workspace, open API documentation for the modules selected by the current directory:

```sh
ja doc --browse
```

Open a qualified type directly:

```sh
ja doc --browse java.lang.String
ja doc --browse com.example.application.Main
```

`ja` resolves the workspace's modular compilation context and supplies it to `jdocserver`.

Use the standalone command to browse the running JDK:

```sh
jdocserver --browse
```

Open a JDK type directly with the standalone command:

```sh
jdocserver --browse=java.lang.String
```

Browsing the JDK requires its source archive at `lib/src.zip`.

Without `--browse`, the server prints its URL and remains in the foreground until interrupted:

```console
$ jdocserver
...
Generating standard Javadoc overview in the background...
Type documentation is generated on demand.
Serving Java API documentation on 127.0.0.1 port 8000
URL http://127.0.0.1:8000/
```

## Browse a project

Supply the same modular Java options used to compile the project:

```sh
jdocserver \
  --module-path build/modules:lib/example.jar \
  --module-source-path src \
  --module com.example.application \
  --source-path lib/example-sources.jar
```

JdocServer also accepts argument files. For example, `main.args` can contain one argument on each non-empty line:

```text
--module-path
build/modules:lib/example.jar
--module-source-path
src
--module
com.example.application
--source-path
lib/example-sources.jar
```

Start the server with that context and open its overview:

```sh
jdocserver --browse @main.args
```

Module-path, module-source-path, source-path, and system entries retain their standard JDK ordering and lookup semantics. Other supported inputs include `--add-modules`, `--limit-modules`, `--add-reads`, `--add-exports`, `--patch-module`, `--release`, `--source`, and `--enable-preview`.

Select another JDK with `--system`:

```sh
jdocserver --system /path/to/jdk @main.args
```

The selected JDK must provide `lib/src.zip` so its module and package documentation can be included.

## Generated documentation

The overview, module and package pages, navigation, search data, styles, and scripts are generated in the background by the selected JDK's standard HTML doclet. Requests for those resources wait for the shared generation task when necessary.

Type pages are generated when their standard links are followed or when a direct `/type` request is made. Concurrent requests for the same type share one generation task, and generated resources remain available for the lifetime of the server. Links between generated pages target the local server, including links to locally supplied JDK sources.

Generated files live in a temporary workspace that is removed when the server shuts down.

For the default JDK, the index is the standard Javadoc module overview.

## Server options

JdocServer listens on `127.0.0.1` port `8000` by default. Select the binding or port explicitly:

```sh
jdocserver --bind-address 0.0.0.0 --port 8080 @main.args
```

The default loopback binding keeps the server local. Binding to `0.0.0.0` or `::` exposes it on all available interfaces.

The short option `-p` retains its standard Java meaning, `--module-path`; server ports therefore use `--port`.

Open the index or a type when the server starts:

```sh
jdocserver --browse @main.args
jdocserver --browse=com.example.application.Main @main.args
```

## Embedding

`DocumentationHandler` implements the JDK `HttpHandler` API and can be mounted in any `jdk.httpserver` server:

```java
try (var docs = DocumentationHandler.create(arguments)) {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", docs);
    server.start();
    try {
        // Run the host application.
    } finally {
        server.stop(0);
    }
}
```

The host controls the HTTP server lifecycle. Closing the handler removes its temporary documentation output.

`DocumentationHandler.optionChecker()` exposes the standard documentation and file-manager options accepted by `create`. Use `indexUri()` or `typeUri(qualifiedName)` to obtain a request URI without depending on the handler's route layout, then resolve it against the hosting server's base URI.
