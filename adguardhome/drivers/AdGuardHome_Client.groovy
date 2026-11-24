/*
 *  AdGuard Home DNS Client Driver for Hubitat
 *  Author: JoKneeMo <https://github.com/JoKneeMo>
 *  Copyright: JoKneeMo <https://github.com/JoKneeMo>
 *  License: GPL-3.0-only
 *  Version: 1.2.0

-------------------------------------------

Change history:

1.2.0 - Restored compatibility with the current AdGuard Home Control API:
          - /control/clients/find responses are handled in both the current
            {"data": {...}} form and the pre-0.107 plain-map form.
          - All client mutations now read the authoritative client settings
            from the server before posting /control/clients/update, so
            unrelated settings (ids, tags, upnames, parents, blocked
            services, per-client flags) are preserved instead of being
            rebuilt from stale cached attributes.
          - Mutations abort when the authoritative read fails or the client
            no longer exists; missing clients and null values are handled.
          - Refresh failures are reported without corrupting attributes;
            empty successful responses are not confused with errors.
          - Sensitive client payloads and credentials are never logged.
1.1.1 - JoKneeMo - Set attribute types to string because bool is not valid
1.1.0 - JoKneeMo - Fix "null" value from being added to block lists
1.0.0 - JoKneeMo - Initial release
*/

metadata {
    definition(name: "AdGuard Home DNS Client", namespace: "JoKneeMo", author: "JoKneeMo", importUrl: "") {
        capability "Refresh"
        command "refresh"
        command "GlobalSettingsOn"
        command "GlobalSettingsOff"
        command "FilteringOn"
        command "FilteringOff"
        command "ParentalControlOn"
        command "ParentalControlOff"
        command "SafeBrowsingOn"
        command "SafeBrowsingOff"
        command "SafeSearchOn"
        command "SafeSearchOff"
        command "GlobalBlockListOn"
        command "GlobalBlockListOff"
        command "blockService", ["String"]
        command "unblockService", ["String"]
        attribute "name", "string"
        attribute "filtering", "string"
        attribute "parental", "string"
        attribute "safeBrowsing", "string"
        attribute "safeSearch", "string"
        attribute "blockedServices", "string"
        attribute "use_global_blocked_services", "string"
        attribute "use_global_settings", "string"
        attribute "clientIds", "string"
        attribute "clientTags", "string"
    }
}

preferences {
    section {
        input name: "logDebug", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "logTrace", type: "bool", title: "Enable trace logging", defaultValue: false
    }
}

def refresh() {
    logDebug "DNS Client Polling..."
    try {
        getStatus()
    } catch (Exception e) {
        logError "Client refresh failed: ${parent.sanitizeErrorMessage(e)}"
    }
}

// State Collector
/*
 * Reads the authoritative client record from the parent server. Returns the
 * client map, or null when the read failed or the client no longer exists.
 */
def readLiveClient() {
    def primaryId = resolveClientId()
    if (primaryId == null) {
        logError "No client ID known for this device; cannot read client status"
        return null
    }
    def respValues = parent.doHttpGet("/clients/find?ip0=${primaryId}")
    if (respValues == null) {
        logError "Client lookup request failed for [${primaryId}]"
        return null
    }
    def entries = parent.extractClientsFind(respValues, primaryId)
    if (entries.size() < 1) {
        logError "Client [${primaryId}] no longer exists on the AdGuard Home server"
        return null
    }
    def client = entries[0]
    if (client == null || !(client instanceof Map)) {
        logError "Client lookup returned an unexpected response for [${primaryId}]"
        return null
    }
    return client
}

/*
 * The primary client ID: prefer the known good state id, fall back to the
 * first value of the clientIds attribute (which may be a list or the string
 * form of a list stored by Hubitat).
 */
def resolveClientId() {
    def fromState = state?.primaryId
    if (fromState != null && "${fromState}" != "" && "${fromState}" != "null") {
        return fromState.toString()
    }
    def list = normalizeStringList(device.currentValue("clientIds"))
    if (list.size() > 0) {
        state.primaryId = list[0]
        return list[0]
    }
    return null
}

def getStatus() {
    logDebug "Getting Client Status...."
    def respValues = readLiveClient()
    if (respValues == null) {
        return
    }
    state.clientName = respValues.name
    sendEvent(name: "name", value: (respValues.name != null) ? respValues.name.toString() : "")
    sendEvent(name: "use_global_settings", value: (respValues.use_global_settings != null) ? respValues.use_global_settings.toString() : "true")
    sendEvent(name: "use_global_blocked_services", value: (respValues.use_global_blocked_services != null) ? respValues.use_global_blocked_services.toString() : "true")
    sendEvent(name: "filtering", value: (respValues.filtering_enabled != null) ? respValues.filtering_enabled.toString() : "true")
    sendEvent(name: "parental", value: (respValues.parental_enabled != null) ? respValues.parental_enabled.toString() : "false")
    sendEvent(name: "safeBrowsing", value: (respValues.safebrowsing_enabled != null) ? respValues.safebrowsing_enabled.toString() : "false")
    sendEvent(name: "safeSearch", value: (respValues.safesearch_enabled != null) ? respValues.safesearch_enabled.toString() : "false")
    def services = normalizeStringList(respValues.blocked_services)
    sendEvent(name: "blockedServices", value: services.toString())
    def ids = normalizeStringList(respValues.ids)
    if (ids.size() > 0) {
        state.primaryId = ids[0]
    }
    sendEvent(name: "clientIds", value: ids.toString())
    sendEvent(name: "clientTags", value: normalizeStringList(respValues.tags).toString())
}

