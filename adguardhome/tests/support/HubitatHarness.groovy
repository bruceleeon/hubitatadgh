/*
 * Test-only Hubitat harness: loads the Hubitat Groovy components as plain
 * scripts, executes their metadata/preferences DSL blocks against a
 * recording stub, and services their HTTP calls from in-memory fixtures.
 * No network access, no Hubitat installation and no credentials required.
 */
import groovy.json.JsonSlurper

class MockResponse {
    def data
    int status
    MockResponse(data, int status = 200) { this.data = data; this.status = status }
    def getData() { return data }
    int getStatus() { return status }
}

class MockLog {
    List messages = []
    void info(msg)  { messages << ["level": "info",  "msg": msg.toString()] }
    void debug(msg) { messages << ["level": "debug", "msg": msg.toString()] }
    void warn(msg)  { messages << ["level": "warn",  "msg": msg.toString()] }
    void error(msg) { messages << ["level": "error", "msg": msg.toString()] }
    void trace(msg) { messages << ["level": "trace", "msg": msg.toString()] }
    List at(String level) { return messages.findAll { it.level == level } }
    boolean contains(String level, String needle) {
        return at(level).any { it.msg.contains(needle) }
    }
    boolean containsAnywhere(String needle) {
        return messages.any { it.msg.contains(needle) }
    }
}

class MockDevice {
    String deviceNetworkId
    String label
    Map attrs = [:]
    List calls = []
    def currentValue(String n) { return attrs[n] }
    def methodMissing(String name, args) {
        calls << [name: name, args: args as List]
        return null
    }
}

class RecordingDsl {
    List records = []
    def methodMissing(String name, args) {
        records << [name: name, args: (args as List)]
        return null
    }
}

class HubitatHarness {
    static final JsonSlurper SLURPER = new JsonSlurper()

    def script = null
    MockLog logMock = new MockLog()
    MockDevice device = new MockDevice()
    Map state = [:]
    List calls = []        // HTTP calls: [method, path, body, headers]
    List responses = []    // sendEvent payloads
    Map children = [:]     // deviceNetworkId -> MockDevice
    List routes = []       // [method, path, data|error|errorMsg, once, used]
    Map dsl = [:]          // DSL records by top-level block name

