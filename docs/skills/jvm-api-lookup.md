---
title: Looking up JVM APIs
description: Before calling a JVM dependency's API, read its real signatures with javap against the jar in ~/.m2 instead of guessing.
version: 1.0.0
tags: [jvm, javap, dependencies]
---

# Looking up JVM APIs

Use this before writing code that calls a class from a Java/Kotlin/Scala dependency whose
API you have not just read in this session. Method names, parameter order and nullability
drift between versions; guessing produces code that does not compile. The jar on disk is
the truth for the version the project actually uses.

## 1. Find the version

Read it from the project's `pom.xml` (or the parent pom's `<dependencyManagement>` or a
`${...}` property it points at): you need `groupId`, `artifactId` and `version`.

## 2. Find the jar

Maven stores it at `~/.m2/repository/<groupId with dots as slashes>/<artifactId>/<version>/<artifactId>-<version>.jar`:

```
ls ~/.m2/repository/io/modelcontextprotocol/kotlin-sdk-client-jvm/0.14.0/
```

If the directory is missing, the dependency has not been downloaded: run
`mvn -q dependency:resolve` once and look again. Kotlin multiplatform libraries ship the
JVM classes in the `-jvm` artifact; use that one.

## 3. List the classes, then read one

```
jar tf ~/.m2/repository/<group>/<artifact>/<version>/<artifact>-<version>.jar | grep -i <ClassName>
javap -cp <jar> <fully.qualified.ClassName>
```

`javap` shows public members by default. Add `-p` for private ones, and use
`'Outer$Inner'` (quoted) for nested classes. If the class refers to types from another
jar, add it to the classpath: `-cp <jar1>:<jar2>`.

Kotlin types show up as their JVM shape: a `suspend` function has a trailing
`Continuation` parameter, a default argument is a `...$default` method, and a property is
a `getX`/`setX` pair.

## 4. Then write the call

Copy names and parameter types from the `javap` output. If a `-sources.jar` sits next to
the jar, `unzip -p <sources-jar> <path/to/File.kt>` gives you the docs too.
