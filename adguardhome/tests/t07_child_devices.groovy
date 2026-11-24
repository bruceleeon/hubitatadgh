/*
 * t07: child-device management. createSingleChild handles the current
 * {"data": {...}} and the pre-0.107 plain-map /clients/find shapes,
 * reports missing clients, createChildDevices skips malformed entries,
 * deleteChildDevices removes every child.
 */
import groovy.json.JsonSlurper

int failures = 0
int passes = 0

def fixturesDir = new File(System.getProperty("fixturesDir"))
def serverFile = new File(System.getProperty("serverDriver"))

// ---- createSingleChild with current {"data": ...} shape ----
def h = HubitatHarness.load(serverFile)
h.routeFile(fixturesDir, "GET", "/control/clients/find?ip0=192.168.1.50", "clients_find_data.json")
h.script.createSingleChild("192.168.1.50")
def addCalls = h.calls.findAll { it.method == "ADD_CHILD" }
assertEq(1, addCalls.size(), "one child device created from current clients/find shape")
assertEq("AdGuard Home DNS Client", addCalls ? addCalls[0].path : null, "correct driver used")
def childDni = addCalls ? addCalls[0].body.id : null
assertEq("AGH_CLIENT|192.168.1.50", childDni, "device network id format preserved")
assertEq("DNS CLIENT | livingroom-tv", addCalls ? addCalls[0].body.meta.label : null, "child label format preserved")

// ---- same for legacy plain-map shape ----
def h2 = HubitatHarness.load(serverFile)
h2.routeFile(fixturesDir, "GET", "/control/clients/find?ip0=192.168.1.50", "clients_find_legacy.json")
h2.script.createSingleChild("192.168.1.50")
assertEq(1, h2.calls.findAll { it.method == "ADD_CHILD" }.size(), "one child created from legacy clients/find shape")

// ---- existing child is not duplicated ----
def h3 = HubitatHarness.load(serverFile)
h3.children["AGH_CLIENT|192.168.1.50"] = new MockDevice(deviceNetworkId: "AGH_CLIENT|192.168.1.50")
h3.routeFile(fixturesDir, "GET", "/control/clients/find?ip0=192.168.1.50", "clients_find_data.json")
h3.script.createSingleChild("192.168.1.50")
assertEq(0, h3.calls.findAll { it.method == "ADD_CHILD" }.size(), "existing child not duplicated")

// ---- missing client reported, nothing created ----
def h4 = HubitatHarness.load(serverFile)
h4.routeFile(fixturesDir, "GET", "/control/clients/find?ip0=10.9.9.9", "clients_find_missing.json")
h4.script.createSingleChild("10.9.9.9")
assertEq(0, h4.calls.findAll { it.method == "ADD_CHILD" }.size(), "missing client creates no device")
assertContains(h4.logMock.at("error"), "does not exist", "missing client reported")

// ---- failed lookup aborts ----
def h5 = HubitatHarness.load(serverFile)
h5.routeError("GET", "/control/clients/find?ip0=192.168.1.50", 500, "boom")
h5.script.createSingleChild("192.168.1.50")
assertEq(0, h5.calls.findAll { it.method == "ADD_CHILD" }.size(), "failed lookup creates no device")
assertContains(h5.logMock.at("error"), "Aborting createSingleChild", "lookup failure reported")

// ---- createChildDevices over the full list ----
def h6 = HubitatHarness.load(serverFile)
h6.routeFile(fixturesDir, "GET", "/control/clients", "clients.json")
h6.script.createChildDevices()
def created = h6.calls.findAll { it.method == "ADD_CHILD" }
assertEq(1, created.size(), "only well-formed clients create children")
assertEq("AGH_CLIENT|192.168.1.50", created ? created[0].body.id : null, "named client with ids created")

// ---- createChildDevices tolerates empty and missing client lists ----
def h7 = HubitatHarness.load(serverFile)
h7.route("GET", "/control/clients", { params -> [clients: []] })
h7.script.createChildDevices()
assertEq(0, h7.calls.findAll { it.method == "ADD_CHILD" }.size(), "empty client list creates nothing")
assertEq(0, h7.logMock.at("error").size(), "empty client list is not an error")

def h8 = HubitatHarness.load(serverFile)
h8.routeError("GET", "/control/clients", 500, "boom")
h8.script.createChildDevices()
assertEq(0, h8.calls.findAll { it.method == "ADD_CHILD" }.size(), "failed client list read creates nothing")

// ---- deleteChildDevices removes every child ----
def h9 = HubitatHarness.load(serverFile)
h9.children["AGH_CLIENT|a"] = new MockDevice(deviceNetworkId: "AGH_CLIENT|a")
h9.children["AGH_CLIENT|b"] = new MockDevice(deviceNetworkId: "AGH_CLIENT|b")
h9.script.deleteChildDevices()
assertEq(0, h9.children.size(), "all children deleted")

reportAndExit("t07_child_devices")
