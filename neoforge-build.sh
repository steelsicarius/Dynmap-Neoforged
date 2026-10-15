#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Builds the NeoForge 26.x dynmap platform modules (Minecraft 26.x).
#
#   ./neoforge-build.sh                      # build every neoforge-26.x module
#   ./neoforge-build.sh neoforge-26.1.2      # build a single module
#   NEOFORGE_JAVA_HOME=/path/to/jdk-25 ./neoforge-build.sh
#
# This host's Gradle 8.14 wrapper cannot run the legacy platform modules
# under a modern JVM (fabric-loom needs Java 21+, and Gradle 8.14 cannot run
# on Java 25), so this launcher drives the alternate single-project build
# script with a Java 8-24 JVM; the neoforge tasks inside fork each module's
# own Gradle 9 wrapper with a Java 25 toolchain (see build-neoforge.gradle).
# ---------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")"

find_root_jdk() {
    # A JVM the repo's Gradle 8.14 wrapper can run (8-24). Honors JAVA_HOME.
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        echo "$JAVA_HOME"; return
    fi
    for j in /usr/java/jdk-21 /usr/java/jdk-17 \
             /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-17-openjdk \
             /usr/lib/jvm/java-1.8.0-openjdk /usr/lib/jvm/java-8-openjdk; do
        if [ -x "$j/bin/java" ]; then echo "$j"; return; fi
    done
    echo ""
}

ROOT_JDK="$(find_root_jdk)"
if [ -z "$ROOT_JDK" ]; then
    echo "ERROR: no JVM found to run the Gradle 8.14 wrapper (needs Java 8-24)." >&2
    echo "Set JAVA_HOME and retry." >&2
    exit 1
fi
echo "==> Root build JVM: $ROOT_JDK"

exec env JAVA_HOME="$ROOT_JDK" ./gradlew -b build-neoforge.gradle "$@"