// State Controls
/*
 * Every mutation reads the authoritative client from the server, changes
 * only the requested field and posts the documented
 * {name: <currentName>, data: <full client>} schema to
 * POST /control/clients/update. Failed reads and vanished clients abort
 * the mutation; nothing is rebuilt from cached attributes.
 */
def mutateClient(Closure mutator) {
    def client = readLiveClient()
    if (client == null) {
        logError "Aborting client update: authoritative read failed"
        return
    }
    mutator(client)
    if (!updateClient(client)) {
        return
    }
    getStatus()
}

def setUseGlobalSettings(boolean enabled) {
    logDebug "${enabled ? 'Enabling' : 'Disabling'} Global Settings for client...."
    mutateClient { it.use_global_settings = enabled }
}

def GlobalSettingsOn() {
    setUseGlobalSettings(true)
}

def GlobalSettingsOff() {
    setUseGlobalSettings(false)
}

def setFiltering(boolean enabled) {
    logDebug "${enabled ? 'Enabling' : 'Disabling'} Filtering for client...."
    mutateClient { it.filtering_enabled = enabled }
}

def FilteringOn() {
    setFiltering(true)
}

def FilteringOff() {
    setFiltering(false)
}

def setParentalControl(boolean enabled) {
    logDebug "${enabled ? 'Enabling' : 'Disabling'} Parental Control for client...."
    mutateClient { it.parental_enabled = enabled }
}

def ParentalControlOn() {
    setParentalControl(true)
}

def ParentalControlOff() {
    setParentalControl(false)
}

def setSafeBrowsing(boolean enabled) {
    logDebug "${enabled ? 'Enabling' : 'Disabling'} SafeBrowsing for client...."
    mutateClient { it.safebrowsing_enabled = enabled }
}

def SafeBrowsingOn() {
    setSafeBrowsing(true)
}

def SafeBrowsingOff() {
    setSafeBrowsing(false)
}

def setSafeSearch(boolean enabled) {
    logDebug "${enabled ? 'Enabling' : 'Disabling'} SafeSearch for client...."
    mutateClient { it.safesearch_enabled = enabled }
}

def SafeSearchOn() {
    setSafeSearch(true)
}

def SafeSearchOff() {
    setSafeSearch(false)
}

def setUseGlobalBlockedServices(boolean enabled) {
    logDebug "${enabled ? 'Enabling' : 'Disabling'} Global Block List for client...."
    mutateClient { it.use_global_blocked_services = enabled }
}

def GlobalBlockListOn() {
    setUseGlobalBlockedServices(true)
}

def GlobalBlockListOff() {
    setUseGlobalBlockedServices(false)
}

def blockService(services_string) {
    List<String> services_list = normalizeStringList(services_string)
    if (services_list.size() < 1) {
        logError "No services specified to block"
        return
    }
    logDebug "Blocking ${services_list.size()} Service(s): ${services_list}..."
    mutateClient { client ->
        List<String> currentBlocks = normalizeStringList(client.blocked_services)
        List<String> postBlockList = []
        currentBlocks.each { if (!postBlockList.contains(it)) { postBlockList.add(it) } }
        services_list.each { if (!postBlockList.contains(it)) { postBlockList.add(it) } }
        logDebug "Setting ${postBlockList.size()} Blocked Services: ${postBlockList}"
        client.blocked_services = postBlockList
    }
}

def unblockService(services_string) {
    List<String> services_list = normalizeStringList(services_string)
    if (services_list.size() < 1) {
        logError "No services specified to unblock"
        return
    }
    logDebug "Unblocking ${services_list.size()} Service(s): ${services_list}..."
    mutateClient { client ->
        List<String> currentBlocks = normalizeStringList(client.blocked_services)
        List<String> postBlockList = []
        currentBlocks.each {
            if (!services_list.contains(it) && !postBlockList.contains(it)) {
                postBlockList.add(it)
            }
        }
        logDebug "Setting ${postBlockList.size()} Blocked Services: ${postBlockList}"
        client.blocked_services = postBlockList
    }
}


// General Functions
def parentEvent(attrib_name, attrib_value) {
    logTrace "Updating Attribute ${attrib_name}: ${attrib_value}"
    def values = normalizeStringList(attrib_value)
    if (values.size() > 0) {
        state.primaryId = values[0]
    }
    sendEvent(name: attrib_name, value: values.toString())
}

/*
 * Posts the authoritative client document to the server using the
 * documented update schema: top-level "name" identifies the existing
 * client, "data" carries the complete (modified) client settings.
 * Returns true on success, false on failure.
 */
def updateClient(Map client) {
    logTrace "Sending client update...."
    def name = (client.name != null) ? client.name.toString() : (state.clientName != null ? state.clientName.toString() : "")
    def payload = [name: name, data: client]
    def resp = parent.doHttpPostJson("/clients/update", payload)
    if (resp == null) {
        logError "Client update was rejected or failed; aborting"
        return false
    }
    pauseExecution(1000)
    return true
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
