/*
 *  AdGuard Home DNS Server Driver for Hubitat
 *  Author: JoKneeMo <https://github.com/JoKneeMo>
 *  Copyright: JoKneeMo <https://github.com/JoKneeMo>
 *  License: GPL-3.0-only
 *  Version: 1.2.0

-------------------------------------------

Change history:

1.2.0 - Restored compatibility with the current AdGuard Home Control API:
          - Protection uses read-modify-write of GET /control/dns_info plus
            POST /control/dns_config so upstreams and all unrelated DNS
            settings are preserved.
          - Filtering toggle preserves the configured refresh interval.
          - DHCP toggle preserves the interface and full v4/v6 configuration
            (POST /control/dhcp/set_config requires the complete schema).
          - Safe Search on/off preserves nested per-engine options via
            PUT /control/safesearch/settings, falling back to the legacy
            enable/disable endpoints on older servers.
          - Service blocking/unblocking reads the authoritative list with
            GET /control/blocked_services/list before every change. Blocked
            service schedules (/control/blocked_services/schedule) are never
            called and therefore preserved.
          - /control/clients/find responses are handled in both the current
            {"data": {...}} form and the pre-0.107 plain-map form.
          - Status reads "protected" (current schema) with fallbacks.
          - Refresh failures are isolated per endpoint; a failed read aborts
            the dependent mutation instead of posting destructive defaults.
          - Empty successful responses (empty service lists, empty client
            results) are no longer confused with errors.
          - Logs are sanitized: credentials, Authorization headers and
            sensitive client payloads are never logged.
          - TLS: behaviour is unchanged from 1.1.x (ignoreSSLIssues is still
            set so self-signed certificates keep working). This is a known
            limitation documented in adguardhome/README.md; verification is
            not weakened beyond the pre-existing behaviour.
1.1.1 - JoKneeMo - Set attribute types to string because bool is not valid
1.1.0 - JoKneeMo - Added ability to create a single client
                 - Fix "null" value from being added to block lists
1.0.1 - JoKneeMo - Fix unsupported media type error
1.0.0 - JoKneeMo - Initial release
*/

metadata {
    definition(name: "AdGuard Home DNS Server", namespace: "JoKneeMo", author: "JoKneeMo", importUrl: "") {
        capability "Initialize"
        capability "Refresh"
        command "initialize"
        command "refresh"
        command "createChildDevices"
        command "createSingleChild", ["String"]
        command "deleteChildDevices"
        command "ProtectionOn"
        command "ProtectionOff"
        command "FilteringOn"
        command "FilteringOff"
        command "DhcpOn"
        command "DhcpOff"
        command "ParentalControlOn"
        command "ParentalControlOff"
        command "SafeBrowsingOn"
        command "SafeBrowsingOff"
        command "SafeSearchOn"
        command "SafeSearchOff"
        command "blockService", ["String"]
        command "unblockService", ["String"]
        attribute "version", "string"
        attribute "protection", "string"
        attribute "filtering", "string"
        attribute "dhcp", "string"
        attribute "parental", "string"
        attribute "safeBrowsing", "string"
        attribute "safeSearch", "string"
        attribute "blockedServices", "string"
    }
}

preferences {
    section {
        input name: "serverIP", type: "text", title: "Server IP Address", required: true
        input name: "username", type: "text", title: "Username", required: true
        input name: "password", type: "password", title: "Password", required: true
        input name: "tlsEnable", type: "bool", title: "TLS Connection", defaultValue: false
    }
    section {
        input name: "logDebug", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "logTrace", type: "bool", title: "Enable trace logging", defaultValue: false
    }
}

def initialize() {
    refresh()
}

/*
 * Refresh reads every status endpoint independently. A failure of one
 * endpoint is logged and skipped; it does not abort the remaining reads.
 */
