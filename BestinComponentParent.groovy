import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

/**
 * Hubitat Bestin — parent driver for the besthing WoT servient.
 *
 * Structure is discovered dynamically from the servient's Thing Description catalog
 * (GET http://<ip>:<port>/things): initialize() walks every Thing and maps affordances to
 * Hubitat component children through the rule tables below (THING_KIND_RULES + propKind()).
 * All interaction then runs over one WebSocket speaking the W3C Web Thing Protocol
 * (request/response/notification JSON envelopes; observations re-register per connection).
 *
 * What stays in code (the TD cannot express it):
 *  - semantic rules: which TD shapes become which Hubitat drivers, and value conversions
 *    (ventil mid<->medium, thermostat pause->off, motion detect->active, ...)
 *  - LEGACY_ALIASES: (thing|affordance) -> child names from the imap-XML era, so devices
 *    that predate this driver keep their DNIs, rules and dashboards.
 *
 * Children created by discovery carry data values (thingId/kind/propName) that drive
 * observe registration, incoming routing and component commands — names are never parsed.
 * New affordances only get children when the createNewDevices preference is on.
 */

metadata {
    definition (name: "Hubitat Bestin", namespace: "bestin", author: "Luke Lee",
                importUrl: "https://raw.githubusercontent.com/zekaizer/HubitatBestin/main/BestinComponentParent.groovy") {
        capability "Configuration"
        capability "Initialize"

        command "setLightState", [
            [name: "Device Name", type: "ENUM", constraints: ['livinglight', 'light01', 'light02', 'light03', 'light04']],
            [name: "Device Number", type: "NUMBER", description: "Number of the device"],
            [name: "Light State", type: "ENUM", constraints: ["on", "off"]]
        ]
        command "setTemperature", [
            [name: "Device Name", type: "ENUM", constraints: ['temper']],
            [name: "Device Number", type: "NUMBER", description: "Number of the device"],
            [name: "Temperature", type: "NUMBER", description: "Set temperature in Celsius"]
        ]
        command "setThermostatMode", [
            [name: "Device Name", type: "ENUM", constraints: ['temper']],
            [name: "Device Number", type: "NUMBER", description: "Number of the device"],
            [name: "Thermostat Mode", type: "ENUM", constraints: ["heat", "off"]]
        ]
        command "controlAirVentilator", [
            [name: "Device Name", type: "ENUM", constraints: ['ventil']],
            [name: "Device Number", type: "NUMBER", description: "Number of the device"],
            [name: "Fan Speed", type: "ENUM", constraints: ["off", "low", "medium", "high"]]
        ]
        command "updateAllStatus"
        command "deleteAllDevices"
        command "initialize"

        attribute "connection", "enum", ["connected", "disconnected"]
    }
}

preferences {
    input name: "ipAddress", type: "text", title: "IP Address", required: true
    input name: "port", type: "number", title: "Port", required: true, defaultValue: 8788
    input name: "createNewDevices", type: "bool", title: "Create child devices for newly discovered affordances", defaultValue: false
    input name: "newDeviceAllowlist", type: "text", title: "Only create these child names (comma-separated; blank = all)", required: false
    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
}

// ---------------------------------------------------------------------------
// Rule tables
// ---------------------------------------------------------------------------

// Things modeled as ONE child for the whole Thing (its properties are facets of one device).
// (GasValve is not mapped yet: this hub has no "Generic Component Valve" driver.)
@Field static final Map THING_KIND_RULES = [
    'wallpad:Thermostat' : [kind: 'thermostat', driver: 'Generic Component Thermostat'],
    'wallpad:Ventilation': [kind: 'fan',        driver: 'Generic Component Fan Control'],
    'wallpad:Doorlock'   : [kind: 'lock',       driver: 'Generic Component Lock'],
]

// The wireless doorlock reports no lock-status, so its lock child mirrors a door magnetic
// reed on a *different* Thing: door open (detect) -> unlocked, door closed (normal) -> locked.
// Maps the doorlock thingId to its state source (thingId + property).
@Field static final Map LOCK_STATE_SOURCE = [
    'urn:besthing:doorlock': [thingId: 'urn:besthing:sensor', prop: 'magnetic'],
]

