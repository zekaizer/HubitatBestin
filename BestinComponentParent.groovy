import hubitat.device.HubAction
import hubitat.device.Protocol
import groovy.time.TimeCategory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap

metadata {
    definition (name: "Hubitat Bestin", namespace: "bestin", author: "Luke Lee") {
        capability "Configuration"
        
        command "setLightState", [
            [name: "Device Name", type: "ENUM", constraints: ['batchlight', 'livinglight', 'light01', 'light02', 'light03', 'light04']],
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
            [name: "Thermostat Mode", type: "ENUM", constraints: ["heat", "cool", "auto", "off"]]
        ]
        command "controlAirVentilator", [
            [name: "Device Name", type: "ENUM", constraints: ['ventil']],
            [name: "Device Number", type: "NUMBER", description: "Number of the device"],
            [name: "Ventilator State", type: "ENUM", constraints: ["on", "off"]],
            [name: "Fan Speed", type: "ENUM", constraints: ["low", "medium", "high", "auto"]]
        ]
        command "updateAllStatus"
        command "deleteAllDevices"
        command "initialize"
    }
}

preferences {
    input name: "ipAddress", type: "text", title: "IP Address", required: true
    input name: "port", type: "number", title: "Port", required: true
    input name: "sender", type: "text", title: "Sender", required: true, description: "예: 203동701호"
}

def installed() {
    log.debug "installed()"
    initialize()
}

def updated() {
   log.debug "updated()"
   initialize()
}

def initialize() {
    unschedule()
    state.clear()
    state.submitCommands = new ConcurrentLinkedQueue<Map>()
    state.sendingCommands = new ConcurrentHashMap<String, Map>()
    state.sendingCommandCount = new AtomicInteger(0)
    state.msgNoCounter = new Random().nextInt(999999) + 1
    schedule("*/1 * * ? * *", "bestinCheckCommandTimeouts")
}

def parse(String description) {
    log.debug "Received: ${description}"
    
    def message = parseTcpMessage(description)
    if (message) {
        processMessage(message)
    }
}

def updateDeviceStatus(String devName, BigDecimal devNum, String status) {
    def childDevice = getChildDeviceByNameAndNumber(devName, devNum)
    if (childDevice) {
        switch(devName) {
            case ['batchlight', 'livinglight', 'light01', 'light02', 'light03', 'light04']:
                childDevice.parse([[name: "switch", value: status == "timeout" ? "unknown" : status]])
                break
            case 'temper':
                childDevice.parse([[name: "thermostatMode", value: status == "timeout" ? "unknown" : status]])
                break
            case 'ventil':
                childDevice.parse([[name: "switch", value: status == "timeout" ? "unknown" : status]])
                break
        }
    }
}

// 명령 구현
def requestDeviceStatus(String devName, BigDecimal devNum) {
    log.debug "requestDeviceStatus called for ${devName} (Number: ${devNum})"
    def childDevice = fetchChild(devName, devNum, "Switch")
    if (childDevice) {
        def msgNo = getNextMsgNo()
        def xml = bestinXMLDeviceGetStatusRequest(devName, devNum, msgNo, "GET_DEVICE_STATE")
        bestinSubmitXMLRequest(devName, devNum, msgNo, xml)
    }
}

def setLightState(String devName, BigDecimal devNum, String state) {
    log.debug "setLightState called for ${devName} (Number: ${devNum}) with state: ${state}"
    def childDevice = fetchChild(devName, devNum, "Switch")
    if (childDevice) {
        def msgNo = getNextMsgNo()
        def xml = bestinXMLDeviceSetControlRequest(devName, devNum, msgNo, state, "SET_LIGHT_STATE")
        bestinSubmitXMLRequest(devName, devNum, msgNo, xml)
    }
}

