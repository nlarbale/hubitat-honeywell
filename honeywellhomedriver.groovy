/*
Hubitat Driver For Honeywell Thermostat

Copyright 2020 - Taylor Brown

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

Major Releases:
11-25-2020 :  Initial 
11-27-2020 :  Alpha Release (0.1)
12-15-2020 :  Beta 1 Release (0.2.0)

*/
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

metadata {
    definition (
        name: "Honeywell Home Thermostat", 
        description:"Driver for Lyric (LCC) and T series (TCC) Honeywell Thermostats, Requires corresponding Honeywell Home App.",
        importUrl:"https://raw.githubusercontent.com/nlarbale/hubitat-honeywell/main/honeywellhomedriver.groovy",
        namespace: "thecloudtaylor", 
        author: "Taylor Brown") {
            capability "Actuator"
            capability "Temperature Measurement"
            capability "Relative Humidity Measurement"
            capability "Thermostat"
            capability "Refresh"

        //Maybe?
		capability "Sensor"
		attribute "thermostatFanState", "enum", ["true", "false"]
		attribute "emergencyHeatActive", "enum", ["unsupported", "true", "false"]
        attribute "autoChangeoverActive", "enum", ["unsupported", "true", "false"]
        attribute "allowedModes", "string"
        attribute "units", "enum", ["F", "C"]
        attribute "heatingRuntimeToday", "number"
        attribute "coolingRuntimeToday", "number"
        attribute "heatingRuntimeYesterday", "number"
        attribute "coolingRuntimeYesterday", "number"
        attribute "lastOperatingStateChange", "string"

    }
    preferences{
        input ("heatModeEnabled", "bool", 
                title: "Allow Heat Mode, if false heat mode will be ignored",
                defaultValue:true)
        input ("coolModeEnabled", "bool", 
                title: "Allow Cool Mode, if false cool mode will be ignored",
                defaultValue:true)
        input ("debugLogs", "bool", 
			   title: "Enable debug logging (turns off after 30 minutes)", 
			   defaultValue: false)
		input ("descriptionText", "bool", 
			   title: "Enable description text logging", 
			   defaultValue: true)
    }
}

void LogDebug(logMessage)
{
    if(debugLogs)
    {
        log.debug "${device.displayName} ${logMessage}";
    }
}

void LogInfo(logMessage)
{
    log.info "${device.displayName} ${logMessage}";
}

void LogDescription(logMessage)
{
    if(descriptionText != false)
    {
        log.info "${device.displayName} ${logMessage}";
    }
}

void LogWarn(logMessage)
{
    log.warn "${device.displayName} ${logMessage}";
}

void disableDebugLog() 
{
    LogWarn("Disabling Debug Logging.");
    device.updateSetting("debugLogs",[value:"false",type:"bool"]);
}

void installed()
{
    LogInfo("Installing.");
    // Persist the defaults; preference defaultValues aren't saved until the user hits Save Preferences.
    device.updateSetting("heatModeEnabled", [value: "true", type: "bool"])
    device.updateSetting("coolModeEnabled", [value: "true", type: "bool"])
    device.updateSetting("debugLogs", [value: "false", type: "bool"])
    device.updateSetting("descriptionText", [value: "true", type: "bool"])
    refresh()
}

void uninstalled()
{
    LogInfo("Uninstalling.");
}

void updated() 
{
    LogInfo("Updating.");
    unschedule("disableDebugLog")
    if (debugLogs)
    {
        runIn(1800, "disableDebugLog")
    }
    refresh()
}

void parse(String message) 
{
    LogDebug("ParseCalled: ${message}");
}

// Supported mode attributes are JSON arrays; older app versions stored a Groovy list string.
private boolean isSupported(String attributeName, String value)
{
    def raw = device.currentValue(attributeName)
    if (raw == null)
    {
        // Not reported yet; let the API decide.
        return true
    }
    try
    {
        return new JsonSlurper().parseText(raw.toString()).contains(value)
    }
    catch (Exception ignored)
    {
        return raw.toString().contains(value)
    }
}

void auto()
{
    LogDebug("auto called");

    if(isSupported("allowedModes", "Auto"))
    {
        setThermostatMode("auto")
    }
    else
    {
        LogWarn("Auto not in the supported modes.")
    }
}

void cool()
{
    LogDebug("cool called");

    setThermostatMode("cool")
}