@Field static final Map DRIVER_FOR_KIND = [
    'switch'      : 'Generic Component Switch',
    'actionSwitch': 'Generic Component Switch',
    'power'       : 'Generic Component Power Meter',
    'motion'      : 'Generic Component Motion Sensor',
    'contact'     : 'Generic Component Contact Sensor',
]

// (thingID|property) or (thingID|__thing__) -> child name from the imap-XML era.
// Guarantees pre-migration children keep their DNI `<parentDNI>-<name>`.
@Field static final Map LEGACY_ALIASES = [
    'urn:besthing:light:living|switch1': 'livinglight-1',
    'urn:besthing:light:living|switch2': 'livinglight-2',
    'urn:besthing:light:living|switch3': 'livinglight-3',
    'urn:besthing:light:living|switch4': 'livinglight-4',
    'urn:besthing:light:living|switch5': 'livinglight-5',
    'urn:besthing:light:room1|switch1' : 'light01-1',
    'urn:besthing:light:room1|switch2' : 'light01-2',
    'urn:besthing:light:room1|switch3' : 'light01-3',
    'urn:besthing:light:room2|switch1' : 'light02-1',
    'urn:besthing:light:room2|switch2' : 'light02-2',
    'urn:besthing:light:room3|switch1' : 'light03-1',
    'urn:besthing:light:room3|switch2' : 'light03-2',
    'urn:besthing:light:room4|switch1' : 'light04-1',
    'urn:besthing:light:room4|switch2' : 'light04-2',
    'urn:besthing:light:batch|batch'   : 'batchlight-1',
    'urn:besthing:ventil|__thing__'    : 'ventil-1',
    'urn:besthing:thermostat:room1|__thing__': 'temper-1',
    'urn:besthing:thermostat:room2|__thing__': 'temper-2',
    'urn:besthing:thermostat:room3|__thing__': 'temper-3',
    'urn:besthing:thermostat:room4|__thing__': 'temper-4',
]

@Field static final int RECONNECT_MAX_SEC = 60

// After an optimistic unlock, wait this long before reconciling the lock child to the
// real door state (in case the door magnetic never reports an opening).
@Field static final int RELOCK_SECONDS = 5

def installed() {
    logDebug "installed()"
    initialize()
}

def updated() {
    logDebug "updated()"
    initialize()
}

def configure() {
    initialize()
}

def initialize() {
    unschedule()
    // Drop leftover state from the imap-XML protocol version.
    ['submitedCommands', 'sendingCommands', 'sendingCommandCount', 'latestSendTime', 'msgNoCounter'].each { state.remove(it) }
    state.reconnectDelay = 1
    if (!state.lastFanSpeed) state.lastFanSpeed = "low"

    // Fresh install: installed() fires before preferences are ever saved.
    if (!settings.ipAddress || !settings.port) {
        log.warn "ipAddress/port not configured; save preferences to start"
        return
    }

    runDiscovery()
    connectWebSocket()
}

// ---------------------------------------------------------------------------
// TD discovery
// ---------------------------------------------------------------------------

private void runDiscovery() {
    def tds = fetchTdCatalog()
    if (tds == null) {
        log.warn "TD discovery failed; keeping existing children as-is (retrying in 60s)"
        runIn(60, "retryDiscovery")
        return
    }
    tds.each { td ->
        try {
            discoverThing(td)
        } catch (Exception e) {
            log.error "Discovery failed for ${td.id}: ${e.message}"
        }
    }
    if (!getChildDevices()) {
        log.warn "No child devices exist; enable the createNewDevices preference and re-initialize to create the discovered devices"
    }
}

// Late-discovery path: the servient's HTTP side was down when initialize() ran.
def retryDiscovery() {
    runDiscovery()
    if (state.connected) observeAll()
}

private List fetchTdCatalog() {
    def result = null
    def uri = "http://${settings.ipAddress}:${settings.port as int}/things"
    try {
        httpGet([uri: uri, contentType: 'application/json', timeout: 10]) { resp ->
            result = resp.data
        }
        logDebug "TD catalog fetched: ${result?.size()} things"
    } catch (Exception e) {
        log.error "TD fetch from ${uri} failed: ${e.message}"
    }
    return result
}

