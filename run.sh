#!/usr/bin/env bash
#
# MoneyMind launcher.
#
#   ./run.sh          build everything and run the packaged jar on :8080
#   ./run.sh dev      run the backend only, for use alongside `npm run dev` in ui/
#
set -euo pipefail
cd "$(dirname "$0")"

# The default `java` on this machine is JDK 8; Spring Boot 3.x needs 17+.
if [[ -z "${JAVA_HOME:-}" ]]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21)"
    export JAVA_HOME
fi
echo "Using JDK: $JAVA_HOME"

case "${1:-run}" in
  dev)
    echo "Backend only on http://localhost:8080 — run 'npm run dev' in ui/ for the UI."
    ./mvnw -DskipFrontend spring-boot:run
    ;;
  run)
    ./mvnw clean package
    echo "MoneyMind on http://localhost:8080"
    "$JAVA_HOME/bin/java" -jar target/moneymind.jar
    ;;
  *)
    echo "usage: $0 [run|dev]" >&2
    exit 64
    ;;
esac