def refresh() {
    logDebug "DNS Server Polling..."
    if (serverIP == null || "${serverIP}" == "") {
        logError "Server IP/FQDN not entered in preferences"
        return
    }
    def failures = 0
    def collectors = [
        "status":          { getStatus() },
        "filtering":       { getFiltering() },
        "dhcp":            { getDHCP() },
        "safebrowsing":    { getSafeBrowsing() },
        "safesearch":      { getSafeSearch() },
        "parental":        { getParental() },
        "blocked_services":{ getBlockedServices() != null }
    ]
    collectors.each { name, collector ->
        try {
            if (collector.call() == false) {
                // The collector already logged the sanitized HTTP failure;
                // report which endpoint it was so operators can act on it.
                failures++
                logError "Refresh of '${name}' failed (see the HTTP request error above)"
            }
        } catch (Exception e) {
            failures++
            logError "Refresh of '${name}' failed: ${sanitizeErrorMessage(e)}"
        }
    }
    if (failures > 0) {
        logError "Refresh completed with ${failures} failed endpoint read(s)"
    }
}

// State Collectors
/*
 * Each collector returns true when its authoritative read succeeded (even if
 * the payload lacked expected fields) and false when the read failed, so
 * refresh() can report the failed endpoint by name.
 */
def getStatus() {
    logDebug "Getting Status...."
    def respValues = doHttpGet("/status")
    if (!(respValues instanceof Map)) {
        // Older releases exposed "version" only on the newer API status
        // endpoint; try it before giving up (both are documented reads).
        return false
    }
    def version = respValues.version
    if (version == null) {
        def apiStatus = doHttpGet("/api/v1/status")
        if (apiStatus instanceof Map) {
            version = apiStatus.version
        }
    }
    if (version != null) {
        sendEvent(name: "version", value: version.toString())
    }
    def protection = null
    if (respValues.containsKey("protected")) {
        protection = respValues.protected
    }
    else if (respValues.containsKey("protection_enabled")) {
        protection = respValues.protection_enabled
    }
    else {
        def dnsInfo = doHttpGet("/dns_info")
        if (dnsInfo instanceof Map && dnsInfo.containsKey("protection_enabled")) {
            protection = dnsInfo.protection_enabled
        }
    }
    if (protection != null) {
        sendEvent(name: "protection", value: protection.toString())
    }
    return true
}

def getFiltering() {
    logDebug "Getting Filtering...."
    def respValues = doHttpGet("/filtering/status")
    if (respValues == null) {
        return false
    }
    if (respValues instanceof Map && respValues.enabled != null) {
        sendEvent(name: "filtering", value: respValues.enabled.toString())
    }
    return true
}

def getDHCP() {
    logDebug "Getting DHCP...."
    def respValues = doHttpGet("/dhcp/status")
    if (respValues == null) {
        return false
    }
    if (respValues instanceof Map && respValues.enabled != null) {
        sendEvent(name: "dhcp", value: respValues.enabled.toString())
    }
    return true
}

def getSafeBrowsing() {
    logDebug "Getting SafeBrowsing...."
    def respValues = doHttpGet("/safebrowsing/status")
    if (respValues == null) {
        return false
    }
    if (respValues instanceof Map && respValues.enabled != null) {
        sendEvent(name: "safeBrowsing", value: respValues.enabled.toString())
    }
    return true
}

def getSafeSearch() {
    logDebug "Getting SafeSearch...."
    def respValues = doHttpGet("/safesearch/status")
    if (respValues == null) {
        return false
    }
    if (respValues instanceof Map && respValues.enabled != null) {
        sendEvent(name: "safeSearch", value: respValues.enabled.toString())
    }
    return true
}

def getParental() {
    logDebug "Getting Parental...."
    def respValues = doHttpGet("/parental/status")
    if (respValues == null) {
        return false
    }
    if (respValues instanceof Map && respValues.enabled != null) {
        sendEvent(name: "parental", value: respValues.enabled.toString())
    }
    return true
}

/*
 * GET /control/blocked_services/list returns a JSON array of service names
 * (possibly empty). An empty list is a successful read, not an error.
 * Returns the list, or null when the read failed.
 */
def getBlockedServices() {
    logDebug "Getting Blocked Services...."
    def respValues = doHttpGet("/blocked_services/list")
    if (respValues == null) {
        return null
    }
    def services = respValues
    if (respValues instanceof Map && respValues.containsKey("ids")) {
        services = respValues.ids
    }
    if (!(services instanceof List)) {
        logError "Unexpected response from /blocked_services/list"
        return null
    }
    services = normalizeStringList(services)
    sendEvent(name: "blockedServices", value: services.toString())
    return services
}