private void discoverThing(Map td) {
    String thingId = td.id
    // JSON-LD allows @type to be a single string or an array
    def types = td['@type']
    def thingRule = (types instanceof List ? types : [types]).findResult { THING_KIND_RULES[it as String] }
    if (thingRule) {
        ensureChildFor(thingId, null, thingRule.kind, thingRule.driver, td.title as String)
        return
    }

    td.properties?.each { propName, spec ->
        def kind = propKind(propName as String, spec as Map)
        if (kind) ensureChildFor(thingId, propName as String, kind, DRIVER_FOR_KIND[kind], "${td.title} ${propName}")
        else logDebug "No rule for property ${thingId}/${propName}"
    }

    // Stateless on/off actions (e.g. all_lights) become switches; sensitive ones never auto-map.
    td.actions?.each { actName, spec ->
        if (spec['wallpad:sensitive']) return
        if ((spec.input?.enum as Set) == (['on', 'off'] as Set)) {
            ensureChildFor(thingId, actName as String, 'actionSwitch', DRIVER_FOR_KIND.actionSwitch, "${td.title} ${actName}")
        } else {
            logDebug "No rule for action ${thingId}/${actName}"
        }
    }
}

// Property-level semantic rules: TD schema shape -> Hubitat child kind.
private static String propKind(String propName, Map spec) {
    boolean writable = !spec.readOnly
    if (writable && (spec.enum as Set) == (['on', 'off'] as Set)) return 'switch'
    if ((spec.enum as Set) == (['detect', 'normal'] as Set)) return propName == 'motion' ? 'motion' : 'contact'
    if (spec.readOnly && spec.unit == 'W') return 'power'
    return null
}

private void ensureChildFor(String thingId, String prop, String kind, String driver, String label) {
    def name = childNameFor(thingId, prop, kind)
    def dni = "${device.deviceNetworkId}-${name}"
    def child = getChildDevice(dni)
    if (!child) {
        // LEGACY_ALIASES only adopts pre-existing children; creating anything new
        // (legacy-named or not) is gated behind the createNewDevices preference.
        // Preferences set through the API may arrive as the STRING "false" (truthy!).
        if (settings.createNewDevices?.toString() != 'true') {
            logDebug "Skipping new device for ${thingId}/${prop ?: kind} (createNewDevices is off)"
            return
        }
        // Optional allowlist: when set, only the listed child names are created.
        def allow = (settings.newDeviceAllowlist ?: '').split(',')*.trim().findAll { it }
        if (allow && !(name in allow)) {
            logDebug "Skipping ${name}: not in newDeviceAllowlist"
            return
        }
        logDebug "Creating child ${dni} (${driver})"
        child = addChildDevice("hubitat", driver, dni, [name: name, label: label, isComponent: true])
        // The doorlock has no observable state; seed the resting "locked" position once.
        if (kind == 'lock') child.sendEvent(name: "lock", value: "locked")
    }
    child.updateDataValue("thingId", thingId)
    child.updateDataValue("kind", kind)
    if (prop) child.updateDataValue("propName", prop)

    if (kind == 'fan') child.sendEvent(name: "supportedFanSpeeds", value: JsonOutput.toJson(["low", "medium", "high", "off"]))
    if (kind == 'thermostat') child.sendEvent(name: "supportedThermostatModes", value: JsonOutput.toJson(["heat", "off"]))
}

private static String aliasKey(String thingId, String prop, String kind) {
    return THING_KIND_RULES.values().any { it.kind == kind } ? "${thingId}|__thing__" : "${thingId}|${prop}"
}

private static String childNameFor(String thingId, String prop, String kind) {
    def alias = LEGACY_ALIASES[aliasKey(thingId, prop, kind)]
    if (alias) return alias
    return prop ? "${shortThingId(thingId)}-${prop}" : shortThingId(thingId)
}

private static String shortThingId(String thingId) {
    return thingId.replace('urn:besthing:', '').replace(':', '_')
}

private static String thingLevelChildName(String thingId) {
    // toString(): a GString key would miss the map's String keys on stock Groovy
    return LEGACY_ALIASES["${thingId}|__thing__".toString()] ?: shortThingId(thingId)
}

// The properties a child observes (and refreshes); thing-level kinds have fixed facets.
private static List<String> observedProps(String kind, String propName) {
    switch (kind) {
        case 'thermostat': return ['current', 'target', 'mode']
        case 'fan': return ['mode']
        case 'valve': return ['valve']
        case 'lock': return []   // momentary release action; no readable state
        case 'actionSwitch': return []
        default: return propName ? [propName] : []
    }
}

