#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_JAR="$ROOT/analytics-server/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar"
CONNECTION_CLASSES="$ROOT/analytics-server/connection-ingestion/target/classes"
RUNNER_SOURCE="$ROOT/scripts/RuntimeProfileSparkCheckRunner.java"
OUTPUT_DIR="$ROOT/analytics-server/platform-app/target/runtime-profile-spark-wsl-check"
SPARK_SUBMIT_PATH="${SPARK_SUBMIT_PATH:-}"

if [[ -z "$SPARK_SUBMIT_PATH" ]]; then
  echo "Set SPARK_SUBMIT_PATH to the installed WSL spark-submit executable." >&2
  exit 2
fi
if [[ ! -x "$SPARK_SUBMIT_PATH" ]]; then
  echo "SPARK_SUBMIT_PATH is not an executable file." >&2
  exit 2
fi
if [[ ! -r "$APP_JAR" || ! -d "$CONNECTION_CLASSES" || ! -r "$RUNNER_SOURCE" ]]; then
  echo "Build the platform-app reactor and ensure connection-ingestion target/classes exists." >&2
  exit 2
fi

if [[ -n "${RUNTIME_PROFILE_CHECK_JAVA_HOME:-}" ]]; then
  JAVA_HOME="$RUNTIME_PROFILE_CHECK_JAVA_HOME"
elif [[ -x /opt/jdk-17.0.12/bin/java && -x /opt/jdk-17.0.12/bin/javac ]]; then
  JAVA_HOME=/opt/jdk-17.0.12
elif [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
fi
JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"
if [[ ! -x "$JAVA" || ! -x "$JAVAC" ]]; then
  echo "A Java 17 JDK is required (set JAVA_HOME explicitly)." >&2
  exit 2
fi
JAVA_MAJOR="$($JAVA -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
if [[ "$JAVA_MAJOR" != 17 ]]; then
  echo "Java 17 is required; selected runtime major is ${JAVA_MAJOR:-unknown}." >&2
  exit 2
fi

mkdir -p "$OUTPUT_DIR/classes"
"$JAVAC" -d "$OUTPUT_DIR/classes" "$RUNNER_SOURCE"

MODULE_CLASSES=(
  "$ROOT/analytics-server/platform-common/target/classes"
  "$CONNECTION_CLASSES"
  "$ROOT/analytics-server/warehouse-pipeline/target/classes"
  "$ROOT/analytics-server/metric-analysis/target/classes"
  "$ROOT/analytics-server/ai-decision/target/classes"
  "$ROOT/analytics-server/platform-app/target/classes"
  "$OUTPUT_DIR/classes"
)
for module_classes in "${MODULE_CLASSES[@]}"; do
  if [[ ! -d "$module_classes" ]]; then
    echo "Missing current workspace classes: ${module_classes#"$ROOT"/}" >&2
    exit 2
  fi
done

LOADER_PATH="$(IFS=,; echo "${MODULE_CLASSES[*]}")"
"$JAVA" \
  -Dloader.path="$LOADER_PATH" \
  -Dloader.main=RuntimeProfileSparkCheckRunner \
  -Dspark.submit.path="$SPARK_SUBMIT_PATH" \
  -Druntime.profile.expectedClasses="$CONNECTION_CLASSES" \
  -cp "$APP_JAR" \
  org.springframework.boot.loader.launch.PropertiesLauncher