// State Controls
/*
 * POST /control/dns_config expects the COMPLETE dnsconfig schema; a partial
 * payload would reset upstreams and other DNS settings (or be rejected).
 * Read GET /control/dns_info first, change only protection_enabled and
 * write the full document back. Abort if the authoritative read fails.
 */
def setProtection(boolean enabled) {
    logDebug "${enabled ? 'Enabling' : 'Disabling'} Protection...."
    def dnsInfo = doHttpGet("/dns_info")
    if (!(dnsInfo instanceof Map)) {
        logError "Aborting protection change: could not read /dns_info"
        return
    }
    dnsInfo.protection_enabled = enabled
    if (doHttpPostJson("/dns_config", dnsInfo) == null) {
        return
    }
    getStatus()
}

def ProtectionOn() {
    setProtection(true)
}

def ProtectionOff() {
    setProtection(false)
}

/*
 * POST /control/filtering/config takes FilteringConfig {enabled, interval}.
 * Preserve the currently configured refresh interval from
 * GET /control/filtering/status instead of forcing a default.
 */
def setFiltering(boolean enabled) {
    logDebug "${enabled ? 'Enabling' : 'Disabling'} Filtering...."
    def current = doHttpGet("/filtering/status")
    if (!(current instanceof Map)) {
        logError "Aborting filtering change: could not read /filtering/status"
        return
    }
    def payload = [enabled: enabled]
    if (current.interval != null) {
        payload.interval = current.interval
    }
    if (doHttpPostJson("/filtering/config", payload) == null) {
        return
    }
    getFiltering()
}

def FilteringOn() {
    setFiltering(true)
}

def FilteringOff() {
    setFiltering(false)
}

/*
 * POST /control/dhcp/set_config requires the full DHCPConfig schema
 * (enabled, interface_name, v4, v6). Read GET /dhcp/status first and write
 * the complete document back with only "enabled" changed.
 */
def setDhcp(boolean enabled) {
    logDebug "${enabled ? 'Enabling' : 'Disabling'} DHCP...."
    def current = doHttpGet("/dhcp/status")
    if (!(current instanceof Map)) {
        logError "Aborting DHCP change: could not read /dhcp/status"
        return
    }
    def payload = [enabled: enabled]
    ["interface_name", "v4", "v6"].each { key ->
        if (current.containsKey(key) && current[key] != null) {
            payload[key] = current[key]
        }
    }
    if (doHttpPostJson("/dhcp/set_config", payload) == null) {
        return
    }
    getDHCP()
}

def DhcpOn() {
    setDhcp(true)
}

def DhcpOff() {
    setDhcp(false)
}

/*
 * POST /control/parental/enable and /parental/disable take no request body
 * (the parental sensitivity is a server configuration setting, not part of
 * these endpoints).
 */
def ParentalControlOn() {
    logDebug "Enabling Parental Control...."
    if (doHttpPostJson("/parental/enable", [:]) == null) {
        return
    }
    getParental()
}

def ParentalControlOff() {
    logDebug "Disabling Parental Control...."
    if (doHttpPostJson("/parental/disable", [:]) == null) {
        return
    }
    getParental()
}

def SafeBrowsingOn() {
    logDebug "Enabling SafeBrowsing...."
    if (doHttpPostJson("/safebrowsing/enable", [:]) == null) {
        return
    }
    getSafeBrowsing()
}

def SafeBrowsingOff() {
    logDebug "Disabling SafeBrowsing...."
    if (doHttpPostJson("/safebrowsing/disable", [:]) == null) {
        return
    }
    getSafeBrowsing()
}

/*
 * Current AdGuard Home versions expose per-engine Safe Search options.
 * GET /control/safesearch/status returns {enabled, bing, duckduckgo, google,
 * pixabay, yandex, ...}. When nested options are present, write them back
 * unchanged via PUT /control/safesearch/settings with only "enabled"
 * changed. On older servers (plain {enabled} response, no settings endpoint)
 * fall back to the legacy POST /safesearch/enable and /safesearch/disable.
 */
