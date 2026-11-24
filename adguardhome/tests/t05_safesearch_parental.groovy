/*
 * t05: Safe Search writes nested per-engine options back through
 * PUT /control/safesearch/settings, falls back to the legacy
 * POST /safesearch/enable|disable on plain-status servers; parental and
 * safebrowsing use the bodyless enable/disable endpoints.
 */
import groovy.json.JsonSlurper

int failures = 0
int passes = 0

def fixturesDir = new File(System.getProperty("fixturesDir"))
def serverFile = new File(System.getProperty("serverDriver"))

// ---- Nested per-engine options preserved via PUT settings ----
Map captured = [:]
def h = HubitatHarness.load(serverFile)
h.routeFile(fixturesDir, "GET", "/control/safesearch/status", "safesearch_status_nested.json")
h.route("PUT", "/control/safesearch/settings", { params -> captured.body = params.body; return [:] })
h.script.SafeSearchOn()

assertEq(true, captured.body?.enabled, "safesearch enabled=true written")
assertEq(true, captured.body?.bing, "bing option preserved")
assertEq(false, captured.body?.duckduckgo, "duckduckgo option preserved")
assertEq(true, captured.body?.google, "google option preserved")
assertEq(false, captured.body?.pixabay, "pixabay option preserved")
assertEq(false, captured.body?.yandex, "yandex option preserved")
assertNoCall(h, "POST", "/control/safesearch/enable", "legacy enable endpoint not used when settings API works")

// ---- Legacy server (plain status) uses enable/disable ----
def h2 = HubitatHarness.load(serverFile)
h2.routeFile(fixturesDir, "GET", "/control/safesearch/status", "safesearch_status_plain.json")
h2.route("POST", "/control/safesearch/enable", { params -> [:] })
h2.script.SafeSearchOn()
assertEq(1, h2.findCalls("POST", "/control/safesearch/enable").size(), "legacy enable endpoint used for plain status")

def h3 = HubitatHarness.load(serverFile)
h3.routeFile(fixturesDir, "GET", "/control/safesearch/status", "safesearch_status_plain.json")
h3.route("POST", "/control/safesearch/disable", { params -> [:] })
h3.script.SafeSearchOff()
assertEq(1, h3.findCalls("POST", "/control/safesearch/disable").size(), "legacy disable endpoint used for plain status")

// ---- Settings PUT failure falls back to legacy endpoint ----
def h4 = HubitatHarness.load(serverFile)
h4.routeFile(fixturesDir, "GET", "/control/safesearch/status", "safesearch_status_nested.json")
h4.routeError("PUT", "/control/safesearch/settings", 404, "Not Found")
h4.route("POST", "/control/safesearch/enable", { params -> [:] })
h4.routeFile(fixturesDir, "GET", "/control/safesearch/status", "safesearch_status_nested.json")
h4.script.SafeSearchOn()
assertEq(1, h4.findCalls("POST", "/control/safesearch/enable").size(), "legacy fallback after settings PUT failure")
assertContains(h4.logMock.at("warn"), "trying legacy endpoint", "fallback is logged as a warning")

// ---- Failed read aborts ----
def h5 = HubitatHarness.load(serverFile)
h5.routeError("GET", "/control/safesearch/status", 500, "boom")
h5.script.SafeSearchOn()
assertEq(0, h5.calls.count { it.method == "POST" || it.method == "PUT" }, "no mutation when safesearch status read fails")

// ---- Parental and safebrowsing ----
def h6 = HubitatHarness.load(serverFile)
h6.route("POST", "/control/parental/enable", { params -> [:] })
h6.routeFile(fixturesDir, "GET", "/control/parental/status", "parental_status.json")
h6.script.ParentalControlOn()
assertEq(1, h6.findCalls("POST", "/control/parental/enable").size(), "parental enable posted")
assertEq("false", eventValue(h6, "parental"), "parental state re-read")

def h7 = HubitatHarness.load(serverFile)
h7.route("POST", "/control/safebrowsing/disable", { params -> [:] })
h7.routeFile(fixturesDir, "GET", "/control/safebrowsing/status", "safebrowsing_status.json")
h7.script.SafeBrowsingOff()
assertEq(1, h7.findCalls("POST", "/control/safebrowsing/disable").size(), "safebrowsing disable posted")

reportAndExit("t05_safesearch_parental")
