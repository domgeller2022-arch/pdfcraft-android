#!/bin/sh

# Gradle startup script for Gradle 8.12.1

# Stops when either the shell has no "set" (e.g., dash) or when POSIX mode is on
# but the POSIXLY_CORRECT var was not originally set.
if command -v cygpath >/dev/null 2>&1; then
    APP_HOME=$(cygpath --unix "$APP_HOME")
    DOTS=$(cygpath --unix .);
fi

APP_HOME=$(cd "$(dirname "$0")" && pwd) || {
    echo "Could not determine script directory."; exit 1;
}
APP_NAME="gradle"

# Resolve the home directory for the user, falling back to the current user
# home directory if HOME is unset.
if [ -z "$HOME" ]; then
    HOME=$(eval printf "%s" ~)
fi

# Set the runtime classpath
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Determine the Java executable
if [ -n "$JAVA_HOME" ]; then
    JAVACMD=$(command -v "$JAVA_HOME/bin/java" 2>/dev/null || true)
    if [ -z "$JAVACMD" ]; then
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ]; then
        echo "Error: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
        echo "Try: export JAVA_HOME=<path_to_jdk>" >&2
        exit 1
    fi
else
    JAVACMD=$(command -v java 2>/dev/null || true)
    if [ -z "$JAVACMD" ]; then
        echo "Error: java is not found in your PATH." >&2
        echo "Install a JDK (e.g., 'brew install openjdk@17') or set JAVA_HOME." >&2
        exit 1
    fi
fi

# Add default JVM options here. You can also use JAVA_OPTS to pass
# JVM options to the script.
DEFAULT_JVM_OPTS=""

# Use the POSIX locale (simplifies string manipulation and avoids
# encoding issues on non-English systems).
export LC_ALL=C

# Use the maximum available file descriptor limit, if we have one.
if command -v ulimit >/dev/null 2>&1; then
    CONFIGURE_LIMITS=$(ulimit -a 2>/dev/null | grep "file size" || echo "not set")
    ulimit -n 65536 2>/dev/null || true
fi

# Escape application args
if [ $# -gt 0 ]; then
    case "$*" in
        *[!\ ]*)
            set -- "$@"
            ;;
        *)
            set -- $(printf '%s\n' "$*" | sed 's/[^ ]*/"&"/g')
            ;;
    esac
fi

# For Cygwin, reset the executable path
if command -v cygpath >/dev/null 2>&1; then
    SWING=$(cygpath --windows "$JAVA_HOME/lib/swing.properties" 2>/dev/null
            || echo "")
fi

# Split the classpath using the path separator
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar":

exec "$JAVACMD" \
    $DEFAULT_JVM_OPTS \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
