/*
 * t04: DHCP toggling preserves interface_name and the full v4/v6 config
 * (POST /control/dhcp/set_config requires the complete schema); filtering
 * toggling preserves the configured refresh interval.
 */
import groovy.json.JsonSlurper

int failures = 0
int passes = 0

def fixturesDir = new File(System.getProperty("fixturesDir"))
def serverFile = new File(System.getProperty("serverDriver"))
Map captured = [:]

// ---- DhcpOn preserves interface and v4/v6 ----
def h = HubitatHarness.load(serverFile)
h.routeFile(fixturesDir, "GET", "/control/dhcp/status", "dhcp_status.json")
h.route("POST", "/control/dhcp/set_config", { params -> captured.body = params.body; return [:] })
h.script.DhcpOn()

def body = captured.body
assertTrue(body instanceof Map, "dhcp set_config POST body is the full dhcp document")
assertEq(true, body?.enabled, "enabled set to true")
assertEq("eth0", body?.interface_name, "interface_name preserved")
assertEq("192.168.1.1", body?.v4?.gateway_ip, "v4.gateway_ip preserved")
assertEq("192.168.1.100", body?.v4?.range_start, "v4.range_start preserved")
assertEq("192.168.1.200", body?.v4?.range_end, "v4.range_end preserved")
assertEq(3600, body?.v4?.lease_duration, "v4.lease_duration preserved")
assertEq("fe80::", body?.v6?.range_start, "v6.range_start preserved")
assertEq("true", eventValue(h, "dhcp"), "dhcp attribute re-read after change")

// ---- Failed authoritative read aborts the mutation ----
def h2 = HubitatHarness.load(serverFile)
h2.routeError("GET", "/control/dhcp/status", 401, "Unauthorized")
h2.script.DhcpOff()
assertNoCall(h2, "POST", "/control/dhcp/set_config", "no set_config POST when /dhcp/status read fails")
assertContains(h2.logMock.at("error"), "Aborting DHCP change", "abort is reported")

// ---- FilteringOff preserves the configured interval ----
def h3 = HubitatHarness.load(serverFile)
Map captured3 = [:]
h3.routeFile(fixturesDir, "GET", "/control/filtering/status", "filtering_status.json")
h3.route("POST", "/control/filtering/config", { params -> captured3.body = params.body; return [:] })
h3.script.FilteringOff()
assertEq(false, captured3.body?.enabled, "filtering enabled=false posted")
assertEq(72, captured3.body?.interval, "configured refresh interval preserved (not defaulted)")

// ---- FilteringOn ----
def h4 = HubitatHarness.load(serverFile)
Map captured4 = [:]
h4.routeFile(fixturesDir, "GET", "/control/filtering/status", "filtering_status.json")
h4.route("POST", "/control/filtering/config", { params -> captured4.body = params.body; return [:] })
h4.script.FilteringOn()
assertEq(true, captured4.body?.enabled, "filtering enabled=true posted")
assertEq(72, captured4.body?.interval, "interval preserved on enable")

// ---- Failed filtering read aborts ----
def h5 = HubitatHarness.load(serverFile)
h5.routeError("GET", "/control/filtering/status", 500, "boom")
h5.script.FilteringOn()
assertNoCall(h5, "POST", "/control/filtering/config", "no filtering/config POST when read fails")

reportAndExit("t04_dhcp_filtering")