def setTemperature(String devName, BigDecimal devNum, BigDecimal temperature) {
    log.debug "setTemperature called for ${devName} (Number: ${devNum}) with temperature: ${temperature}"
    def childDevice = fetchChild(devName, devNum, "Thermostat")
    if (childDevice) {
        def msgNo = getNextMsgNo()
        def xml = bestinXMLDeviceSetControlRequest(devName, devNum, msgNo, temperature.toString(), "SET_TEMPERATURE")
        bestinSubmitXMLRequest(devName, devNum, msgNo, xml)
    }
}

def setThermostatMode(String devName, BigDecimal devNum, String mode) {
    log.debug "setThermostatMode called for ${devName} (Number: ${devNum}) with mode: ${mode}"
    def childDevice = fetchChild(devName, devNum, "Thermostat")
    if (childDevice) {
        def msgNo = getNextMsgNo()
        def xml = bestinXMLDeviceSetControlRequest(devName, devNum, msgNo, mode, "SET_THERMOSTAT_MODE")
        bestinSubmitXMLRequest(devName, devNum, msgNo, xml)
    }
}

def controlAirVentilator(String devName, BigDecimal devNum, String state, String fanSpeed) {
    log.debug "controlAirVentilator called for ${devName} (Number: ${devNum}) with state: ${state}, fan speed: ${fanSpeed}"
    def childDevice = fetchChild(devName, devNum, "Fan")
    if (childDevice) {
        def msgNo = getNextMsgNo()
        def action = "${state},${fanSpeed}"
        def xml = bestinXMLDeviceSetControlRequest(devName, devNum, msgNo, action, "CONTROL_AIR_VENTILATOR")
        bestinSubmitXMLRequest(devName, devNum, msgNo, xml)
    }
}

def updateAllStatus() {
    log.debug "updateAllStatus called for all child devices"
    
    def children = getChildDevices()
    children.each { child ->
        log.debug "Updating status for child device: ${child.deviceNetworkId}"
        try {
            def (devName, devNum) = child.name.split('-')
            requestDeviceStatus(devName, devNum as BigDecimal)
        } catch (Exception e) {
            log.error "Error updating status for child device ${child.deviceNetworkId}: ${e.message}"
        }
    }
}

def deleteAllDevices() {
    log.debug "deleteAllDevices called for all child devices"
    
    def children = getChildDevices()
    children.each { child ->
        log.debug "delete child device: ${child.deviceNetworkId}"
        try {
            deleteChildDevice(child.deviceNetworkId)
        } catch (Exception e) {
            log.error "Error deleting child device ${child.deviceNetworkId}: ${e.message}"
        }
    }
}

private def fetchChild(String devName, BigDecimal devNum, String type) {
    if (devNum == null) {
        log.error "fetchChild called with null devNum for device: ${devName}"
        return null
    }

    def childDeviceNetworkId = "${device.deviceNetworkId}-${devName}-${devNum}"
    def childDevice = getChildDevice(childDeviceNetworkId)
    
    if (!childDevice) {
        log.debug "Child device not found. Creating new child device: ${childDeviceNetworkId}"
        switch(type) {
            case "Switch":
            case "Thermostat":
            case "Fan":
                childDevice = addChildDevice("hubitat", "Generic Component ${type}", childDeviceNetworkId, [name: "${devName}-${devNum}", isComponent: true])
                break
            default:
                log.warn "Unknown device type: ${type}. Child device not created."
                return null
        }
    }
    
    return childDevice
}

// Child Device Methods
void componentRefresh(childDevice) {
    log.debug "componentRefresh called with child device: ${childDevice}"
    def (devName, devNum) = childDevice.name.split('-')
    requestDeviceStatus(devName, devNum as BigDecimal)
}

void componentOn(childDevice) {
    log.debug "componentOn called with child device: ${childDevice}"
    def (devName, devNum) = childDevice.name.split('-')
    setLightState(devName, devNum as BigDecimal, "on")
}