def setSafeSearch(boolean enabled) {
    logDebug "${enabled ? 'Enabling' : 'Disabling'} SafeSearch...."
    def current = doHttpGet("/safesearch/status")
    if (!(current instanceof Map)) {
        logError "Aborting SafeSearch change: could not read /safesearch/status"
        return
    }
    def nestedKeys = ["bing", "duckduckgo", "google", "pixabay", "yandex"]
    def hasNested = nestedKeys.any { current.containsKey(it) }
    if (hasNested) {
        def payload = [enabled: enabled]
        nestedKeys.each { key ->
            if (current[key] != null) {
                payload[key] = current[key]
            }
        }
        if (doHttpPutJson("/safesearch/settings", payload) != null) {
            getSafeSearch()
            return
        }
        logWarn "Could not update /safesearch/settings; trying legacy endpoint"
    }
    def endpoint = enabled ? "/safesearch/enable" : "/safesearch/disable"
    if (doHttpPostJson(endpoint, [:]) == null) {
        return
    }
    getSafeSearch()
}

def SafeSearchOn() {
    setSafeSearch(true)
}

def SafeSearchOff() {
    setSafeSearch(false)
}

/*
 * Service blocking is read-modify-write against the server's authoritative
 * list (never a stale cached attribute). The blocked services SCHEDULE lives
 * behind /control/blocked_services/schedule and is never called here, so it
 * survives list changes.
 */
def blockService(services_string) {
    List<String> services_list = normalizeStringList(services_string)
    if (services_list.size() < 1) {
        logError "No services specified to block"
        return
    }
    logDebug "Blocking ${services_list.size()} Service(s): ${services_list}..."
    def currentBlocks = getBlockedServices()
    if (currentBlocks == null) {
        logError "Aborting blockService: could not read the authoritative blocked services list"
        return
    }
    List<String> postBlockList = []
    currentBlocks.each { if (!postBlockList.contains(it)) { postBlockList.add(it) } }
    services_list.each { if (!postBlockList.contains(it)) { postBlockList.add(it) } }
    logDebug "Setting ${postBlockList.size()} Blocked Services: ${postBlockList}"
    if (doHttpPostJson("/blocked_services/set", postBlockList) == null) {
        return
    }
    getBlockedServices()
}

def unblockService(services_string) {
    List<String> services_list = normalizeStringList(services_string)
    if (services_list.size() < 1) {
        logError "No services specified to unblock"
        return
    }
    logDebug "Unblocking ${services_list.size()} Service(s): ${services_list}..."
    def currentBlocks = getBlockedServices()
    if (currentBlocks == null) {
        logError "Aborting unblockService: could not read the authoritative blocked services list"
        return
    }
    List<String> postBlockList = []
    currentBlocks.each {
        if (!services_list.contains(it) && !postBlockList.contains(it)) {
            postBlockList.add(it)
        }
    }
    logDebug "Setting ${postBlockList.size()} Blocked Services: ${postBlockList}"
    if (doHttpPostJson("/blocked_services/set", postBlockList) == null) {
        return
    }
    getBlockedServices()
}

// Client Controls
def getClients() {
    logDebug "Getting Clients...."
    def respValues = doHttpGet("/clients")
    if (!(respValues instanceof Map)) {
        logError "Could not retrieve the clients list from the server"
        return null
    }
    return respValues
}

def createChildDevices() {
    def respValues = getClients()
    def clients = (respValues != null) ? respValues.clients : null
    if (!(clients instanceof List)) {
        if (respValues != null) {
            logDebug "Server returned no clients"
        }
        return
    }
    clients.each { client ->
        def ids = (client?.ids instanceof List) ? normalizeStringList(client.ids) : []
        if (!client?.name || ids.size() < 1) {
            logTrace "Skipping client without name or ids"
            return
        }
        if (findChildDevice(ids[0], "client")) {
            logTrace "Client Already Exists: ${client.name} | ${ids[0]}"
        } else {
            createChildDevice(client.name, ids, "client")
        }
    }
}

/*
 * GET /control/clients/find?ip0=<id> returns {"data": {"<key>": [client...]}}
 * on current servers and a plain {"<key>": [client...]} map on pre-0.107
 * servers. Missing clients produce an empty result and are reported.
 */
