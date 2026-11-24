/*
 * Assertion helpers shared by every test script. Each test file is run as a
 * standalone Groovy script: it must declare its own failures counter and
 * exit nonzero when any assertion fails.
 */
import groovy.json.JsonSlurper

// assertEq compares loosely on purpose: Groovy JSON booleans/strings are
// compared with their string forms where tests request it via strEq.
def assertTrue = { boolean cond, String msg ->
    if (!cond) {
        failures++
        System.err.println "  ASSERT FAILED: ${msg}"
    } else {
        passes++
    }
}
def assertEq = { Object expected, Object actual, String msg ->
    if (expected != actual) {
        failures++
        System.err.println "  ASSERT FAILED: ${msg} (expected=<${expected}> actual=<${actual}>)"
    } else {
        passes++
    }
}
def assertContains = { haystack, String needle, String msg ->
    def list = (haystack instanceof List) ? haystack : haystack.toString()
    boolean found = (list instanceof List) ? list.any { it.toString().contains(needle) } : list.contains(needle)
    if (!found) {
        failures++
        System.err.println "  ASSERT FAILED: ${msg} (needle=<${needle}> not found)"
    } else {
        passes++
    }
}
def assertNotContains = { haystack, String needle, String msg ->
    def list = (haystack instanceof List) ? haystack : haystack.toString()
    boolean found = (list instanceof List) ? list.any { it.toString().contains(needle) } : list.contains(needle)
    if (found) {
        failures++
        System.err.println "  ASSERT FAILED: ${msg} (needle=<${needle}> unexpectedly present)"
    } else {
        passes++
    }
}
def assertNoCall = { HubitatHarness h, String method, String path, String msg ->
    def found = h.findCalls(method, path)
    if (!found.isEmpty()) {
        failures++
        System.err.println "  ASSERT FAILED: ${msg} (found ${found.size()} unexpected ${method} ${path} call(s))"
    } else {
        passes++
    }
}
def eventValue = { HubitatHarness h, String attr ->
    def found = h.responses.findAll { it.name == attr }
    return found.isEmpty() ? null : found[-1].value
}
// Routes every status read used by the server driver refresh to its fixture.
def addStatusRoutes = { HubitatHarness h, File fixturesDir ->
    h.routeFile(fixturesDir, "GET", "/control/status", "status.json")
    h.routeFile(fixturesDir, "GET", "/control/api/v1/status", "api_status.json")
    h.routeFile(fixturesDir, "GET", "/control/dns_info", "dns_info.json")
    h.routeFile(fixturesDir, "GET", "/control/filtering/status", "filtering_status.json")
    h.routeFile(fixturesDir, "GET", "/control/dhcp/status", "dhcp_status.json")
    h.routeFile(fixturesDir, "GET", "/control/parental/status", "parental_status.json")
    h.routeFile(fixturesDir, "GET", "/control/safebrowsing/status", "safebrowsing_status.json")
    h.routeFile(fixturesDir, "GET", "/control/safesearch/status", "safesearch_status_nested.json")
    h.routeFile(fixturesDir, "GET", "/control/blocked_services/list", "blocked_services_list.json")
    return h
}
def reportAndExit = { String suite ->
    if (failures > 0) {
        System.err.println "${suite}: FAIL (${failures} failed, ${passes} passed)"
        System.exit(1)
    }
    println "${suite}: PASS (${passes} assertions)"
    System.exit(0)
}