void componentOff(childDevice) {
    log.debug "componentOff called with child device: ${childDevice}"
    def (devName, devNum) = childDevice.name.split('-')
    setLightState(devName, devNum as BigDecimal, "off")
}

void componentSetSpeed(childDevice, speed) {
    log.debug "componentSetSpeed called with child device: ${childDevice}, speed: ${speed}"
    def (devName, devNum) = childDevice.name.split('-')
    controlAirVentilator(devName, devNum as BigDecimal, "on", speed)
}

void componentSetHeatingSetpoint(childDevice, temperature) {
    log.debug "componentSetHeatingSetpoint called with child device: ${childDevice}, temperature: ${temperature}"
    def (devName, devNum) = childDevice.name.split('-')
    setTemperature(devName, devNum as BigDecimal, temperature)
}

// Helper method to get child device by name and number
private getChildDeviceByNameAndNumber(String devName, BigDecimal devNum) {
    def childDeviceNetworkId = "${device.deviceNetworkId}-${devName}-${devNum}"
    return getChildDevice(childDeviceNetworkId)
}

private bestinXMLDeviceGetStatusRequest(String devName, BigDecimal devNum, Integer msgNo, String cmdId) {
    def xml = """
        <?xml version="1.0" encoding="utf-8"?>
        <imap ver="1.0" address="10.3.7.1" sender="${settings.sender}">
        <service type="request" name="msg_home_device_get_status">
            <target name="hubitat" id="1" msg_no="${msgNo}" />
            <model_id>${devName}</model_id>
            <dev_num>${devNum}</dev_num>
            <command_id>${cmdId}</command_id>
        </service>
        </imap>
    """
    return xml
}

private bestinXMLDeviceSetControlRequest(String devName, BigDecimal devNum, BigDecimal msgNo, String action, String cmdId) {
    def xml = """
        <?xml version="1.0" encoding="utf-8"?>
        <imap ver="1.0" address="10.3.7.1" sender="${settings.sender}">
        <service type="request" name="msg_home_device_set_control">
            <target name="hubitat" id="1" msg_no="${msgNo}" />
            <model_id>${devName}</model_id>
            <dev_num>${devNum}</dev_num>
            <action>${action}</action>
            <command_id>${cmdId}</command_id>
        </service>
        </imap>
    """
    return xml
}

// msg_no 생성 메서드
private getNextMsgNo() {
    state.msgNoCounter = (state.msgNoCounter + 1) % 1000000 // 큰 수로 순환
    return state.msgNoCounter
}

private bestinSubmitXMLRequest(String devName, BigDecimal devNum, BigDecimal msgNo, String xml) {
    log.debug "bestinSubmitXMLRequest called with child device: ${devName}-${devNum} msgNo:${msgNo}"
    //state.submitCommands.offer([msgNo: msgNo, devName: devName, devNum: devNum, xml: xml])
    state.submitCommands.add([msgNo: msgNo, devName: devName, devNum: devNum, xml: xml])
    bestinProcessSubmitedXMLRequest()
}

private bestinProcessSubmitedXMLRequest() {
    while (state.sendingCommandCount < 1) {
        try {
        def command = state.submitCommands.remove(0)
            if (command) {
                bestinSendXMLRequest(command.devName, command.devNum, command.msgNo, command.xml)
            }
        } catch (IndexOutOfBoundsException e) {
            break
        }
    }
}

// XML 요청 메서드
private bestinSendXMLRequest(String devName, BigDecimal devNum, BigDecimal msgNo, String xml) {
    log.debug "bestinSendXMLRequest called with child device: ${devName}-${devNum}"
    state.sendingCommands.put(msgNo.toString(), [devName: devName, devNum: devNum, time: new Date().time])
    state.sendingCommandCount++//.incrementAndGet()
    sendTcpMessage(xml)
}