// ---------------------------------------------------------------------------
// WebSocket lifecycle
// ---------------------------------------------------------------------------

def connectWebSocket() {
    unschedule("connectWebSocket")
    if (!settings.ipAddress || !settings.port) {
        log.warn "ipAddress/port not configured; skipping WebSocket connect"
        return
    }
    state.connecting = true
    state.connected = false
    // Closing a live socket fires webSocketStatus("status: closing"); the connecting
    // flag stops that self-initiated close from scheduling another reconnect (which
    // would tear the fresh connection down 1s later, looping forever).
    try {
        interfaces.webSocket.close()
    } catch (Exception ignored) { }
    def url = "ws://${settings.ipAddress}:${settings.port as int}"
    logDebug "Connecting to ${url}"
    try {
        interfaces.webSocket.connect(url, pingInterval: 30)
        // Watchdog: a close swallowed during the handshake yields neither open nor
        // failure, which would otherwise leave the driver disconnected forever.
        runIn(30, "ensureConnected")
    } catch (Exception e) {
        log.error "WebSocket connect failed: ${e.message}"
        scheduleReconnect()
    }
}

def ensureConnected() {
    if (!state.connected) {
        log.warn "WebSocket still not connected after 30s; retrying"
        connectWebSocket()
    }
}

def webSocketStatus(String message) {
    logDebug "webSocketStatus: ${message}"
    if (message.startsWith("status: open")) {
        unschedule("connectWebSocket")
        unschedule("ensureConnected")
        state.connecting = false
        state.connected = true
        state.reconnectDelay = 1
        sendEvent(name: "connection", value: "connected")
        log.info "WebSocket connected to ${settings.ipAddress}:${settings.port}"
        observeAll()
    } else if (message.startsWith("status: closing") || message.startsWith("failure:")) {
        if (state.connecting && message.startsWith("status: closing")) {
            logDebug "Ignoring self-initiated close during connect"
            return
        }
        state.connecting = false
        if (state.connected) log.warn "WebSocket disconnected: ${message}"
        state.connected = false
        sendEvent(name: "connection", value: "disconnected")
        scheduleReconnect()
    } else {
        log.warn "Unhandled webSocketStatus: ${message}"
    }
}

private scheduleReconnect() {
    def delay = state.reconnectDelay ?: 1
    state.reconnectDelay = Math.min(delay * 2, RECONNECT_MAX_SEC)
    logDebug "Reconnecting in ${delay}s"
    runIn(delay, "connectWebSocket")
}

// Observations are per-connection on the servient: re-register after every (re)connect.
// Each observe response carries the current value, refreshing all child states for free.
private void observeAll() {
    getChildDevices().each { child ->
        def thingId = child.getDataValue("thingId")
        if (!thingId) {
            log.warn "Child ${child.deviceNetworkId} has no WoT mapping (discovery never saw it); skipping"
            return
        }
        observedProps(child.getDataValue("kind"), child.getDataValue("propName")).each { prop ->
            sendWotRequest([operation: "observeproperty", thingID: thingId, name: prop])
        }
    }
}

// ---------------------------------------------------------------------------
// WoT envelope I/O
// ---------------------------------------------------------------------------

private void sendWotRequest(Map msg) {
    if (!state.connected) {
        log.warn "WebSocket not connected; dropping ${msg.operation} ${msg.thingID}/${msg.name}"
        return
    }
    msg.messageID = UUID.randomUUID().toString()
    msg.messageType = "request"
    def json = JsonOutput.toJson(msg)
    logDebug "WS -> ${json}"
    interfaces.webSocket.sendMessage(json)
}

