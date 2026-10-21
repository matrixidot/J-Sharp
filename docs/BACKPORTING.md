# Building J# for Java 21

J# is built for Java 25. The same sources also build for Java 21 (D107): the compiler, runtime,
CLI, language server, Gradle plugin and editor plugins all run on Java 21, and J# then emits Java 21
class files. CI builds and tests both variants on every push.

## Build it

```
./gradlew build -PjavaVersion=21
```

Gradle needs a JDK 21 for the toolchain: an installed one is found, otherwise Gradle downloads it.
Everything else is as usual: `-PjavaVersion=21` works with every task (`publishToMavenLocal`,
`cliDistribution`, `vscodeExtension`, ...), and the IntelliJ plugin build in `editors/intellij`
takes it too.

For a release line that stays on Java 21, set `javaVersion=21` in `gradle.properties` instead.
`scripts/runtimes.sh` then bundles Java 21 runtimes too (it reads the same property).

## What changes for Java 21

One property, `javaVersion` in `gradle.properties`, decides:

| What | Where it is used |
| --- | --- |
| The toolchain and `--release` of every module | `build.gradle.kts`, `editors/intellij/build.gradle.kts` |
| The class file version J# emits (release + 44) | `LanguageInfo.JAVA_RELEASE`, `ClassGen.CLASS_FILE_VERSION` |
| The toolchain of `jsharp new` projects | `ProjectTemplates` |
| The bundled Java runtimes | `scripts/runtimes.sh` |

The compiler reads and writes class files with `java.lang.classfile`, final only since Java 24.
Below 24, the build compiles a copy of the sources (`build/backport/<source set>`) whose imports
point at the API's backport, `io.github.dmlloyd:jdk-classfile-backport`: the JDK's code, with the
same classes and methods in the package `io.github.dmlloyd.classfile`. The compiler module then
depends on it. Its license is the JDK's (GPLv2 with the Classpath Exception).

## Keeping it possible

- Use no Java API or language feature newer than Java 21, except `java.lang.classfile`. The
  `java21` CI job fails when something does: `--release 21` rejects newer APIs and syntax.
- Refer to the class file version only through `ClassGen.CLASS_FILE_VERSION`, and to the Java
  release only through `LanguageInfo.JAVA_RELEASE` (or `javaVersion` in build scripts).
- When the backport's version is bumped (`compiler/build.gradle.kts`), pick the one matching the
  JDK the code is written against.

A few messages still say "Java 25" in prose: the README, the VS Code extension's "the language
server is not running" error, and the release notes. A Java 21 release changes those by hand,
along with `gradle/gradle-daemon-jvm.properties` (`./gradlew updateDaemonJvm --jvm-version=21`)
if Gradle itself should run on 21.
