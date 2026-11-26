#!/bin/sh
# Kestrel Gradle wrapper launcher.
# If gradle/wrapper/gradle-wrapper.jar is missing, it is downloaded once from the
# official Gradle repository (tag v8.11.1), then the pinned Gradle version from
# gradle/wrapper/gradle-wrapper.properties is used for the build.
set -e

APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
  echo "gradle-wrapper.jar not found. Downloading..." >&2
  mkdir -p "$APP_HOME/gradle/wrapper"
  TMP_JAR="$WRAPPER_JAR.tmp"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$TMP_JAR" "$WRAPPER_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$TMP_JAR" "$WRAPPER_URL"
  else
    echo "ERROR: curl or wget is required to download gradle-wrapper.jar" >&2
    exit 1
  fi
  mv "$TMP_JAR" "$WRAPPER_JAR"
fi

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="java"
fi

exec "$JAVACMD" -Xmx64m -Xms64m \
  -Dorg.gradle.appname=gradlew \
  -classpath "$WRAPPER_JAR" \
  org.gradle.wrapper.GradleWrapperMain "$@"
