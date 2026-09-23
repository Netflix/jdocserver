# jdocserver

[![Maven Central](https://img.shields.io/maven-central/v/com.netflix/com.netflix.tools.jdocserver)](https://central.sonatype.com/artifact/com.netflix/com.netflix.tools.jdocserver)
![JDK 25+](https://img.shields.io/badge/JDK-25%2B-blue)

`jdocserver` turns a modular Java compilation context into a local, browsable API reference. It uses the selected JDK's standard HTML doclet, so project and platform documentation has the same navigation, search, and presentation as generated Javadoc without requiring the complete site to be built before the server starts.

Capabilities include:

- Browse the JDK without additional configuration
- Browse the modules selected by a `ja` workspace
- Reuse standard module, source, release, system, and access options from compilation
- Generate the overview in the background and type pages on demand
- Follow links between project and JDK documentation within one local server
- Embed the documentation handler in another JDK HTTP server

> [!IMPORTANT]
> This tool is currently in preview. Please share feedback for any of the tools in [Discussions](https://github.com/Netflix/ja/discussions).

## Installation

> [!NOTE]
> Netflix engineers should use the internally bundled toolchain rather than installing this tool separately.

Follow the `ja` [Installation Guide](https://github.com/Netflix/ja#installation) to install the bundled tools, including `jdocserver`.

For standalone use, download the modular JAR from [Maven Central](https://central.sonatype.com/artifact/com.netflix/com.netflix.tools.jdocserver). JDK 25 or later is required. Run it as module `com.netflix.tools.jdocserver`:

```sh
java --module-path com.netflix.tools.jdocserver-VERSION.jar --module com.netflix.tools.jdocserver --browse
```

JMOD artifacts are also published for building custom runtime images.

## Quick start

From a `ja` workspace, open API documentation for the modules selected by the current directory:

```sh
ja doc --browse
```

Open a qualified type directly:

```sh
ja doc --browse java.lang.String
```

Browse the running JDK with the standalone command:

```sh
jdocserver --browse
jdocserver --browse=java.lang.String
```

Browsing the JDK requires its source archive at `lib/src.zip`.

## Documentation

The [wiki](https://github.com/Netflix/jdocserver/wiki) covers:

- [Browsing](https://github.com/Netflix/jdocserver/wiki/Browsing)
- [Running the server](https://github.com/Netflix/jdocserver/wiki/Running-the-Server)
- [Embedding](https://github.com/Netflix/jdocserver/wiki/Embedding)
