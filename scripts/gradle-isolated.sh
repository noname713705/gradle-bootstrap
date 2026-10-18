#!/usr/bin/env bash
# Run an early Gradle distribution (0.x, started by org.gradle.BootstrapMain) with only
# BootstrapMain on java.class.path.
#
# Why: the standard bin/gradle puts the whole Gradle jar on java.class.path. Groovy's groovyc
# Ant task appends java.class.path to the compile classpath (Ant Path.concatSystemClasspath),
# so when an old Gradle compiles newer Gradle sources, the builder's own classes shadow the
# sources being compiled. BootstrapMain itself loads lib/*.jar into its own class loader, so
# Gradle runs exactly as with bin/gradle.
#
# usage: gradle-isolated.sh <gradle distribution dir> [gradle arguments...]
#   e.g. gradle-isolated.sh target/gradle-0.4 clean explodedDistBase
# JAVA_HOME must point to the JDK to use (tools.jar is passed like bin/gradle does).
set -euo pipefail

if [ $# -lt 1 ] || [ ! -d "$1/lib" ]; then
    echo "usage: $0 <gradle distribution dir> [gradle arguments...]" >&2
    exit 2
fi
dist=$(cd "$1" && pwd -P)
shift
: "${JAVA_HOME:?JAVA_HOME must be set}"

# only for distributions started by BootstrapMain
if ! grep -q 'org.gradle.BootstrapMain' "$dist/bin/gradle"; then
    echo "$dist/bin/gradle does not start org.gradle.BootstrapMain, not supported" >&2
    exit 1
fi

# the bootstrap jar, next to the distribution: <dist>-bootstrap.jar (built once)
bootstrap_jar="$dist-bootstrap.jar"
if [ ! -f "$bootstrap_jar" ]; then
    gradle_jar=""
    for jar in "$dist"/lib/gradle-*.jar; do
        if unzip -l "$jar" org/gradle/BootstrapMain.class >/dev/null 2>&1; then
            gradle_jar=$jar
            break
        fi
    done
    if [ -z "$gradle_jar" ]; then
        echo "no jar with org/gradle/BootstrapMain.class in $dist/lib" >&2
        exit 1
    fi
    work=$(mktemp -d)
    trap 'rm -rf "$work"' EXIT
    # BootstrapMain and the org.gradle classes it needs, followed recursively in the bytecode (javap):
    # 0.4 needs nothing else, 0.5 org.gradle.util.BootstrapUtil, 0.8 also org.gradle.api.UncheckedIOException...
    todo="org/gradle/BootstrapMain"
    seen=" "
    while [ -n "$todo" ]; do
        cls=${todo%% *}
        todo=$(echo "${todo#"$cls"}" | sed 's/^ *//')
        case "$seen" in *" $cls "*) continue ;; esac
        seen="$seen$cls "
        # the class and its inner classes (unzip exits with 11 when a pattern matches nothing)
        (cd "$work" && unzip -q -o "$gradle_jar" "$cls.class" "$cls\$*.class" 2>/dev/null) || true
        for f in "$work/$cls.class" "$work/$cls"\$*.class; do
            [ -f "$f" ] || continue
            name=${f#"$work/"}
            name=${name%.class}
            refs=$("$JAVA_HOME/bin/javap" -v -classpath "$work" "${name//\//.}" 2>/dev/null \
                | grep -oE '= Class +#[0-9]+ +// +"?\[*L?org/gradle/[A-Za-z0-9_/]+' | grep -oE 'org/gradle/[A-Za-z0-9_/]+' | sort -u | tr '\n' ' ' || true)
            todo="$todo $refs"
        done
    done
    if [ ! -f "$work/org/gradle/BootstrapMain.class" ]; then
        echo "could not extract org/gradle/BootstrapMain.class from $gradle_jar" >&2
        exit 1
    fi
    (cd "$work" && "$JAVA_HOME/bin/jar" cf "$bootstrap_jar" org)
    echo "created $bootstrap_jar from $(basename "$gradle_jar"): $(cd "$work" && find org -name '*.class' | wc -l) classes" >&2
fi

# same options as bin/gradle
exec "$JAVA_HOME/bin/java" ${GRADLE_OPTS:-} \
    -classpath "$bootstrap_jar" \
    -Dgradle.home="$dist" \
    -Dtools.jar="$JAVA_HOME/lib/tools.jar" \
    org.gradle.BootstrapMain "$@"