def bestinCheckCommandTimeouts() {
    def now = new Date().time
    def timeoutThreshold = 30

    // ERROR: groovy.lang.MissingMethodException: No signature of method
    // boolean removedAny = state.sendingCommands.removeIf { msgNo, command -> 
    //     if (now - command.time > timeoutThreshold * 1000) {
    //         log.warn "Command timed out: ${command}"
    //         updateDeviceStatus(command.devName, command.devNum, "timeout")
    //         return true
    //     }
    //     return false
    // }

    def iterator = state.sendingCommands.entrySet().iterator()
    while (iterator.hasNext()) {
        def entry = iterator.next()
        try {
            if (now - entry.value.time > timeoutThreshold * 1000) {
                log.warn "Command timed out: ${entry.value}"
                updateDeviceStatus(entry.value.devName, entry.value.devNum, "timeout")
                iterator.remove()
                state.sendingCommandCount--//.decrementAndGet()
            }
        } catch (IllegalStateException e) {
            log.warn "Command already removed: ${entry.key}"
        }
    }

    bestinProcessSubmitedXMLRequest()
}

private bestinHandleReply(xml) {
    def result = xml.service.@result.text()
    def msgNo = xml.service.target.@msg_no.toString()

    log.debug "bestinHandleReply result ${result}, msg_no ${msgNo}"
    log.debug "bestinHandleReply ${state.sendingCommands}"
    
    def command = state.sendingCommands.remove(msgNo)
    if (command) {
        log.debug "Received reply for command: ${command}, result: ${result}"
        
        state.sendingCommandCount--//.decrementAndGet()

        if (result == "ok") {
            def deviceType = xml.service.model_id.text()
            def devNum = xml.service.dev_num.text()
            def status = xml.service.status.text()
            
            log.debug "bestinHandleReply Reply deviceType ${deviceType}, devNum ${devNum}, status ${status}, elapsed ${new Date().time - command.time}"
            updateDeviceStatus(deviceType, new BigDecimal(devNum), status)
        }
    } else {
        log.warn "Received reply for unknown msg_no: ${msgNo}"
    }

    bestinProcessSubmitedXMLRequest()
}

private sendTcpMessage(String message) {
    def hubAction = new HubAction(
        message,
        Protocol.LAN,
        [
            type: HubAction.Type.LAN_TYPE_RAW,
            destinationAddress: "${settings.ipAddress}:${settings.port}"
        ]
    )
    sendHubCommand(hubAction)
}

private parseTcpMessage(String description) {
    // HEX_STRING에서 실제 hex 값 추출
    def hexString = description.split("payload:")[1].trim()

    // Base64를 바이트 배열로 변환
    byte[] bytes = hexString.decodeBase64()
    
    // 바이트 배열을 문자열로 변환
    String decodedString = new String(bytes, "UTF-8")
    
    return decodedString
}

private processMessage(String message) {
    // 여기서 받은 메시지를 처리합니다.
    // 예: 상태 업데이트, 명령 응답 처리 등
    log.debug "Processing message: ${message}"

    // 메시지 내용에 따라 적절한 처리 로직 구현
    def cleanedXml = message.trim() // 앞뒤 공백 제거
    
    // BOM 제거 (UTF-8 BOM인 경우)
    if (cleanedXml.startsWith("\uFEFF")) {
        cleanedXml = cleanedXml.substring(1)
    }
    
    // XML 선언이 없는 경우 추가
    if (!cleanedXml.startsWith("<?xml")) {
        cleanedXml = "<?xml version='1.0' encoding='UTF-8'?>\n" + cleanedXml
    }
    
    try {
        def xmlSlurper = new XmlSlurper()
        def xml = xmlSlurper.parseText(cleanedXml)
        log.debug "XML 내용: ${cleanedXml}"
        if (xml.service.@type == "reply") {
            bestinHandleReply(xml)
        }
    } catch (org.xml.sax.SAXParseException e) {
        log.error "XML 파싱 오류: ${e.message}"
        log.debug "문제의 XML 내용: ${cleanedXml}"
    }
}