def parse(String description) {
    logDebug "WS <- ${description}"
    def msg
    try {
        msg = new JsonSlurper().parseText(description)
    } catch (Exception e) {
        log.warn "Received non-JSON frame: ${description.take(200)}"
        return
    }

    if (msg.error) {
        log.warn "WoT error for ${msg.thingID}/${msg.name} (${msg.operation}): ${msg.error.status} ${msg.error.detail}"
        return
    }

    switch (msg.operation) {
        case "readproperty":
        case "writeproperty":
        case "observeproperty":
            // responses and observe notifications all carry the current value
            if (msg.containsKey("value")) updateFromWot(msg.thingID as String, msg.name as String, msg.value)
            break
        case "invokeaction":
            // correlationID round-trips "<childDNI>|<value>" for stateless action switches
            if (msg.correlationID) {
                def parts = (msg.correlationID as String).split(/\|/, 2)
                def child = getChildDevice(parts[0])
                if (child && parts.size() == 2) {
                    child.parse([[name: "switch", value: parts[1], descriptionText: "${child.displayName} was turned ${parts[1]}"]])
                }
            }
            break
        case "unobserveproperty":
        case "subscribeevent":
        case "unsubscribeevent":
            break
        default:
            logDebug "Unhandled operation: ${msg.operation}"
    }
}

// Route an incoming (thing, property, value) to its child. Child names are a pure function
// of (thing, prop) — the same childNameFor used at creation — so no index is needed.
private void updateFromWot(String thingId, String propName, value) {
    reflectDoorlockState(thingId, propName, value)
    def thingChild = getChildDevice("${device.deviceNetworkId}-${thingLevelChildName(thingId)}")
    if (thingChild) {
        handleThingUpdate(thingChild, propName, value)
        return
    }
    def child = getChildDevice("${device.deviceNetworkId}-${childNameFor(thingId, propName, 'property')}")
    if (child) {
        handlePropUpdate(child, value)
        return
    }
    logDebug "No child for ${thingId}/${propName} = ${value}"
}

// Facet updates of a whole-Thing child (thermostat / fan / valve).
private void handleThingUpdate(child, String propName, value) {
    switch (child.getDataValue("kind")) {
        case 'thermostat':
            switch (propName) {
                case 'current':
                    child.parse([[name: "temperature", value: value, unit: "°C"]])
                    break
                case 'target':
                    child.parse([[name: "heatingSetpoint", value: value, unit: "°C"],
                                 [name: "thermostatSetpoint", value: value, unit: "°C"]])
                    break
                case 'mode':
                    child.parse([[name: "thermostatMode", value: wotThermoModeToHubitat(value as String)],
                                 [name: "thermostatOperatingState", value: value == "heat" ? "heating" : "idle"]])
                    break
            }
            break
        case 'fan':
            if (propName != 'mode') { logDebug "Unmapped fan property ${propName} = ${value}"; return }
            def speed = wotWindToSpeed(value as String)
            if (speed != "off") state.lastFanSpeed = speed
            child.parse([[name: "speed", value: speed, descriptionText: "${child.displayName} speed is ${speed}"]])
            break
        case 'valve':
            if (propName != 'valve') return
            // 'operation' is a transitional motor state; report only settled positions
            if (value == 'open' || value == 'close') {
                child.parse([[name: "valve", value: value == 'open' ? 'open' : 'closed']])
            }
            break
    }
}

// Updates of a per-property child (switch / power / motion / contact).
private void handlePropUpdate(child, value) {
    switch (child.getDataValue("kind")) {
        case 'switch':
            child.parse([[name: "switch", value: value, descriptionText: "${child.displayName} was turned ${value}"]])
            break
        case 'power':
            child.parse([[name: "power", value: value, unit: "W"]])
            break
        case 'motion':
            child.parse([[name: "motion", value: value == 'detect' ? 'active' : 'inactive']])
            break
        case 'contact':
            child.parse([[name: "contact", value: value == 'detect' ? 'open' : 'closed']])
            break
        default:
            logDebug "No update handler for kind ${child.getDataValue('kind')}"
    }
}

// ---------------------------------------------------------------------------
// Value conversions (Hubitat <-> TD vocabularies)
// ---------------------------------------------------------------------------

private static String hubitatSpeedToWotWind(String speed) {
    switch (speed?.trim()) {
        case "off": return "off"
        case "low":
        case "medium-low": return "low"
        case "medium":
        case "medium-high": return "mid"
        case "high": return "high"
        default: return null
    }
}

private static String wotWindToSpeed(String wind) {
    switch (wind) {
        case "off": return "off"
        case "low": return "low"
        case "mid": return "medium"
        case "high": return "high"
        default: return "unknown(${wind})"
    }
}