void emergencyHeat()
{
    LogDebug("emergencyHeat called");

    setThermostatMode("emergency heat")
}

void fanAuto()
{
    LogDebug("fanAuto called");
    setThermostatFanMode("auto")
}

void fanCirculate()
{
    LogDebug("fanCirculate called");
    setThermostatFanMode("circulate")
}

void fanOn()
{
    LogDebug("fanOn called");
    setThermostatFanMode("on")
}

void heat()
{
    LogDebug("heat called");

    setThermostatMode("heat")
}

void off()
{
    LogDebug("off called");

    setThermostatMode("off")
}

//Defined Command : temperature required (NUMBER) - Cooling setpoint in degrees
void setCoolingSetpoint(temperature)
{
    LogDebug("setCoolingSetpoint() - autoChangeoverActive: ${device.currentValue("autoChangeoverActive")}");
    
    //setThermosatSetPoint(com.hubitat.app.DeviceWrapper device, mode=null, autoChangeoverActive=false, emergencyHeatActive=null, heatPoint=null, coolPoint=null)
    if (parent.setThermosatSetPoint(device, null, device.currentValue("autoChangeoverActive"), device.currentValue("emergencyHeatActive"), null, temperature))
    {
        LogDescription("Cooling setpoint changed to ${temperature}")
    }
    else
    {
        LogWarn("Set cooling point to ${temperature} failed.")
    }
}

//Defined Command : temperature required (NUMBER) - Heating setpoint in degrees
void setHeatingSetpoint(temperature)
{
    LogDebug("setHeatingSetpoint() - autoChangeoverActive: ${device.currentValue("autoChangeoverActive")}");

    //setThermosatSetPoint(com.hubitat.app.DeviceWrapper device, mode=null, autoChangeoverActive=false, emergencyHeatActive=null, heatPoint=null, coolPoint=null)
    if (parent.setThermosatSetPoint(device, null, device.currentValue("autoChangeoverActive"), device.currentValue("emergencyHeatActive"), temperature, null))
    {
        LogDescription("Heating setpoint changed to ${temperature}")
    }
    else
    {
        LogWarn("Set heating point to ${temperature} failed.")
    }
}

//Defined Command : JSON_OBJECT (JSON_OBJECT) - JSON_OBJECT
void setSchedule(JSON_OBJECT)
{
    LogDebug("setSchedule called");
}

//Defined Command : fanmode required (ENUM) - Fan mode to set
void setThermostatFanMode(fanmode)
{
    LogDebug("setThermostatFanMode called");

    def mode = fanmode?.toString()?.toLowerCase()
    if(isSupported("supportedThermostatFanModes", mode))
    {
        if(parent.setThermosatFan(device, mode))
        {
            LogDescription("Fan Mode Changed to: ${mode}")
        }
        else
        {
            LogWarn("Set fan mode to ${mode} failed.")
        }
    }
    else
    {
        LogWarn("${fanmode} not in the supported fan modes.")
    }
}

//Defined Command : thermostatmode required (ENUM) - Thermostat mode to set
void setThermostatMode(thermostatmode)
{
    LogDebug("setThermostatMode() - autoChangeoverActive: ${device.currentValue("autoChangeoverActive")}");

    def mode = thermostatmode?.toString()?.toLowerCase()

    // Only an explicit false disables a mode; null means the preference was never saved.
    if ((mode == "heat" || mode == "emergency heat") && heatModeEnabled == false)
    {
        LogInfo("Heating Mode Requested but Not Allowed, Ignoring")
        return
    }

    if (mode == "cool" && coolModeEnabled == false)
    {
        LogInfo("Cooling Mode Requested but Not Allowed, Ignoring")
        return
    }

    //setThermosatSetPoint(com.hubitat.app.DeviceWrapper device, mode=null, autoChangeoverActive=false, emergencyHeatActive=null, heatPoint=null, coolPoint=null)
    if (parent.setThermosatSetPoint(device, mode, device.currentValue("autoChangeoverActive"), device.currentValue("emergencyHeatActive"), null, null))
    {
        LogDescription("mode changed to ${mode}")
    }
    else
    {
        LogWarn("Set mode to ${mode} failed.")
    }
}

void refresh()
{
    LogDebug("Refresh called");
    parent.refreshSingleThermostat(device)
}
