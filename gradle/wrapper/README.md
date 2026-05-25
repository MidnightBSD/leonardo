# Gradle Wrapper

The `gradle-wrapper.jar` binary required at this path is a small (~63 KB)
bootstrap JAR shipped with every Gradle installation. It cannot be generated
from source by this scaffolding step. Populate it with one of the following:

## Option 1 — Generate via an existing Gradle install

If you have any Gradle 8.x available:

```sh
gradle wrapper --gradle-version 8.10.2 --distribution-type bin
```

This overwrites `gradle/wrapper/gradle-wrapper.jar`,
`gradle/wrapper/gradle-wrapper.properties`, `gradlew`, and `gradlew.bat` in
one step. The other three files are already in place; only the JAR is missing.

## Option 2 — Download the binary from Gradle's release distribution

```sh
curl -L -o gradle/wrapper/gradle-wrapper.jar \
  https://raw.githubusercontent.com/gradle/gradle/v8.10.2/gradle/wrapper/gradle-wrapper.jar
```

Verify the SHA-256 against the value published at
<https://gradle.org/release-checksums/> before committing.

## Option 3 — Extract from a Gradle distribution archive

```sh
curl -L -o /tmp/gradle.zip \
  https://services.gradle.org/distributions/gradle-8.10.2-bin.zip
unzip -j /tmp/gradle.zip 'gradle-8.10.2/lib/plugins/gradle-wrapper-*.jar' \
  -d /tmp/wrapper-jar
# inside, locate gradle-wrapper.jar (not gradle-wrapper-shared.jar) and copy it
```

Once the JAR is in place, this `README.md` file can be deleted.
