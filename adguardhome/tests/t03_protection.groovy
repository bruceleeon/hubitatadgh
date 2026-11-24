/*
 * t03: protection toggling is a read-modify-write of GET /control/dns_info
 * followed by POST /control/dns_config with the FULL document, changing
 * only protection_enabled. A failed authoritative read aborts the mutation.
 */
import groovy.json.JsonSlurper

int failures = 0
int passes = 0

def fixturesDir = new File(System.getProperty("fixturesDir"))
def serverFile = new File(System.getProperty("serverDriver"))
Map captured = [:]

// ---- ProtectionOff preserves the whole DNS document ----
def h = HubitatHarness.load(serverFile)
h.routeFile(fixturesDir, "GET", "/control/dns_info", "dns_info.json")
h.route("POST", "/control/dns_config", { params -> captured.body = params.body; return [:] })
h.routeFile(fixturesDir, "GET", "/control/status", "status.json")
h.routeFile(fixturesDir, "GET", "/control/api/v1/status", "api_status.json")
h.script.ProtectionOff()

def body = captured.body
assertTrue(body instanceof Map, "dns_config POST body is the full dns document")
assertEq(false, body?.protection_enabled, "protection_enabled set to false in posted document")
assertEq(["1.1.1.1", "9.9.9.9"], body?.upstream_dns, "upstream_dns preserved")
assertEq(["8.8.8.8", "1.1.1.1"], body?.bootstrap_dns, "bootstrap_dns preserved")
assertEq("load_balance", body?.upstream_mode, "upstream_mode preserved")
assertEq("REFUSED", body?.blocking_mode, "blocking_mode preserved")
assertEq("1800s", body?.querylog_interval, "querylog_interval preserved")
assertEq("true", eventValue(h, "protection"), "status re-read after change (status fixture says protected=true)")

// ---- ProtectionOn flips to true ----
def h2 = HubitatHarness.load(serverFile)
Map captured2 = [:]
h2.routeFile(fixturesDir, "GET", "/control/dns_info", "dns_info.json")
h2.route("POST", "/control/dns_config", { params -> captured2.body = params.body; return [:] })
h2.routeFile(fixturesDir, "GET", "/control/status", "status.json")
h2.routeFile(fixturesDir, "GET", "/control/api/v1/status", "api_status.json")
h2.script.ProtectionOn()
assertEq(true, captured2.body?.protection_enabled, "protection_enabled set to true in posted document")
assertEq(["1.1.1.1", "9.9.9.9"], captured2.body?.upstream_dns, "upstreams preserved on enable too")

// ---- Failed authoritative read aborts the mutation ----
def h3 = HubitatHarness.load(serverFile)
h3.routeError("GET", "/control/dns_info", 500, "Internal Server Error")
h3.script.ProtectionOff()
assertNoCall(h3, "POST", "/control/dns_config", "no dns_config POST when /dns_info read fails")
assertContains(h3.logMock.at("error"), "Aborting protection change", "abort is reported")

// ---- Failed POST does not pretend success ----
def h4 = HubitatHarness.load(serverFile)
h4.routeFile(fixturesDir, "GET", "/control/dns_info", "dns_info.json")
h4.routeError("POST", "/control/dns_config", 422, "Unprocessable Entity")
h4.script.ProtectionOn()
assertEq(0, h4.findCalls("GET", "/control/status").size(), "status not re-read when POST rejected")
assertTrue(h4.logMock.at("error").size() >= 1, "POST failure logged")

reportAndExit("t03_protection")
