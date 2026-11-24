/*
 * t09: client driver mutations always read the authoritative client first
 * and post the documented {name, data} update schema, preserving ids, tags,
 * parents and upnames. Failed reads, vanished clients and rejected updates
 * abort without corrupting the server or the device state.
 */
import groovy.json.JsonSlurper

int failures = 0
int passes = 0

def fixturesDir = new File(System.getProperty("fixturesDir"))
def serverFile = new File(System.getProperty("serverDriver"))
def clientFile = new File(System.getProperty("clientDriver"))

def freshClientHarness = { Map captured, String fixture ->
    def server = HubitatHarness.load(serverFile)
    server.routeFile(fixturesDir, "GET", "/control/clients/find?ip0=192.168.1.50", fixture)
    server.route("POST", "/control/clients/update", { params -> captured.body = params.body; return [:] })
    def ch = HubitatHarness.load(clientFile, [parent: server.script])
    ch.device.attrs["clientIds"] = "[192.168.1.50]"
    return [ch, server]
}

// ---- blockService merges into the live client's blocked services ----
Map captured = [:]
def (ch, server) = freshClientHarness(captured, "clients_find_data.json")
ch.script.blockService("facebook, youtube")
def body = captured.body
assertTrue(body instanceof Map && body.name != null && body.data != null, "update posts {name, data} schema")
assertEq("livingroom-tv", body?.name, "top-level name identifies the client")
assertEq(["netflix", "facebook", "youtube"], body?.data?.blocked_services, "existing blocked services preserved, new ones added")
assertEq(["192.168.1.50", "aa:bb:cc:dd:ee:ff"], body?.data?.ids, "ids preserved")
assertEq(["tv"], body?.data?.tags, "tags preserved")
assertEq(["tv-upname"], body?.data?.upnames, "upnames preserved")
assertEq(1, body?.data?.parents?.size(), "parents preserved")
assertEq("example.com", body?.data?.parents?.getAt(0)?.domain, "parent domain preserved")
assertEq(false, body?.data?.use_global_settings, "use_global_settings preserved")
// after a successful update the driver re-reads the client
assertTrue(server.findCalls("GET", "/control/clients/find").size() >= 2, "client re-read after successful update")

// ---- unblockService removes only requested services ----
Map captured2 = [:]
def (ch2, server2) = freshClientHarness(captured2, "clients_find_data.json")
ch2.script.unblockService("netflix")
assertEq([], captured2.body?.data?.blocked_services, "requested service removed")
assertEq(["192.168.1.50", "aa:bb:cc:dd:ee:ff"], captured2.body?.data?.ids, "ids still preserved")

// ---- flag toggles change only the requested field ----
Map captured3 = [:]
def (ch3, s3) = freshClientHarness(captured3, "clients_find_data.json")
ch3.script.FilteringOff()
assertEq(false, captured3.body?.data?.filtering_enabled, "filtering_enabled toggled")
assertEq(["netflix"], captured3.body?.data?.blocked_services, "blocked services untouched by flag toggle")

Map captured4 = [:]
def (ch4, s4) = freshClientHarness(captured4, "clients_find_data.json")
ch4.script.ParentalControlOn()
assertEq(true, captured4.body?.data?.parental_enabled, "parental_enabled toggled")

Map captured5 = [:]
def (ch5, s5) = freshClientHarness(captured5, "clients_find_data.json")
ch5.script.SafeBrowsingOn()
assertEq(true, captured5.body?.data?.safebrowsing_enabled, "safebrowsing_enabled toggled")

Map captured6 = [:]
def (ch6, s6) = freshClientHarness(captured6, "clients_find_data.json")
ch6.script.SafeSearchOff()
assertEq(false, captured6.body?.data?.safesearch_enabled, "safesearch_enabled toggled")

Map captured7 = [:]
def (ch7, s7) = freshClientHarness(captured7, "clients_find_data.json")
ch7.script.GlobalSettingsOn()
assertEq(true, captured7.body?.data?.use_global_settings, "use_global_settings toggled")

Map captured8 = [:]
def (ch8, s8) = freshClientHarness(captured8, "clients_find_data.json")
ch8.script.GlobalBlockListOff()
assertEq(false, captured8.body?.data?.use_global_blocked_services, "use_global_blocked_services toggled")

// ---- legacy plain-map find shape works for mutations too ----
Map captured9 = [:]
def (ch9, s9) = freshClientHarness(captured9, "clients_find_legacy.json")
ch9.script.GlobalSettingsOff()
assertEq(false, captured9.body?.data?.use_global_settings, "mutation works with legacy find shape")

// ---- authoritative read failure aborts the mutation ----
def serverA = HubitatHarness.load(serverFile)
serverA.routeError("GET", "/control/clients/find?ip0=192.168.1.50", 500, "boom")
def chA = HubitatHarness.load(clientFile, [parent: serverA.script])
chA.device.attrs["clientIds"] = "[192.168.1.50]"
chA.script.FilteringOff()
assertNoCall(serverA, "POST", "/control/clients/update", "no update POST when authoritative read fails")
assertContains(chA.logMock.at("error"), "Aborting client update", "abort reported")

// ---- vanished client aborts ----
def serverB = HubitatHarness.load(serverFile)
serverB.routeFile(fixturesDir, "GET", "/control/clients/find?ip0=192.168.1.50", "clients_find_missing.json")
def chB = HubitatHarness.load(clientFile, [parent: serverB.script])
chB.device.attrs["clientIds"] = "[192.168.1.50]"
chB.script.blockService("youtube")
assertNoCall(serverB, "POST", "/control/clients/update", "no update POST for vanished client")

// ---- rejected update: no bogus re-read loop, error logged ----
def serverC = HubitatHarness.load(serverFile)
serverC.routeFile(fixturesDir, "GET", "/control/clients/find?ip0=192.168.1.50", "clients_find_data.json")
serverC.routeError("POST", "/control/clients/update", 422, "Unprocessable Entity")
def chC = HubitatHarness.load(clientFile, [parent: serverC.script])
chC.device.attrs["clientIds"] = "[192.168.1.50]"
chC.script.FilteringOff()
assertEq(1, serverC.findCalls("GET", "/control/clients/find").size(), "no re-read after rejected update")
assertContains(chC.logMock.at("error"), "Client update was rejected", "rejected update reported")

// ---- empty input performs nothing ----
Map capturedD = [:]
def (chD, sD) = freshClientHarness(capturedD, "clients_find_data.json")
chD.script.blockService("")
assertNoCall(sD, "POST", "/control/clients/update", "empty service input performs no update")

reportAndExit("t09_client_mutations")