def createSingleChild(id) {
    logDebug "Adding Single Client [${id}]...."
    def respValues = doHttpGet("/clients/find?ip0=${id}")
    if (respValues == null) {
        logError "Aborting createSingleChild: /clients/find request failed"
        return
    }
    def entries = extractClientsFind(respValues, id)
    if (entries.size() < 1) {
        logError "Client with ID ${id} does not exist on server."
        return
    }
    def clientQuery = entries[0]
    def ids = (clientQuery?.ids instanceof List) ? normalizeStringList(clientQuery.ids) : []
    if (!clientQuery?.name || ids.size() < 1) {
        logError "Client with ID ${id} has no name or ids on server."
        return
    }
    if (findChildDevice(ids[0], "client")) {
        logError "Client Already Exists: ${clientQuery.name} | ${ids[0]}"
    } else {
        createChildDevice(clientQuery.name, ids, "client")
    }
}

/*
 * Normalises both /clients/find response shapes to a list of client maps.
 * Shared with the child client driver via parent.extractClientsFind().
 */
def extractClientsFind(respValues, id) {
    def map = respValues
    if (respValues instanceof Map && respValues.data instanceof Map) {
        map = respValues.data
    }
    if (!(map instanceof Map)) {
        return []
    }
    def entries = map["${id}"]
    if (entries == null && map.size() == 1) {
        // Some deployments key the result by the resolved client address;
        // when exactly one entry is returned it is the queried client.
        def only = map.values().iterator().next()
        if (only instanceof List) {
            entries = only
        }
    }
    return (entries instanceof List) ? entries : []
}

def createChildDevice(name, ids, deviceType) {
    def child
        try {
            switch(deviceType.toString()) {
                case "client":
                    logDebug "Creating New Client: ${name} | ${ids[0]}"
                    def newChild = addChildDevice("AdGuard Home DNS Client", childDNI(ids[0], deviceType), [name: childName(name, deviceType), label: childName(name, deviceType), isComponent: false])
                    newChild.parentEvent("clientIds", ids)
                    pauseExecution(1000)
                    newChild.refresh()
                    break
                default:
                    logDebug "deviceType is not specified or is unsupported"
            }
        }
        catch (Exception e) {
            logError "Child Device Creation Failed: ${e.message}"
        }
}

def deleteChildDevices() {
    logInfo "Deleting all child devices...."
    for(child in getChildDevices()) {
        deleteChildDevice(child.deviceNetworkId)
    }
}

def childName(name, deviceType) {
    return "DNS ${deviceType.toString().toUpperCase()} | ${name.toString()}"
}

def findChildDevice(id, deviceType) {
    return getChildDevice(childDNI(id, deviceType))
}

def childDNI(id, deviceType) {
    return "AGH_${deviceType.toString().toUpperCase()}|${id.toString()}"
}

// General Functions
/*
 * doHttpGet returns the parsed JSON body on success (which may be an empty
 * list/map for a successful but empty response) or null when the request
 * failed. Callers must treat null as "read failed" and abort mutations;
 * empty collections are valid data.
 */
def doHttpGet(endpoint, bodyData = null) {
    logDebug "Getting ${endpoint}...."
    def httpParams = [
        uri: "${getBaseURI()}${endpoint}",
        ignoreSSLIssues: true,
        headers: getDefaultHeaders()
    ]
    try {
        logTrace "HTTP GET ${sanitizeUri(httpParams.uri)}"
        def respData = null
        httpGet(httpParams) { resp ->
            respData = resp.getData()
            return respData
        }
        return respData
    } catch(Exception e) {
        logError "HTTP GET ${sanitizeUri(httpParams.uri)} failed: ${sanitizeErrorMessage(e)}"
        return null
    }
}

/*
 * Returns the parsed response body on success, an empty map when the server
 * answered successfully with an empty body, or null when the request failed.
 */
def doHttpPostJson(endpoint, bodyData) {
    logDebug "Posting to ${endpoint}...."
    def httpParams = [
        uri: "${getBaseURI()}${endpoint}",
        ignoreSSLIssues: true,
        headers: getDefaultHeaders(),
        body: bodyData
    ]
    try {
        logTrace "HTTP POST ${sanitizeUri(httpParams.uri)}"
        def respData = null
        httpPostJson(httpParams) { resp ->
            respData = resp.getData()
            return respData
        }
        return (respData != null) ? respData : [:]
    } catch(Exception e) {
        logError "HTTP POST ${sanitizeUri(httpParams.uri)} failed: ${sanitizeErrorMessage(e)}"
        return null
    }
}

