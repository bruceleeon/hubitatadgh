/*
 * t08: client driver refresh. Reads the authoritative client from the
 * parent server (both /clients/find shapes), publishes attributes, reports
 * missing clients without corrupting state, and tolerates unknown ids.
 */
import groovy.json.JsonSlurper

int failures = 0
int passes = 0

def fixturesDir = new File(System.getProperty("fixturesDir"))
def serverFile = new File(System.getProperty("serverDriver"))
def clientFile = new File(System.getProperty("clientDriver"))

// The parent server harness services the client's HTTP calls.
def server = HubitatHarness.load(serverFile)
server.routeFile(fixturesDir, "GET", "/control/clients/find?ip0=192.168.1.50", "clients_find_data.json")

def ch = HubitatHarness.load(clientFile, [parent: server.script])
ch.device.attrs["clientIds"] = "[192.168.1.50, aa:bb:cc:dd:ee:ff]"
ch.script.refresh()

assertEq("livingroom-tv", eventValue(ch, "name"), "client name published")
assertEq("false", eventValue(ch, "use_global_settings"), "use_global_settings published")
assertEq("true", eventValue(ch, "filtering"), "filtering published")
assertEq("false", eventValue(ch, "parental"), "parental published")
assertEq("false", eventValue(ch, "safeBrowsing"), "safeBrowsing published")
assertEq("false", eventValue(ch, "safeSearch"), "safeSearch published")
assertEq("[netflix]", eventValue(ch, "blockedServices"), "per-client blocked services published")
assertEq("[192.168.1.50, aa:bb:cc:dd:ee:ff]", eventValue(ch, "clientIds"), "client ids published")
assertEq("[tv]", eventValue(ch, "clientTags"), "client tags published")
assertEq(0, ch.logMock.at("error").size(), "clean client refresh logs no errors")
assertEq("192.168.1.50", ch.state.primaryId, "primary id remembered")

// ---- legacy plain-map /clients/find shape ----
def server2 = HubitatHarness.load(serverFile)
server2.routeFile(fixturesDir, "GET", "/control/clients/find?ip0=192.168.1.50", "clients_find_legacy.json")
def ch2 = HubitatHarness.load(clientFile, [parent: server2.script])
ch2.device.attrs["clientIds"] = "[192.168.1.50]"
ch2.script.refresh()
assertEq("livingroom-tv", eventValue(ch2, "name"), "legacy find shape also refreshes")

// ---- missing client: error reported, attributes untouched ----
def server3 = HubitatHarness.load(serverFile)
server3.routeFile(fixturesDir, "GET", "/control/clients/find?ip0=192.168.1.50", "clients_find_missing.json")
def ch3 = HubitatHarness.load(clientFile, [parent: server3.script])
ch3.device.attrs["clientIds"] = "[192.168.1.50]"
ch3.script.refresh()
assertEq(0, ch3.responses.size(), "missing client publishes no events")
assertContains(ch3.logMock.at("error"), "no longer exists", "missing client reported")

// ---- failed lookup: exception path sanitized ----
def server4 = HubitatHarness.load(serverFile)
server4.routeError("GET", "/control/clients/find?ip0=192.168.1.50", 401, "Unauthorized")
def ch4 = HubitatHarness.load(clientFile, [parent: server4.script])
ch4.device.attrs["clientIds"] = "[192.168.1.50]"
ch4.script.refresh()
assertEq(0, ch4.responses.size(), "failed lookup publishes no events")
assertTrue(ch4.logMock.at("error").size() >= 1, "failed lookup reported")
assertNotContains(ch4.logMock.messages, "dGVzdGVyOnRlc3QtcGFzc3dvcmQ", "credentials not logged by client driver")

// ---- unknown id: no HTTP call, clear error ----
def server5 = HubitatHarness.load(serverFile)
def ch5 = HubitatHarness.load(clientFile, [parent: server5.script])
ch5.script.refresh()
assertEq(0, server5.calls.size(), "no HTTP calls when no client id is known")
assertContains(ch5.logMock.at("error"), "No client ID known", "unknown id reported")

// ---- parentEvent seeds the primary id and attribute ----
def server6 = HubitatHarness.load(serverFile)
server6.routeFile(fixturesDir, "GET", "/control/clients/find?ip0=aa:bb:cc:dd:ee:ff", "clients_find_data.json")
def ch6 = HubitatHarness.load(clientFile, [parent: server6.script])
ch6.script.parentEvent("clientIds", ["192.168.1.50", "aa:bb:cc:dd:ee:ff"])
assertEq("192.168.1.50", ch6.state.primaryId, "parentEvent stores primary id")
assertEq("[192.168.1.50, aa:bb:cc:dd:ee:ff]", eventValue(ch6, "clientIds"), "parentEvent publishes attribute")

reportAndExit("t08_client_refresh")
