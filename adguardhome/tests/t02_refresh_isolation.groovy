/*
 * t02: refresh() isolates failures per endpoint; failed reads are logged
 * with sanitized messages and do not abort the remaining reads or publish
 * fabricated values. An auth failure never leaks credentials to the log.
 */
import groovy.json.JsonSlurper

int failures = 0
int passes = 0

def fixturesDir = new File(System.getProperty("fixturesDir"))
def serverFile = new File(System.getProperty("serverDriver"))

// ---- One failing endpoint (401 on dhcp) does not stop the others ----
def h = HubitatHarness.load(serverFile)
addStatusRoutes(h, fixturesDir)
h.routes.clear()
// re-add everything except dhcp, which now fails
h.routeFile(fixturesDir, "GET", "/control/status", "status.json")
h.routeFile(fixturesDir, "GET", "/control/api/v1/status", "api_status.json")
h.routeFile(fixturesDir, "GET", "/control/dns_info", "dns_info.json")
h.routeFile(fixturesDir, "GET", "/control/filtering/status", "filtering_status.json")
h.routeError("GET", "/control/dhcp/status", 401, "Unauthorized")
h.routeFile(fixturesDir, "GET", "/control/parental/status", "parental_status.json")
h.routeFile(fixturesDir, "GET", "/control/safebrowsing/status", "safebrowsing_status.json")
h.routeFile(fixturesDir, "GET", "/control/safesearch/status", "safesearch_status_nested.json")
h.routeFile(fixturesDir, "GET", "/control/blocked_services/list", "blocked_services_list.json")
h.script.refresh()

assertEq(null, eventValue(h, "dhcp"), "failed dhcp read publishes no attribute value")
assertEq("false", eventValue(h, "filtering"), "filtering still read after dhcp failure")
assertEq("false", eventValue(h, "parental"), "parental still read after dhcp failure")
assertEq("[youtube, tiktok]", eventValue(h, "blockedServices"), "blocked services still read after dhcp failure")
assertContains(h.logMock.at("error"), "Refresh of 'dhcp' failed", "dhcp failure reported by endpoint name")
assertContains(h.logMock.at("error"), "Refresh completed with 1 failed endpoint read", "failure count summarised")

// Log sanitization: the Basic credential and password must never appear.
assertNotContains(h.logMock.messages, "dGVzdGVyOnRlc3QtcGFzc3dvcmQ", "base64 credentials never logged")
assertNotContains(h.logMock.messages, "test-password", "password never logged")

// ---- Every endpoint failing: no crash, no events ----
def h2 = HubitatHarness.load(serverFile)
["status", "filtering/status", "dhcp/status", "parental/status", "safebrowsing/status",
 "safesearch/status", "blocked_services/list", "dns_info", "api/v1/status"].each {
    h2.routeError("GET", "/control/${it}", 500, "Internal Server Error")
}
h2.script.refresh()
assertEq(0, h2.responses.size(), "no events sent when all reads fail")
assertTrue(h2.logMock.at("error").size() >= 7, "all endpoint failures reported")

// ---- Server IP missing: abort before any HTTP call ----
def h3 = HubitatHarness.load(serverFile, [serverIP: ""])
h3.script.refresh()
assertEq(0, h3.calls.size(), "no HTTP calls when server IP not configured")
assertContains(h3.logMock.at("error"), "Server IP/FQDN not entered", "missing IP reported")

reportAndExit("t02_refresh_isolation")