def doHttpPutJson(endpoint, bodyData) {
    logDebug "Putting to ${endpoint}...."
    def httpParams = [
        uri: "${getBaseURI()}${endpoint}",
        ignoreSSLIssues: true,
        headers: getDefaultHeaders(),
        body: bodyData
    ]
    try {
        logTrace "HTTP PUT ${sanitizeUri(httpParams.uri)}"
        def respData = null
        httpPutJson(httpParams) { resp ->
            respData = resp.getData()
            return respData
        }
        return (respData != null) ? respData : [:]
    } catch(Exception e) {
        logError "HTTP PUT ${sanitizeUri(httpParams.uri)} failed: ${sanitizeErrorMessage(e)}"
        return null
    }
}

def getBaseURI() {
    def baseProtocol = "http://"
    if (tlsEnable) {
        baseProtocol = "https://"
    }
    def baseURI = "${baseProtocol}${serverIP}/control"
    logTrace "Base URI: ${baseURI.toString()}"
    return baseURI.toString()
}

/*
 * Current AdGuard Home versions (0.107.x and later) keep HTTP Basic
 * authentication supported and have deprecated the older session-cookie
 * flow for API clients, so Basic auth remains the documented method.
 * Headers are never logged because they carry credentials.
 */
def getDefaultHeaders() {
    def headers = [:]
    headers.put("Accept-Encoding", "gzip, deflate, br")
    headers.put("Connection", "keep-alive")
    headers.put("Accept", "application/json, text/plain, */*")
    headers.put("Content-type", "application/json")
    headers.put("User-Agent", "Hubitat")
    def authString = "${username}:${password}"
    headers.put("Authorization", "Basic ${authString.bytes.encodeBase64().toString()}")
    return headers
}

/*
 * Normalises null values, raw lists, "[a, b]" attribute strings and
 * comma-separated strings into a clean list of non-empty strings with
 * "null" placeholders removed.
 */
def normalizeStringList(value) {
    def list = []
    if (value == null) {
        return list
    }
    if (value instanceof List) {
        list = value.collect { it == null ? null : it.toString() }
    }
    else {
        list = value.toString().replaceAll(/^\[|\]$/, "").split("\\s*,\\s*").collect { it.trim() }
    }
    return list.findAll { it != null && it != "" && it != "null" }
}

// Kept for backwards compatibility with older helper calls.
def convertMapString(map) {
    return map.toString().replaceAll("\\[|\\]", "").split("\\s*,\\s*")
}

def sanitizeUri(uri) {
    return uri.toString().replaceAll("://[^/@]*@", "://")
}

/*
 * Hubitat HTTP exceptions can embed the full request map, including the
 * Authorization header and the body. Strip credentials and truncate before
 * anything reaches the log.
 */
def sanitizeErrorMessage(error) {
    def msg = (error instanceof Exception) ? error.message : error.toString()
    if (msg == null) {
        return "unknown error"
    }
    msg = msg.replaceAll("(?i)(\"?authorization\"?\\s*[:=]\\s*\"?)(basic\\s+)[A-Za-z0-9+/=]+", '$1$2******')
    msg = msg.replaceAll("(?i)(\"?(password|passwd)\"?\\s*[:=]\\s*\"?)[^,\"}\\s]+", '$1******')
    msg = msg.replaceAll("(?i)([?&](ip0|name)=)[^&\"\\s]+", '$1******')
    if (msg.length() > 500) {
        msg = msg.substring(0, 500) + "... [truncated]"
    }
    return msg
}

//logging help methods
private logError(msg) {
    log.error msg
}

private logWarn(msg) {
    log.warn msg
}

private logInfo(msg) {
    if (descriptionTextEnable) log.info msg
}

private logDebug(msg) {
    if (logDebug) log.debug msg
}

private logTrace(msg) {
    if (logTrace) log.trace msg
}