// TD mode enum: off/heat/sleep/reservation/pause; only off/heat/pause are client-settable.
private static String wotThermoModeToHubitat(String mode) {
    switch (mode) {
        case "heat": return "heat"
        case "sleep":
        case "reservation": return "auto"
        default: return "off"   // off, pause
    }
}

// ---------------------------------------------------------------------------
// Commands (legacy parent commands kept for compatibility)
// ---------------------------------------------------------------------------

private getLegacyChild(String devName, def devNum) {
    def child = getChildDevice("${device.deviceNetworkId}-${devName}-${devNum}")
    if (!child) log.warn "No child device ${devName}-${devNum}"
    return child
}

def setLightState(String devName, BigDecimal devNum, String value) {
    log.info "setLightState ${devName}-${devNum} -> ${value}"
    def child = getLegacyChild(devName, devNum as int)
    if (child) switchChildTo(child, value)
}

def setTemperature(String devName, BigDecimal devNum, BigDecimal temperature) {
    log.info "setTemperature ${devName}-${devNum} -> ${temperature}"
    def child = getLegacyChild(devName, devNum as int)
    if (child) componentSetHeatingSetpoint(child, temperature)
}

def setThermostatMode(String devName, BigDecimal devNum, String mode) {
    log.info "setThermostatMode ${devName}-${devNum} -> ${mode}"
    def child = getLegacyChild(devName, devNum as int)
    if (child) componentSetThermostatMode(child, mode)
}

def controlAirVentilator(String devName, BigDecimal devNum, String fanSpeed) {
    log.info "controlAirVentilator ${devName}-${devNum} -> ${fanSpeed}"
    def child = getLegacyChild(devName, devNum as int)
    if (child) componentSetSpeed(child, fanSpeed)
}

def updateAllStatus() {
    logDebug "updateAllStatus called for all child devices"
    getChildDevices().each { child -> componentRefresh(child) }
}

def deleteAllDevices() {
    logDebug "deleteAllDevices called for all child devices"
    getChildDevices().each { child ->
        try {
            deleteChildDevice(child.deviceNetworkId)
        } catch (Exception e) {
            log.error "Error deleting child device ${child.deviceNetworkId}: ${e.message}"
        }
    }
}

// ---------------------------------------------------------------------------
// Child component callbacks
// ---------------------------------------------------------------------------

void componentRefresh(childDevice) {
    def thingId = childDevice.getDataValue("thingId")
    if (!thingId) return
    observedProps(childDevice.getDataValue("kind"), childDevice.getDataValue("propName")).each { prop ->
        sendWotRequest([operation: "readproperty", thingID: thingId, name: prop])
    }
}

void componentOn(childDevice) { switchChildTo(childDevice, "on") }
void componentOff(childDevice) { switchChildTo(childDevice, "off") }

private void switchChildTo(child, String value) {
    def thingId = child.getDataValue("thingId")
    def propName = child.getDataValue("propName")
    switch (child.getDataValue("kind")) {
        case 'switch':
            sendWotRequest([operation: "writeproperty", thingID: thingId, name: propName, value: value])
            break
        case 'actionSwitch':
            // No observable state: the child's switch is set from the invokeaction reply.
            sendWotRequest([operation: "invokeaction", thingID: thingId, name: propName, input: value,
                            correlationID: "${child.deviceNetworkId}|${value}".toString()])
            break
        case 'fan':
            // Fan Control also exposes Switch: on -> last speed, off -> stop
            componentSetSpeed(child, value)
            break
        default:
            log.warn "on/off not supported for ${child.displayName} (kind ${child.getDataValue('kind')})"
    }
}

void componentSetSpeed(childDevice, speed) {
    def fanSpeed = speed == "on" ? state.lastFanSpeed : speed
    def wind = hubitatSpeedToWotWind(fanSpeed as String)
    if (!wind) {
        log.warn "Unsupported fan speed: ${speed}"
        return
    }
    if (wind != "off") state.lastFanSpeed = wotWindToSpeed(wind)
    sendWotRequest([operation: "writeproperty", thingID: childDevice.getDataValue("thingId"), name: "mode", value: wind])
}

void componentCycleSpeed(childDevice) {
    def next = [low: "medium", medium: "high", high: "low"][childDevice.currentValue("speed")] ?: "low"
    componentSetSpeed(childDevice, next)
}

