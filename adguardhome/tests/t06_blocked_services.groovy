/*
 * t06: blocked-service management is a read-modify-write against the
 * server's authoritative GET /control/blocked_services/list; the schedule
 * endpoint is never touched; an empty list is valid data; a failed read
 * aborts the mutation.
 */
import groovy.json.JsonSlurper

int failures = 0
int passes = 0

def fixturesDir = new File(System.getProperty("fixturesDir"))
def serverFile = new File(System.getProperty("serverDriver"))

// ---- blockService merges into the authoritative list ----
Map captured = [:]
def h = HubitatHarness.load(serverFile)
h.routeFile(fixturesDir, "GET", "/control/blocked_services/list", "blocked_services_list.json")
h.route("POST", "/control/blocked_services/set", { params -> captured.body = params.body; return [:] })
h.script.blockService("facebook, youtube")

def posted = captured.body
assertTrue(posted instanceof List, "blocked_services/set receives the full replacement list")
assertEq(["youtube", "tiktok", "facebook"], posted, "existing services preserved, duplicates not repeated, new service appended")
assertEq("[youtube, tiktok]", eventValue(h, "blockedServices"), "attribute re-read from server after change")

// ---- unblockService removes only the requested services ----
Map captured2 = [:]
def h2 = HubitatHarness.load(serverFile)
h2.routeFile(fixturesDir, "GET", "/control/blocked_services/list", "blocked_services_list.json")
h2.route("POST", "/control/blocked_services/set", { params -> captured2.body = params.body; return [:] })
h2.script.unblockService("youtube")
assertEq(["tiktok"], captured2.body, "only the requested service removed, others preserved")

// ---- unblocking everything posts an explicit empty list ----
Map captured3 = [:]
def h3 = HubitatHarness.load(serverFile)
h3.routeFile(fixturesDir, "GET", "/control/blocked_services/list", "blocked_services_list.json")
h3.route("POST", "/control/blocked_services/set", { params -> captured3.body = params.body; return [:] })
h3.script.unblockService("youtube, tiktok")
assertEq([], captured3.body, "unblocking all services posts an explicit empty list")

// ---- input parsing: bracketed attribute strings, nulls, empty input ----
Map captured4 = [:]
def h4 = HubitatHarness.load(serverFile)
h4.routeFile(fixturesDir, "GET", "/control/blocked_services/list", "blocked_services_empty.json")
h4.route("POST", "/control/blocked_services/set", { params -> captured4.body = params.body; return [:] })
h4.script.blockService("[youtube, null, ]")
assertEq(["youtube"], captured4.body, "bracketed list string parsed, null and blank entries dropped")

def h5 = HubitatHarness.load(serverFile)
h5.script.blockService("")
assertEq(0, h5.calls.size(), "empty service input performs no HTTP calls")
assertContains(h5.logMock.at("error"), "No services specified to block", "empty input reported")

def h6 = HubitatHarness.load(serverFile)
h6.script.unblockService(null)
assertEq(0, h6.calls.size(), "null service input performs no HTTP calls")

// ---- authoritative read failure aborts ----
def h7 = HubitatHarness.load(serverFile)
h7.routeError("GET", "/control/blocked_services/list", 500, "boom")
h7.script.blockService("youtube")
assertNoCall(h7, "POST", "/control/blocked_services/set", "no set POST when authoritative list read fails")
assertContains(h7.logMock.at("error"), "Aborting blockService", "abort reported")

def h8 = HubitatHarness.load(serverFile)
h8.routeError("GET", "/control/blocked_services/list", 500, "boom")
h8.script.unblockService("youtube")
assertNoCall(h8, "POST", "/control/blocked_services/set", "no set POST on unblock when read fails")

// ---- the blocked services schedule endpoint is never called ----
assertEq(0, h.findCalls("GET", "/control/blocked_services/schedule").size(), "schedule never read")
assertEq(0, h.findCalls("POST", "/control/blocked_services/schedule").size(), "schedule never written")
assertEq(0, h2.findCalls("POST", "/control/blocked_services/schedule").size(), "schedule never written on unblock")

reportAndExit("t06_blocked_services")