    static HubitatHarness load(File file, Map opts = [:]) {
        def harness = new HubitatHarness()
        def binding = new Binding()

        // Runtime services used by the drivers/apps at execution time.
        binding.setVariable("log", harness.logMock)
        harness.device.label = (opts.label ?: "Mock Device")
        binding.setVariable("device", harness.device)
        binding.setVariable("state", harness.state)
        binding.setVariable("serverIP", opts.containsKey("serverIP") ? opts.serverIP : "10.0.0.9")
        binding.setVariable("username", opts.username ?: "tester")
        binding.setVariable("password", opts.password ?: "test-password")
        binding.setVariable("tlsEnable", opts.tlsEnable ?: false)
        binding.setVariable("logDebug", opts.logDebug ?: false)
        binding.setVariable("logTrace", opts.logTrace ?: false)
        binding.setVariable("descriptionTextEnable", false)

        binding.setVariable("sendEvent", { Map evt ->
            harness.responses << evt
            harness.device.attrs[evt.name] = (evt.value == null) ? null : evt.value.toString()
            return null
        } as Closure)

        def dispatch = { String method, Map params, Closure resp ->
            def uri = params.uri.toString()
            def path = uri.replaceFirst(/^https?:\/\/[^\/]+/, "")
            harness.calls << [method: method, path: path, body: params.body, headers: params.headers]
            def entry = harness.matchRoute(method, path)
            if (entry == null) {
                throw new RuntimeException("MockHttp: unexpected request ${method} ${path}")
            }
            if (entry.once) { entry.used = true }
            if (entry.error) {
                throw new RuntimeException("org.apache.http.client.HttpResponseException: status: ${entry.status}, reason: ${entry.errorMsg} | request: ${params}")
            }
            def data = (entry.data instanceof Closure) ? entry.data.call(params) : entry.data
            return (resp != null) ? resp.call(new MockResponse(data)) : data
        }
        binding.setVariable("httpGet",      { p, c -> dispatch("GET", p, c) })
        binding.setVariable("httpPost",     { p, c -> dispatch("POST", p, c) })
        binding.setVariable("httpPostJson", { p, c -> dispatch("POST", p, c) })
        binding.setVariable("httpPutJson",  { p, c -> dispatch("PUT", p, c) })

        // Child-device services.
        binding.setVariable("getChildDevice", { id -> harness.children["${id}"] })
        binding.setVariable("getChildDevices", { -> harness.children.values().toList() })
        binding.setVariable("getAllChildDevices", { -> harness.children.values().toList() })
        // Supports both driver-style addChildDevice(driver, dni, meta) and
        // app-style addChildDevice(namespace, driver, dni, location, meta).
        binding.setVariable("addChildDevice", { Object... args ->
            def strings = args.findAll { it instanceof String }
            def meta = args.find { it instanceof Map }
            def id = strings.size() > 0 ? strings[-1] : "unknown"
            def driverName = strings.size() >= 2 ? strings[-2] : "unknown"
            def child = new MockDevice(deviceNetworkId: id, label: meta?.label)
            harness.children[id] = child
            harness.calls << [method: "ADD_CHILD", path: driverName, body: [id: id, meta: meta], headers: [:]]
            return child
        })
        binding.setVariable("deleteChildDevice", { id -> harness.children.remove("${id}") })
        binding.setVariable("pauseExecution", { ms -> null })
        binding.setVariable("runEvery5Minutes", { m -> null })
        binding.setVariable("runIn", { s, m -> null })
        binding.setVariable("subscribe", { Object... a -> null })
        binding.setVariable("unsubscribe", { Object... a -> null })
        binding.setVariable("now", { -> System.currentTimeMillis() })
        binding.setVariable("settings", new LinkedHashMap())
        if (opts.containsKey("parent")) {
            binding.setVariable("parent", opts.parent)
        }
        // Any remaining preferences used by the component under test.
        opts.each { k, v ->
            if (!binding.hasVariable(k as String)) {
                binding.setVariable(k as String, v)
            }
        }

        // DSL blocks are executed once at load time and recorded.
        ["metadata", "preferences"].each { block ->
            binding.setVariable(block, { Closure c ->
                def rec = new RecordingDsl()
                c.delegate = rec
                c.resolveStrategy = Closure.DELEGATE_FIRST
                c.call()
                harness.dsl[block] = rec.records
                return null
            })
        }
        binding.setVariable("definition", { a, b -> null })

        def shell = new GroovyShell(binding)
        harness.script = shell.parse(file)
        harness.script.run()
        return harness
    }

    // Fixture helpers -----------------------------------------------------
    static def fixture(File dir, String name) {
        return SLURPER.parse(new File(dir, name))
    }

    HubitatHarness route(String method, String path, Object data) {
        routes << [method: method, path: path, data: data]
        return this
    }

    HubitatHarness routeFile(File dir, String method, String path, String fixtureName) {
        return route(method, path, fixture(dir, fixtureName))
    }

    HubitatHarness routeOnce(String method, String path, Object data) {
        routes << [method: method, path: path, data: data, once: true]
        return this
    }

    HubitatHarness routeError(String method, String path, int status, String msg) {
        routes << [method: method, path: path, error: true, status: status, errorMsg: msg]
        return this
    }

    HubitatHarness routeErrorOnce(String method, String path, int status, String msg) {
        routes << [method: method, path: path, error: true, status: status, errorMsg: msg, once: true]
        return this
    }

    def matchRoute(String method, String path) {
        def entry = routes.find { it.method == method && it.path == path && !it.used }
        if (entry == null) {
            entry = routes.find { it.method == method && it.path.endsWith("*") &&
                path.startsWith(it.path.substring(0, it.path.length() - 1)) && !it.used }
        }
        return entry
    }

    List findCalls(String method, String pathPrefix) {
        return calls.findAll { it.method == method && it.path.startsWith(pathPrefix) }
    }

    def lastBody(String method, String pathPrefix) {
        def found = findCalls(method, pathPrefix)
        return found.isEmpty() ? null : found[-1].body
    }
}