void componentSetHeatingSetpoint(childDevice, temperature) {
    // TD: 5..40 in 0.5 steps
    def target = Math.min(Math.max(Math.round(((temperature as BigDecimal) * 2).doubleValue()) / 2.0d, 5.0d), 40.0d)
    sendWotRequest([operation: "writeproperty", thingID: childDevice.getDataValue("thingId"), name: "target", value: target])
}

void componentSetThermostatMode(childDevice, mode) {
    if (!(mode in ["heat", "off"])) {
        log.warn "Thermostat mode '${mode}' not supported by the wallpad (heat/off only)"
        return
    }
    sendWotRequest([operation: "writeproperty", thingID: childDevice.getDataValue("thingId"), name: "mode", value: mode])
}

void componentHeat(childDevice) { componentSetThermostatMode(childDevice, "heat") }
void componentAuto(childDevice) { log.warn "auto mode not supported" }
void componentCool(childDevice) { log.warn "cool mode not supported" }
void componentEmergencyHeat(childDevice) { log.warn "emergency heat not supported" }
void componentSetCoolingSetpoint(childDevice, temperature) { log.warn "cooling setpoint not supported" }
void componentFanAuto(childDevice) { log.warn "thermostat fan not supported" }
void componentFanCirculate(childDevice) { log.warn "thermostat fan not supported" }
void componentFanOn(childDevice) { log.warn "thermostat fan not supported" }

// Gas valve: remote close only — opening requires physical action at the wallpad.
void componentClose(childDevice) {
    sendWotRequest([operation: "invokeaction", thingID: childDevice.getDataValue("thingId"), name: "close", input: -1])
}

void componentOpen(childDevice) {
    log.warn "Gas valve cannot be opened remotely"
}

// Doorlock: momentary remote release only (the wallpad relays SetWddrCont, no input).
// The lock has no status of its own, so we show "unlocked" optimistically and let the
// door magnetic settle the real state; relock() reconciles if the door never opens.
void componentUnlock(childDevice) {
    sendWotRequest([operation: "invokeaction", thingID: childDevice.getDataValue("thingId"), name: "open"])
    childDevice.parse([[name: "lock", value: "unlocked", descriptionText: "${childDevice.displayName} was unlocked"]])
    runIn(RELOCK_SECONDS, "relock", [data: [dni: childDevice.deviceNetworkId]])
}

void componentLock(childDevice) {
    // The wallpad cannot be locked on command (it auto-relocks); reflect the real door state.
    unschedule("relock")
    setLockToDoorState(childDevice)
}

// After the optimistic unlock window, settle the lock to whatever the door is actually doing.
void relock(Map data) {
    def child = getChildDevice(data.dni as String)
    if (child) setLockToDoorState(child)
}

// Set a lock child's state from its door magnetic source (open door -> unlocked).
private void setLockToDoorState(childDevice) {
    boolean open = doorIsOpen(childDevice.getDataValue("thingId"))
    childDevice.parse([[name: "lock", value: open ? "unlocked" : "locked",
                        descriptionText: "${childDevice.displayName} is ${open ? 'unlocked' : 'locked'}"]])
}

// True if the door magnetic source for this lock currently reads "open".
private boolean doorIsOpen(String lockThingId) {
    def src = LOCK_STATE_SOURCE[lockThingId]
    if (!src) return false
    def sensor = getChildDevice("${device.deviceNetworkId}-${childNameFor(src.thingId, src.prop, 'contact')}")
    return sensor?.currentValue("contact") == 'open'
}

// Mirror a door magnetic update onto the doorlock lock child (detect=open=unlocked,
// normal=closed=locked). A live sensor reading supersedes the optimistic relock timer.
private void reflectDoorlockState(String thingId, String propName, value) {
    LOCK_STATE_SOURCE.each { lockThingId, src ->
        if (src.thingId != thingId || src.prop != propName) return
        def lock = getChildDevice("${device.deviceNetworkId}-${thingLevelChildName(lockThingId)}")
        if (!lock) return
        unschedule("relock")
        boolean open = (value == 'detect')
        lock.parse([[name: "lock", value: open ? "unlocked" : "locked",
                     descriptionText: "${lock.displayName} ${open ? 'unlocked' : 'locked'} (door ${open ? 'open' : 'closed'})"]])
    }
}

private void logDebug(String msg) {
    if (settings.logEnable != false) log.debug msg
}
