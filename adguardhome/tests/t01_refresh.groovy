/*
 * t01: refresh() reads every status endpoint and publishes the documented
 * attribute values for both the current /control/status schema and the
 * legacy schema. No network access.
 */
import groovy.json.JsonSlurper

int failures = 0
int passes = 0

def fixturesDir = new File(System.getProperty("fixturesDir"))
def serverFile = new File(System.getProperty("serverDriver"))

// ---- Current schema (protected=true, no version field -> api fallback) ----
def h = HubitatHarness.load(serverFile)
addStatusRoutes(h, fixturesDir)
h.script.refresh()

assertEq("true", eventValue(h, "protection"), "current status schema sets protection=true")
assertEq("v0.107.68", eventValue(h, "version"), "version falls back to /api/v1/status when absent")
assertEq("false", eventValue(h, "filtering"), "filtering enabled=false from filtering/status")
assertEq("false", eventValue(h, "dhcp"), "dhcp enabled=false from dhcp/status")
assertEq("false", eventValue(h, "parental"), "parental enabled=false")
assertEq("false", eventValue(h, "safeBrowsing"), "safeBrowsing enabled=false")
assertEq("false", eventValue(h, "safeSearch"), "safeSearch enabled=false")
assertEq("[youtube, tiktok]", eventValue(h, "blockedServices"), "blocked services list published")
assertEq(0, h.logMock.at("error").size(), "clean refresh logs no errors")

// ---- Legacy schema (version + protection_enabled on /control/status) ----
def h2 = HubitatHarness.load(serverFile)
h2.routeFile(fixturesDir, "GET", "/control/status", "status_legacy.json")
h2.routeFile(fixturesDir, "GET", "/control/filtering/status", "filtering_status.json")
h2.routeFile(fixturesDir, "GET", "/control/dhcp/status", "dhcp_status.json")
h2.routeFile(fixturesDir, "GET", "/control/parental/status", "parental_status.json")
h2.routeFile(fixturesDir, "GET", "/control/safebrowsing/status", "safebrowsing_status.json")
h2.routeFile(fixturesDir, "GET", "/control/safesearch/status", "safesearch_status_plain.json")
h2.routeFile(fixturesDir, "GET", "/control/blocked_services/list", "blocked_services_empty.json")
h2.script.refresh()

assertEq("v0.103.5", eventValue(h2, "version"), "legacy status exposes version directly")
assertEq("true", eventValue(h2, "protection"), "legacy protection_enabled is honoured")
assertEq("[]", eventValue(h2, "blockedServices"), "empty service list is a successful read")
assertEq(0, h2.logMock.at("error").size(), "empty service list does not log an error")

reportAndExit("t01_refresh")
