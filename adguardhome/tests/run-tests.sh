#!/usr/bin/env bash
#
# Test runner for the AdGuard Home Hubitat package.
#
# Runs every mock-based functional test (t*.groovy) against fixture
# responses. No network access, no Hubitat installation and no credentials
# are required. Exits nonzero when any test fails.
#
# Each test file is a standalone Groovy script that expects two shared
# binding counters ("failures", "passes") and the assertion helpers from
# support/test-functions.groovy. The runner builds a combined script per
# test: the counters are plain (binding) assignments so the helper closures
# can update them, followed by the helpers, followed by the test body with
# its own local counter declarations removed.
set -u
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/default-java}"

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURES_DIR="$TESTS_DIR/fixtures"
SUPPORT_DIR="$TESTS_DIR/support"
SERVER_DRIVER="$TESTS_DIR/../drivers/AdGuardHome_Server.groovy"
CLIENT_DRIVER="$TESTS_DIR/../drivers/AdGuardHome_Client.groovy"

RUN_TMP="$(mktemp -d)"
trap 'rm -rf "$RUN_TMP"' EXIT

# Precompile the shared harness classes once; the test scripts reference
# them by classpath, not by source path.
if ! groovyc -d "$RUN_TMP/classes" "$SUPPORT_DIR/HubitatHarness.groovy"; then
    echo "run-tests: FAILED to compile support/HubitatHarness.groovy"
    exit 1
fi

overall_status=0
total=0
failed=0

for test_file in "$TESTS_DIR"/t*.groovy; do
    test_name="$(basename "$test_file")"
    total=$((total + 1))
    combined="$RUN_TMP/${test_name%.groovy}.combined.groovy"
    {
        echo 'failures = 0'
        echo 'passes = 0'
        cat "$SUPPORT_DIR/test-functions.groovy"
        sed '/^int failures = 0$/d; /^int passes = 0$/d' "$test_file"
    } > "$combined"

    echo "--- $test_name"
    # NOTE: the Groovy 2.4 launcher ignores "-cp" when it follows a "-D"
    # argument, so the classpath option must come first.
    if ! groovy \
        -cp "$RUN_TMP/classes" \
        -DfixturesDir="$FIXTURES_DIR" \
        -DserverDriver="$SERVER_DRIVER" \
        -DclientDriver="$CLIENT_DRIVER" \
        "$combined"; then
        failed=$((failed + 1))
        overall_status=1
    fi
done

echo "----------------------------------------"
if [ "$overall_status" -eq 0 ]; then
    echo "run-tests: all $total test files passed"
else
    echo "run-tests: FAILED ($failed of $total test files failed)"
fi
exit "$overall_status"
