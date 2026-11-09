#!/usr/bin/env bash
# Compiles and runs the pure-Java tests (protocol package + Formatters) with plain javac.
# No Android SDK or Gradle needed.
#
# Usage: scripts/run_protocol_tests.sh /path/to/junit-4.13.2.jar /path/to/hamcrest-core-1.3.jar
set -euo pipefail

JUNIT_JAR="${1:?Usage: $0 junit.jar hamcrest-core.jar}"
HAMCREST_JAR="${2:?Usage: $0 junit.jar hamcrest-core.jar}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/java/com/example/qrstream"
TST="$ROOT/app/src/test/java/com/example/qrstream"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

javac -Xlint:all -d "$OUT/main" "$SRC"/protocol/*.java "$SRC"/util/Formatters.java
javac -cp "$OUT/main:$JUNIT_JAR:$HAMCREST_JAR" -d "$OUT/test" "$TST"/protocol/*.java "$TST"/util/*.java

java -cp "$OUT/main:$OUT/test:$JUNIT_JAR:$HAMCREST_JAR" org.junit.runner.JUnitCore \
  com.example.qrstream.protocol.FrameCodecTest \
  com.example.qrstream.protocol.ChunkerTest \
  com.example.qrstream.protocol.ParityRecoveryTest \
  com.example.qrstream.protocol.ReassemblerTest \
  com.example.qrstream.protocol.LossSimulatorTest \
  com.example.qrstream.protocol.EndToEndTest \
  com.example.qrstream.protocol.BenchmarkTest \
  com.example.qrstream.util.FormattersTest
