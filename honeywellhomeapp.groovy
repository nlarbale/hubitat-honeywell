/*
Hubitat Driver For Honeywell Thermistate

Copyright 2020 - Taylor Brown

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

Major Releases:
11-25-2020 :  Initial
11-27-2020 :  Alpha Release (0.1)
12-15-2020 :  Beta 1 Release (0.2.0)


Considerable inspiration an example to: https://github.com/dkilgore90/google-sdm-api/
*/


import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field


@Field static String global_apiURL = "https://api.honeywellhome.com"
@Field static String global_redirectURL = "https://cloud.hubitat.com/oauth/stateredirect"

// Don't refresh the OAuth token more than once in this window; concurrent 401s would otherwise race on the refresh token.
@Field static long global_tokenRefreshGuardMs = 60 * 1000
// Back-off used when Honeywell returns 429 without a Retry-After header; doubles (up to the max) if the limit is hit again soon after resuming.
@Field static int global_rateLimitBackoffSec = 15 * 60
@Field static int global_rateLimitMaxBackoffSec = 4 * 60 * 60
// Delay before re-reading thermostats after a command; multiple commands inside this window share one API call.
@Field static int global_commandRefreshDelaySec = 15
// Adaptive polling never waits longer than this, even when stretching to fit the daily budget.
@Field static int global_maxPollIntervalSec = 60 * 60
// Gaps between polls longer than this (outages, rate limit pauses) aren't credited to runtime; the state in between is unknown.
@Field static long global_maxRuntimeGapMs = 65 * 60 * 1000L

definition(
        name: "Honeywell Home",
        namespace: "thecloudtaylor",
        author: "Taylor Brown",
        description: "App for Lyric (LCC) and T series (TCC) Honeywell Thermostats, requires corresponding driver.",
        importUrl:"https://raw.githubusercontent.com/nlarbale/hubitat-honeywell/main/honeywellhomeapp.groovy",
        category: "Thermostate",
        iconUrl: "",
        iconX2Url: "")

preferences
{
    page(name: "mainPage")
    page(name: "debugPage", title: "Debug Options", install: false)
    page(name: "loginPage", title: "Login Options", install: true)
    page(name: "connectToHoneywell")
}

mappings {
    path("/handleAuth") {
        action: [
            GET: "handleAuthRedirect"
        ]
    }
}


def mainPage() {
    migrateTokensToAtomicState()
    dynamicPage(name: "mainPage", title: "Honeywell Home", install: true, uninstall: true) {
        installCheck()
        if(state.appInstalled == 'COMPLETE')
        {
            section {
                paragraph "Establish connection to Honeywell Home and Discover devices"
            }
            if (atomicState.authError)
            {
                section {
                    paragraph "<b>Honeywell authorization problem:</b> ${escapeHtml(atomicState.authError)}"
                }
            }
            if (isRateLimited())
            {
                section {
                    paragraph "<b>Honeywell rate limit hit.</b> Polling and refreshes are paused until ${new Date(atomicState.rateLimitedUntil as Long)}. Thermostat commands still go through."
                }
            }
            section {
                paragraph "Honeywell API calls: ${getApiUsageSummary()}${getAdaptiveStatusSummary()}"
            }
            getDiscoverButton()
            listDiscoveredDevices()
            getRefreshIntervalInputs()

            section {
                href(
                    name       : 'loginHref',
                    title      : 'Login Options',
                    page       : 'loginPage',
                    description: 'Access Login Options'
                )
            }
            section {
                input name: "debugOutput", type: "bool", title: "Enable Debug Logging?", defaultValue: false, submitOnChange: true
            }
            getDebugLink()
        }
    }
}

def installCheck()
{
	state.appInstalled = app.getInstallationState()
	if(state.appInstalled != 'COMPLETE'){
		section{paragraph "Please hit 'Done' to install '${app.label}' app "}
  	}
  	else{
    	LogDebug("Parent Installed OK")
  	}
}



def loginPage()
{
    dynamicPage(name:"loginPage", title: "Honeywell Auth Configuration", install: false, uninstall: false) {
        section {
            paragraph "Please create and input your own Consumer Key and Secret from https://developer.honeywellhome.com/"

            paragraph """Signup is free, once you have a username/password login and then:
                1) Navigate to "My Apps"
                2) Click "Create New App"
                3) Provide an "App Name" can be anything (I use Hubitat)
                4) Input "https://cloud.hubitat.com/oauth/stateredirect" (without quotes) as the callback.
                5) The App will be created, click on it to expand the box and then copy over the Consumer Key and Secret below.
            """

        }
        section {
            input name: "consumerKey", type: "text", title: "Consumer Key", description: "From https://developer.honeywellhome.com/", required: true, submitOnChange: true
            input name: "consumerSecret", type: "password", title: "Consumer Secret", description: "From https://developer.honeywellhome.com/", required: true, submitOnChange: true
            href name: "connectToHoneywell", title: "Connect to Honeywell", page: "connectToHoneywell", description: "Connect to Honeywell"
        }
    }
}

def debugPage() {
    dynamicPage(name:"debugPage", title: "Debug", install: false, uninstall: false) {
        section {
            paragraph "Debug buttons"
        }
        section {
            input 'refreshToken', 'button', title: 'Force Token Refresh', submitOnChange: true
        }
        section {
            input 'deleteDevices', 'button', title: 'Delete all devices', submitOnChange: true
        }
        section {
            input 'refreshDevices', 'button', title: 'Refresh all devices', submitOnChange: true
        }
        section {
            input 'initialize', 'button', title: 'initialize', submitOnChange: true
        }
        section {
            paragraph "Regenerating the Hubitat endpoint token invalidates the OAuth callback; reconnect to Honeywell afterwards."
            input 'createNewAccessToken', 'button', title: 'Regenerate Hubitat Endpoint Token', submitOnChange: true
        }

    }
}

def LogDebug(logMessage)
{
    if(settings?.debugOutput)
    {
        log.debug "${logMessage}";
    }
}

def LogInfo(logMessage)
{
    log.info "${logMessage}";
}

def LogWarn(logMessage)
{
    log.warn "${logMessage}";
}

def LogError(logMessage)
{
    log.error "${logMessage}";
}

def installed()
{
    LogInfo("Installing Honeywell Home.");
    createAccessToken();

}

def initialize()
{
    LogInfo("Initializing Honeywell Home.");
    unschedule()
    migrateTokensToAtomicState()

    if (atomicState.refresh_token == null)
    {
        LogInfo("Not connected to Honeywell yet, skipping refresh and polling.")
        return
    }

    refreshToken()
    RefreshAllDevices()

    if (isAdaptivePolling())
    {
        scheduleNextAdaptivePoll()
        // Restarts the runIn chain if it ever stops (e.g. an exception mid-poll).
        schedule("${(int)(Math.random() * 60)} */30 * ? * *", "adaptiveWatchdog")
        LogInfo("Adaptive thermostat polling enabled.")
    }
    else if (scheduleRefresh("RefreshAllDevices", refreshIntervals ?: "10"))
    {
        LogDebug("Thermostat polling scheduled every ${refreshIntervals ?: '10'} minutes.")
    }
    else
    {
        LogInfo("Thermostat auto refresh disabled.")
    }

    if (scheduleRefresh("RefreshAllRemoteSensors", sensorRefreshInterval ?: "0"))
    {
        LogDebug("Remote sensor polling scheduled every ${sensorRefreshInterval} minutes.")
    }
    else
    {
        LogInfo("Remote sensor auto refresh disabled.")
    }
}

def updated()
{
    LogDebug("Updated with settings: ${settings.keySet()}");
    initialize();
}

def uninstalled()
{
    LogInfo("Uninstalling Honeywell Home.");
    unschedule()
    for (device in getChildDevices())
    {
        deleteChildDevice(device.deviceNetworkId)
    }
}

// Schedules handler every intervalMinutes ("0"/null disables). A random second/minute offset keeps polls off the top of the minute/hour.
def scheduleRefresh(String handler, interval)
{
    def minutes = (interval ?: "0").toString() as Integer
    if (minutes <= 0)
    {
        return false
    }

    def second = (int)(Math.random() * 60)
    def cronString
    if (minutes >= 55)
    {
        cronString = "${second} ${(int)(Math.random() * 60)} * ? * *"
    }
    else
    {
        cronString = "${second} */${minutes} * ? * *"
    }
    LogDebug("Scheduling ${handler} cronstring: ${cronString}")
    schedule(cronString, handler)
    return true
}

def connectToHoneywell()
{
    LogDebug("connectToHoneywell()");

    if (!settings.consumerKey || !settings.consumerSecret)
    {
        return dynamicPage(name: "mainPage", title: "Honeywell Home", install: false, uninstall: false) {
            section {
                paragraph "Enter your Consumer Key and Consumer Secret on the Login Options page first."
            }
        }
    }

    //if this isn't defined early then the redirect fails for some reason...
    def redirectLocation = "http://www.bing.com";
    if (state.accessToken == null)
    {
        createAccessToken();
    }
    def auth_state = java.net.URLEncoder.encode("${getHubUID()}/apps/${app.id}/handleAuth?access_token=${state.accessToken}", "UTF-8")
    def escapedRedirectURL = java.net.URLEncoder.encode(global_redirectURL, "UTF-8")
    def escapedClientId = java.net.URLEncoder.encode(settings.consumerKey, "UTF-8")
    def authQueryString = "response_type=code&redirect_uri=${escapedRedirectURL}&client_id=${escapedClientId}&state=${auth_state}";

    def params = [
        uri: global_apiURL,
        path: "/oauth2/authorize",
        queryString: authQueryString.toString()
    ]
    try {
        httpPost(params) { response ->
            if (response.status == 302)
            {
                LogDebug("Response 302, getting redirect")
                redirectLocation = response.headers.'Location'
            }
            else
            {
                LogError("Auth request Returned Invalid HTTP Response: ${response.status}")
                return false;
            }
        }
    }
    catch (groovyx.net.http.HttpResponseException e)
    {
        LogError("API Auth failed -- ${e.getLocalizedMessage()}: ${e.response?.data}")
        return false;
    }
    catch (Exception e)
    {
        LogError("API Auth failed -- ${e.getMessage()}")
        return false;
    }

    dynamicPage(name: "mainPage", title: "Honeywell Home", install: true, uninstall: true) {
        section
        {
            paragraph "Click below to be redirected to Honeywell to authorize Hubitat access."
            href(
                name       : 'authHref',
                title      : 'Establish OAuth Link with Honeywell',
                url        : redirectLocation,
                description: ''
            )
        }
    }
}

def getDiscoverButton()
{
    if (atomicState.access_token == null)
    {
        section
        {
            paragraph "Device discovery and configuration is hidden until authorization is completed."
        }
    }
    else
    {
        section
        {
            input 'discoverDevices', 'button', title: 'Discover', submitOnChange: true
        }
    }
}

def getDebugLink() {
    section{
        href(
            name       : 'debugHref',
            title      : 'Debug buttons',
            page       : 'debugPage',
            description: 'Access debug buttons (force Token refresh, delete child devices , refresh devices)'
        )
    }
}

def listDiscoveredDevices() {
    def children = getChildDevices()
    def builder = new StringBuilder()
    builder << "<ul>"
    children.each {
        if (it != null) {
            builder << "<li><a href='/device/edit/${it.getId()}'>${escapeHtml(it.getLabel())}</a></li>"
        }
    }
    builder << "</ul>"
    def links = builder.toString()
    if (!children.isEmpty())
    {
        section {
            paragraph "Discovered devices are listed below:"
            paragraph links
        }
    }
}

def getRefreshIntervalInputs()
{
    section {
        paragraph "Refresh intervals (how often devices are automatically polled). Every poll counts against your Honeywell API key's rate limit."
        paragraph "Thermostats: one API call per poll covers every thermostat on the account."
        input name: "thermostatPollingMode", type: "enum", title: "Thermostat polling", options: ["fixed":"Fixed interval", "adaptive":"Adaptive (fast while heating/cooling or near setpoint)"], required: true, defaultValue: "fixed", submitOnChange: true
    }
    if (isAdaptivePolling())
    {
        section {
            paragraph "Adaptive polling uses the fast interval while any thermostat is heating or cooling, or its temperature is within the near-setpoint range, and the idle interval otherwise. With a daily budget, intervals stretch automatically so the day's calls stay under it (token refreshes, remote sensor polls and a small reserve for commands are accounted for)."
            input name: "fastPollMinutes", type: "enum", title: "Fast interval", options: ["1":"1 minute", "2":"2 minutes", "3":"3 minutes", "5":"5 minutes"], required: true, defaultValue: "1", submitOnChange: true
            input name: "idlePollMinutes", type: "enum", title: "Idle interval", options: ["5":"5 minutes", "10":"10 minutes", "15":"15 minutes", "30":"30 minutes", "60":"60 minutes"], required: true, defaultValue: "15", submitOnChange: true
            input name: "nearSetpointDegrees", type: "decimal", title: "Near-setpoint range (degrees, in the thermostat's units)", required: true, defaultValue: 1.0, submitOnChange: true
            input name: "dailyCallBudget", type: "number", title: "Daily API call budget (0 = no budget)", required: true, defaultValue: 0, submitOnChange: true
        }
    }
    section {
        input name: "refreshIntervals", type: "enum", title: isAdaptivePolling() ? "Fixed interval (not used while adaptive polling is on)" : "Thermostat refresh interval", options: ["0":"off", "1":"1 minute (will exhaust the daily quota)", "2":"2 minutes (likely to exhaust the quota)", "5":"5 minutes", "10":"10 minutes", "15":"15 minutes", "30":"30 minutes", "60":"60 minutes"], required: true, defaultValue: "10", submitOnChange: true
        paragraph "Remote sensors: one API call per poll for each thermostat that has enabled sensors. Disable individual sensor devices to stop polling them."
        input name: "sensorRefreshInterval", type: "enum", title: "Remote sensor refresh interval", options: ["0":"off", "5":"5 minutes", "10":"10 minutes", "15":"15 minutes", "30":"30 minutes", "60":"60 minutes"], required: true, defaultValue: "0", submitOnChange: true
    }
}

def escapeHtml(value)
{
    if (value == null)
    {
        return ""
    }
    return value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}



def appButtonHandler(btn) {
    switch (btn) {
    case 'discoverDevices':
        discoverDevices()
        break
    case 'refreshToken':
        refreshToken(true)
        break
    case 'deleteDevices':
        deleteDevices()
        break
    case 'refreshDevices':
        RefreshAllDevices()
        RefreshAllRemoteSensors(true)
        break
    case 'initialize':
        initialize()
        break
    case 'createNewAccessToken':
        state.accessToken = null
        createAccessToken()
        LogWarn("Hubitat endpoint token regenerated; reconnect to Honeywell from Login Options.")
        break
    case 'connectToHoneywell':
        connectToHoneywell()
        break
    default:
        LogError("Invalid Button In Handler")
    }
}

def deleteDevices()
{
    def children = getChildDevices()
    LogInfo("Deleting all child devices: ${children}")
    children.each {
        if (it != null) {
            deleteChildDevice it.getDeviceNetworkId()
        }
    }
}

// Thermostat DNIs are "<locationId> - <deviceId>". Remote sensor DNIs use "-" without spaces and return null here.
def parseThermostatDni(String dni)
{
    def delimiter = dni?.indexOf(' - ')
    if (delimiter == null || delimiter < 0)
    {
        return null
    }
    return [locationId: dni.substring(0, delimiter), deviceId: dni.substring(delimiter + 3)]
}

// Sensors created while room names couldn't be fetched (e.g. rate limited) are labelled "... Thermostat Sensor: Room <id>".
// Replace that placeholder with the real room name; labels the user changed are left alone.
def fixPlaceholderSensorLabel(com.hubitat.app.DeviceWrapper sensor, roomId, roomName)
{
    def label = sensor.getLabel()
    def placeholderSuffix = " Thermostat Sensor: Room ${roomId}".toString()
    if (roomName && label?.endsWith(placeholderSuffix) && roomName.toString() != "Room ${roomId}".toString())
    {
        def newLabel = label.substring(0, label.length() - placeholderSuffix.length()) + " Thermostat Sensor: ${roomName}"
        LogInfo("Renaming sensor '${label}' to '${newLabel}'")
        sensor.setLabel(newLabel)
    }
}

def isRemoteSensor(com.hubitat.app.DeviceWrapper device)
{
    return device.hasAttribute("roomId") && device.hasAttribute("parentDeviceId")
}

//
// Honeywell API access
//

// Older versions kept the Honeywell tokens in state; copy them over so upgrades stay connected.
def migrateTokensToAtomicState()
{
    if (atomicState.access_token == null && state.access_token != null)
    {
        atomicState.access_token = state.access_token
    }
    if (atomicState.refresh_token == null && state.refresh_token != null)
    {
        atomicState.refresh_token = state.refresh_token
    }
}

def isRateLimited()
{
    def until = atomicState.rateLimitedUntil
    return until != null && now() < (until as Long)
}

// Counts calls per clock hour and per day so the app page and rate limit warnings show actual usage.
def countApiCall()
{
    def date = new Date(now())
    def hourKey = date.format("yyyy-MM-dd HH")
    def dayKey = date.format("yyyy-MM-dd")
    def usage = atomicState.apiUsage ?: [:]
    if (usage.day != dayKey)
    {
        usage.day = dayKey
        usage.dayCount = 0
    }
    if (usage.hour != hourKey)
    {
        usage.hour = hourKey
        usage.hourCount = 0
    }
    usage.dayCount = (usage.dayCount ?: 0) + 1
    usage.hourCount = (usage.hourCount ?: 0) + 1
    atomicState.apiUsage = usage
}

def getApiUsageSummary()
{
    def usage = atomicState.apiUsage
    def date = new Date(now())
    def today = (usage?.day == date.format("yyyy-MM-dd")) ? usage.dayCount : 0
    def thisHour = (usage?.hour == date.format("yyyy-MM-dd HH")) ? usage.hourCount : 0
    return "${today} today, ${thisHour} this hour"
}

def noteRateLimited(groovyx.net.http.HttpResponseException e)
{
    // Hit again within an hour of the last pause ending: the limit window is longer than our back-off, so double it.
    def lastUntil = atomicState.rateLimitedUntil
    def lastBackoff = atomicState.rateLimitBackoffSec ?: global_rateLimitBackoffSec
    def backoffSec = global_rateLimitBackoffSec
    if (lastUntil != null && now() - (lastUntil as Long) < 60 * 60 * 1000L)
    {
        backoffSec = Math.min(lastBackoff * 2, global_rateLimitMaxBackoffSec)
    }

    try
    {
        def retryAfter = e.response?.getFirstHeader("Retry-After")?.getValue()
        if (retryAfter != null && retryAfter.toString().isInteger())
        {
            backoffSec = Math.max(60, retryAfter.toString() as Integer)
        }
    }
    catch (Exception ignored)
    {
        // no usable Retry-After header
    }
    atomicState.rateLimitBackoffSec = backoffSec
    atomicState.rateLimitedUntil = now() + (backoffSec * 1000L)
    LogWarn("Honeywell API rate limit hit (calls: ${getApiUsageSummary()}); pausing polling and refreshes for ${backoffSec / 60} minutes. Honeywell said: ${e.response?.data}")
}

// Scheduled polls back off while rate limited or while authorization is known to be broken. User commands still go through.
def skipScheduledPoll()
{
    if (atomicState.authError)
    {
        LogWarn("Skipping poll: ${atomicState.authError}")
        return true
    }
    if (isRateLimited())
    {
        LogDebug("Skipping poll: rate limited until ${new Date(atomicState.rateLimitedUntil as Long)}")
        return true
    }
    return false
}

// Single entry point for authenticated API calls. Handles one token refresh + retry on 401, rate limit back-off, and never logs credentials.
// Returns [ok: Boolean, status: Integer, data: parsed JSON].
def apiRequest(String method, String path, Map query = [:], Map body = null, boolean isRetry = false)
{
    def result = [ok: false, status: null, data: null]

    def tokenUsed = atomicState.access_token
    if (tokenUsed == null)
    {
        LogError("Not connected to Honeywell; open the app and use Login Options to connect.")
        return result
    }

    // While rate limited, reads (polls, manual refreshes, discovery) would only extend the penalty; commands still go through.
    if (method != "POST" && isRateLimited())
    {
        LogWarn("Skipping ${path}: Honeywell rate limit, retry after ${new Date(atomicState.rateLimitedUntil as Long)}")
        result.status = 429
        return result
    }

    countApiCall()

    def params = [
        uri: global_apiURL,
        path: path,
        query: [apikey: settings.consumerKey] + (query ?: [:]),
        headers: [Authorization: "Bearer ${tokenUsed}"],
        contentType: "application/json",
        timeout: 30
    ]
    if (body != null)
    {
        params.body = body
    }
    LogDebug("API ${method} ${path} query: ${query} body: ${body}")

    try
    {
        def handler = { response ->
            result.status = response.getStatus()
            result.data = response.getData()
        }
        if (method == "POST")
        {
            httpPostJson(params, handler)
        }
        else
        {
            httpGet(params, handler)
        }
        result.ok = true
        // Responses can be several KB and include account details (name, email); log only a short preview.
        def preview = result.data?.toString() ?: ""
        LogDebug("API ${method} ${path} status: ${result.status} data (${preview.length()} chars): ${preview.take(200)}")
    }
    catch (groovyx.net.http.HttpResponseException e)
    {
        result.status = e.getStatusCode()
        if (result.status == 401 && !isRetry)
        {
            // Another execution may already have rotated the token; only refresh if ours is still current.
            if (atomicState.access_token == tokenUsed)
            {
                LogWarn('Authorization token expired, will refresh and retry.')
                refreshToken()
            }
            return apiRequest(method, path, query, body, true)
        }
        if (result.status == 429)
        {
            noteRateLimited(e)
        }
        else
        {
            LogError("Honeywell API ${method} ${path} failed (${result.status}): ${e.response?.data}")
        }
    }
    catch (Exception e)
    {
        LogError("Honeywell API ${method} ${path} failed: ${e.getMessage()}")
    }

    return result
}

def discoverDevices()
{
    LogDebug("discoverDevices()");

    def result = apiRequest("GET", "/v2/locations")
    if (!result.ok)
    {
        LogError("Location discovery failed.")
        return
    }

    def addedThermostats = false
    def addedSensors = false
    result.data?.each {locations ->
        def locationID = locations.locationID.toString()
        LogDebug("LocationID: ${locationID}");
        locations.devices?.each {dev ->
            LogDebug("DeviceID: ${dev.deviceID.toString()}")
            LogDebug("DeviceModel: ${dev.deviceModel.toString()}")
            def thermoNetId = "${locationID} - ${dev.deviceID.toString()}"
            if (dev.deviceClass == "Thermostat") {
                try {
                    addChildDevice(
                            'thecloudtaylor',
                            'Honeywell Home Thermostat',
                            thermoNetId,
                            [
                                    name : "Honeywell - ${dev.deviceModel.toString()} - ${dev.deviceID.toString()}",
                                    label: dev.userDefinedDeviceName.toString()
                            ])
                    addedThermostats = true
                 }
                catch (com.hubitat.app.exception.UnknownDeviceTypeException e) {
                    LogInfo("${e.message} - you need to install the appropriate driver.")
                    return
                }
                catch (IllegalArgumentException ignored) {
                    //Intentionally ignored.  Expected if device id already exists in HE.
                }
                //Check this thermostat for remote sensors
                if (dev.groups != null) {
                    LogDebug("Checking Thermostat ${dev.deviceID.toString()} for remote sensors")
                    dev.groups.each { group ->
                        LogDebug("Group ID: ${group.id.toString()}")
                        def sensorRooms = group.rooms?.findAll { it != 0 }  // room 0 is the thermostat itself
                        if (!sensorRooms)
                        {
                            return
                        }
                        // One call per group for all room names, rather than one per sensor.
                        def roomNames = getRoomNames(dev.deviceID.toString(), locationID, group.id.toString())
                        sensorRooms.each { room ->
                            LogDebug("Room No.: ${room.toString()}")
                            def roomName = roomNames[room as Integer] ?: "Room ${room}"
                            def sensorNetId = "${locationID}-${dev.deviceID.toString()}-${group.id.toString()}-${room.toString()}".toString()
                            def sensor = getChildDevice(sensorNetId)
                            if (sensor == null)
                            {
                                try
                                {
                                    sensor = addChildDevice(
                                        'thecloudtaylor',
                                        'Honeywell Home Remote Sensor',
                                        sensorNetId,
                                        [
                                                name : "Honeywell Home Remote Sensor",
                                                label: "${dev.userDefinedDeviceName} Thermostat Sensor: ${roomName}"
                                        ])
                                    addedSensors = true
                                }
                                catch (com.hubitat.app.exception.UnknownDeviceTypeException e)
                                {
                                    LogInfo("${e.message} - you need to install the appropriate driver.")
                                    return
                                }
                            }
                            else
                            {
                                fixPlaceholderSensorLabel(sensor, room, roomNames[room as Integer])
                            }

                            // (Re)apply identifiers so rediscovery repairs existing sensors too.
                            sendEvent(sensor, [name: "groupId", value: group.id])
                            sendEvent(sensor, [name: "roomId", value: room])
                            sendEvent(sensor, [name: "parentDeviceId", value: dev.deviceID.toString()])
                            sendEvent(sensor, [name: "parentDeviceNetId", value: thermoNetId])
                            sendEvent(sensor, [name: "locationId", value: locationID])
                        }
                    }
                }
            }
        }
    }

    // New devices don't refresh themselves; populate them all with one call (plus one per thermostat with new sensors).
    if (addedThermostats)
    {
        RefreshAllDevicesUnified()
    }
    if (addedSensors)
    {
        RefreshAllRemoteSensors(true)
    }
}

def handleAuthRedirect()
{
    LogDebug("handleAuthRedirect()");

    def authCode = params.code
    def connected = false

    if (authCode)
    {
        def body = [
                        grant_type:"authorization_code",
                        code:authCode,
                        redirect_uri:global_redirectURL
        ]
        connected = requestToken(body).ok
    }
    else
    {
        LogError("Honeywell authorization redirect did not include a code: ${params.error ?: 'unknown error'}")
    }

    def stringBuilder = new StringBuilder()
    stringBuilder << "<!DOCTYPE html><html><head><title>Honeywell Connected to Hubitat</title></head><body>"
    if (connected)
    {
        stringBuilder << "<p>Hubitat and Honeywell are now connected.</p>"
    }
    else
    {
        stringBuilder << "<p>Connecting Hubitat to Honeywell failed. Check the Hubitat logs, verify your Consumer Key and Secret, and try again.</p>"
    }
    stringBuilder << "<p><a href=http://${location.hub.localIP}/installedapp/configure/${app.id}/mainPage>Click here</a> to return to the App main page.</p></body></html>"

    def html = stringBuilder.toString()

    render contentType: "text/html", data: html, status: 200
}

// Posts to the token endpoint; returns [ok: Boolean, status: Integer].
def requestToken(Map body)
{
    def authorization = "Basic " + ("${settings.consumerKey}:${settings.consumerSecret}").bytes.encodeBase64().toString()

    def headers = [
                    Authorization: authorization,
                    Accept: "application/json"
                ]
    def params = [uri: global_apiURL, path: "/oauth2/token", headers: headers, body: body, timeout: 30]
    countApiCall()

    try
    {
        def ok = false
        httpPost(params) { response -> ok = loginResponse(response) }
        return [ok: ok, status: 200]
    }
    catch (groovyx.net.http.HttpResponseException e)
    {
        LogError("Honeywell token request failed -- ${e.getLocalizedMessage()}: ${e.response?.data}")
        return [ok: false, status: e.getStatusCode()]
    }
    catch (Exception e)
    {
        LogError("Honeywell token request failed -- ${e.getMessage()}")
        return [ok: false, status: null]
    }
}

def refreshToken(force=false)
{
    LogDebug("refreshToken()");

    def currentRefreshToken = atomicState.refresh_token
    if (currentRefreshToken == null)
    {
        LogError("Failed to refresh token, refresh token null. Connect to Honeywell from Login Options.")
        return false
    }

    def lastRefresh = atomicState.tokenRefreshedAt
    if (!force && lastRefresh != null && (now() - (lastRefresh as Long)) < global_tokenRefreshGuardMs)
    {
        LogDebug("Token refreshed recently, skipping.")
        return true
    }

    def body = [
                    grant_type:"refresh_token",
                    refresh_token:currentRefreshToken
    ]
    def result = requestToken(body)
    if (!result.ok)
    {
        if (result.status == 400 || result.status == 401)
        {
            atomicState.authError = "Honeywell rejected the refresh token. Reconnect via Login Options > Connect to Honeywell."
            LogError(atomicState.authError)
        }
        else
        {
            LogWarn("Token refresh failed, retrying in 5 minutes.")
            runIn(300, "refreshToken")
        }
    }
    return result.ok
}

def loginResponse(response)
{
    LogDebug("loginResponse()");

    def reCode = response.getStatus();
    def reJson = response.getData();
    LogDebug("reCode: ${reCode}")

    if (reCode == 200 && reJson?.access_token)
    {
        atomicState.access_token = reJson.access_token;
        if (reJson.refresh_token)
        {
            atomicState.refresh_token = reJson.refresh_token;
        }
        atomicState.tokenRefreshedAt = now()
        atomicState.authError = null

        def expiresIn = (reJson.expires_in ?: 1799).toString() as Integer
        def expireTime = Math.max(expiresIn - 100, 60)
        LogInfo("Honeywell API Token Refreshed Succesfully, Next Scheduled in: ${expireTime} sec")
        runIn(expireTime, "refreshToken")
        return true
    }

    LogError("LoginResponse Failed HTTP Request Status: ${reCode}");
    return false
}

//
// Refresh
//

// One /v2/locations call returns every thermostat on the account, so this costs a single API call regardless of thermostat count.
def RefreshAllDevicesUnified()
{
    LogDebug("RefreshAllDevicesUnified()");

    def thermostats = [:]
    getChildDevices().each { child ->
        def ids = parseThermostatDni(child.getDeviceNetworkId())
        if (ids != null)
        {
            thermostats[ids.deviceId] = child
        }
    }
    if (thermostats.isEmpty())
    {
        LogDebug("No thermostats to refresh.")
        return
    }

    def result = apiRequest("GET", "/v2/locations")
    if (!result.ok)
    {
        return
    }

    result.data?.each { locationJson ->
        locationJson?.devices?.each { deviceJson ->
            if (deviceJson == null || (deviceJson.deviceClass != "Thermostat" && deviceJson.deviceType != "Thermostat"))
            {
                LogDebug("Non thermostat device ${deviceJson?.deviceClass} - skipping")
                return
            }

            def currentDevice = thermostats[deviceJson.deviceID?.toString()]
            if (currentDevice == null || currentDevice.isDisabled())
            {
                return
            }

            try
            {
                LogDebug("Refreshing ${currentDevice.getLabel()}")
                refreshThermostatNew(currentDevice, deviceJson)
            }
            catch (Exception e)
            {
                LogError("Failed to refresh ${currentDevice.getLabel()}: ${e.getMessage()}")
            }
        }
    }
}

def RefreshAllDevices()
{
    LogDebug("RefreshAllDevices()");

    if (skipScheduledPoll())
    {
        return
    }
    RefreshAllDevicesUnified()
}

//
// Adaptive polling: a runIn chain whose next delay depends on what the thermostats are doing.
//

def isAdaptivePolling()
{
    return settings.thermostatPollingMode == "adaptive"
}

def adaptivePoll()
{
    LogDebug("adaptivePoll()")
    try
    {
        RefreshAllDevices()
    }
    finally
    {
        scheduleNextAdaptivePoll()
    }
}

def adaptiveWatchdog()
{
    def nextAt = atomicState.nextAdaptivePollAt
    if (isAdaptivePolling() && (nextAt == null || now() > (nextAt as Long) + 5 * 60 * 1000L))
    {
        LogWarn("Adaptive polling had stopped; restarting.")
        scheduleNextAdaptivePoll()
    }
}

def scheduleNextAdaptivePoll()
{
    def plan = computeAdaptivePlan()
    atomicState.nextAdaptivePollAt = now() + plan.seconds * 1000L
    atomicState.adaptiveReason = plan.reason
    LogDebug("Next adaptive poll in ${plan.seconds}s (${plan.reason})")
    runIn(plan.seconds, "adaptivePoll")
}

// Returns [seconds: Integer, reason: String].
def computeAdaptivePlan()
{
    def fastSec = ((settings.fastPollMinutes ?: "1").toString() as Integer) * 60
    def idleSec = ((settings.idlePollMinutes ?: "15").toString() as Integer) * 60

    if (isRateLimited())
    {
        def waitSec = (int)(((atomicState.rateLimitedUntil as Long) - now()) / 1000) + 30
        return [seconds: Math.max(waitSec, 60), reason: "rate limited"]
    }
    if (atomicState.authError)
    {
        return [seconds: idleSec, reason: "authorization error"]
    }

    def activity = getThermostatActivity()
    def seconds = activity ? fastSec : idleSec
    def reason = activity ?: "idle"

    def budgetSec = getBudgetMinimumIntervalSec()
    if (budgetSec != null && budgetSec > seconds)
    {
        seconds = budgetSec
        reason = "${reason}, stretched for daily budget"
    }
    return [seconds: Math.min(seconds, global_maxPollIntervalSec), reason: reason]
}

// Describes why fast polling is needed ("Hallway heating", "Hallway near setpoint"), or null when everything is idle.
def getThermostatActivity()
{
    def near = (settings.nearSetpointDegrees != null ? settings.nearSetpointDegrees : 1.0) as BigDecimal
    for (thermostat in getChildDevices().findAll { parseThermostatDni(it.getDeviceNetworkId()) != null && !it.isDisabled() })
    {
        def operating = thermostat.currentValue("thermostatOperatingState")
        if (operating == "heating" || operating == "cooling")
        {
            return "${thermostat.getLabel()} ${operating}"
        }

        def mode = thermostat.currentValue("thermostatMode")
        def temperature = thermostat.currentValue("temperature")
        if (temperature == null || !temperature.toString().isNumber())
        {
            continue
        }
        temperature = temperature as BigDecimal
        def heatSetpoint = thermostat.currentValue("heatingSetpoint")
        def coolSetpoint = thermostat.currentValue("coolingSetpoint")
        def heats = mode in ["heat", "emergency heat", "auto"]
        def cools = mode in ["cool", "auto"]
        if ((heats && heatSetpoint != null && temperature <= (heatSetpoint as BigDecimal) + near) ||
            (cools && coolSetpoint != null && temperature >= (coolSetpoint as BigDecimal) - near))
        {
            return "${thermostat.getLabel()} near setpoint"
        }
    }
    return null
}

// Shortest poll interval that keeps the rest of today's calls under dailyCallBudget, or null without a budget.
def getBudgetMinimumIntervalSec()
{
    def budget = (settings.dailyCallBudget ?: 0) as Integer
    if (budget <= 0)
    {
        return null
    }

    def nowMs = now()
    def endOfDay = (new Date(nowMs).clearTime() + 1).getTime()
    long remainingSec = Math.max((long)((endOfDay - nowMs) / 1000), 60L)

    def usage = atomicState.apiUsage
    def usedToday = (usage?.day == new Date(nowMs).format("yyyy-MM-dd")) ? (usage.dayCount ?: 0) : 0

    // Calls that will happen regardless of thermostat polling.
    def tokenCalls = remainingSec / 1800 + 1
    def sensorMinutes = (settings.sensorRefreshInterval ?: "0").toString() as Integer
    def sensorCalls = sensorMinutes > 0 ? (remainingSec / (sensorMinutes * 60)) * getRemoteSensorsByThermostat().count { k, v -> v.any { !it.isDisabled() } } : 0
    def commandReserve = budget * 0.05

    def available = budget - usedToday - tokenCalls - sensorCalls - commandReserve
    if (available < 1)
    {
        return global_maxPollIntervalSec
    }
    return (int)Math.ceil((remainingSec / available) as double)
}

def getAdaptiveStatusSummary()
{
    if (!isAdaptivePolling() || atomicState.nextAdaptivePollAt == null)
    {
        return ""
    }
    def seconds = Math.max(0, (int)(((atomicState.nextAdaptivePollAt as Long) - now()) / 1000))
    return "<br>Adaptive polling: next poll in ${seconds}s (${escapeHtml(atomicState.adaptiveReason)})"
}

//
// Runtime tracking: heating/cooling time accumulated between polls. A state change is assumed to have happened halfway
// between the two polls that saw it, which halves the timing error compared to crediting it at either poll.
//

def updateRuntime(com.hubitat.app.DeviceWrapper device, String operatingState)
{
    def nowMs = now()
    def dni = device.getDeviceNetworkId()
    def runtimes = atomicState.runtimes ?: [:]
    def rec = runtimes[dni] ?: [state: operatingState, lastPoll: nowMs, day: new Date(nowMs).format("yyyy-MM-dd"), heatSec: 0, coolSec: 0, yesterdayHeatSec: 0, yesterdayCoolSec: 0]

    def lastPoll = rec.lastPoll as Long
    def gap = nowMs - lastPoll
    if (gap > 0 && gap <= global_maxRuntimeGapMs)
    {
        if (rec.state == operatingState)
        {
            creditRuntime(rec, rec.state, lastPoll, nowMs)
        }
        else
        {
            def changedAt = lastPoll + (long)(gap / 2)
            creditRuntime(rec, rec.state, lastPoll, changedAt)
            creditRuntime(rec, operatingState, changedAt, nowMs)
            sendEvent(device, [name: "lastOperatingStateChange", value: new Date(changedAt).format("yyyy-MM-dd'T'HH:mm:ssZ"), descriptionText: "${rec.state} -> ${operatingState} at about ${new Date(changedAt).format('HH:mm')}"])
        }
    }
    else if (rec.state != operatingState)
    {
        // Change seen after a gap too long to estimate; record that it happened by now.
        sendEvent(device, [name: "lastOperatingStateChange", value: new Date(nowMs).format("yyyy-MM-dd'T'HH:mm:ssZ"), descriptionText: "${rec.state} -> ${operatingState} (time unknown, seen after a polling gap)"])
    }
    rollRuntimeDay(rec, new Date(nowMs).format("yyyy-MM-dd"))

    rec.state = operatingState
    rec.lastPoll = nowMs
    runtimes[dni] = rec
    atomicState.runtimes = runtimes

    sendEvent(device, [name: "heatingRuntimeToday", value: (int)((rec.heatSec as Long) / 60), unit: "min"])
    sendEvent(device, [name: "coolingRuntimeToday", value: (int)((rec.coolSec as Long) / 60), unit: "min"])
    sendEvent(device, [name: "heatingRuntimeYesterday", value: (int)((rec.yesterdayHeatSec as Long) / 60), unit: "min"])
    sendEvent(device, [name: "coolingRuntimeYesterday", value: (int)((rec.yesterdayCoolSec as Long) / 60), unit: "min"])
}

// Adds [fromMs, toMs) of the given state to the record, splitting at midnight so each day gets its own share.
def creditRuntime(Map rec, String state, long fromMs, long toMs)
{
    def from = fromMs
    while (from < toMs)
    {
        def dayStart = new Date(from).clearTime()
        def segmentEnd = Math.min(toMs, (dayStart + 1).getTime())
        rollRuntimeDay(rec, dayStart.format("yyyy-MM-dd"))
        def seconds = (long)((segmentEnd - from) / 1000)
        if (state == "heating")
        {
            rec.heatSec = (rec.heatSec as Long) + seconds
        }
        else if (state == "cooling")
        {
            rec.coolSec = (rec.coolSec as Long) + seconds
        }
        from = segmentEnd
    }
}

def rollRuntimeDay(Map rec, String dayKey)
{
    if (rec.day == dayKey)
    {
        return
    }
    def yesterdayKey = (Date.parse("yyyy-MM-dd", dayKey) - 1).format("yyyy-MM-dd")
    rec.yesterdayHeatSec = (rec.day == yesterdayKey) ? rec.heatSec : 0
    rec.yesterdayCoolSec = (rec.day == yesterdayKey) ? rec.coolSec : 0
    rec.heatSec = 0
    rec.coolSec = 0
    rec.day = dayKey
}

// Deferred, coalesced refresh after a command so the cloud has time to reflect the change.
def refreshAfterCommand()
{
    LogDebug("refreshAfterCommand()")
    if (isRateLimited())
    {
        return
    }
    RefreshAllDevicesUnified()
    if (isAdaptivePolling())
    {
        // A command may have started heating/cooling; re-plan so fast polling starts now rather than after an idle wait.
        scheduleNextAdaptivePoll()
    }
}

def scheduleCommandRefresh()
{
    runIn(global_commandRefreshDelaySec, "refreshAfterCommand")
}

def refreshHelper(jsonString, cloudString, deviceString, com.hubitat.app.DeviceWrapper device, optionalUnits=null, optionalMakeLowerMap=false, optionalMakeLowerString=false)
{
    try
    {
        LogDebug("refreshHelper() cloudString:${cloudString} - deviceString:${deviceString} - device:${device} - optionalUnits:${optionalUnits} - optionalMakeLowerMap:${optionalMakeLowerMap} -optionalMakeLowerString:${optionalMakeLowerString}")

        def value = jsonString.get(cloudString)
        LogDebug("updateThermostats-${cloudString}: ${value}")

        if (value == null)
        {
            LogDebug("Thermostate Does not Support: ${deviceString} (${cloudString})")
            return false;
        }
        if (optionalMakeLowerMap)
        {
            def lowerCaseValues = []
            value.each {m -> lowerCaseValues.add(m.toLowerCase())}
            value = lowerCaseValues
        }
        if (optionalMakeLowerString)
        {
            value = value.toLowerCase()
        }
        if (optionalUnits != null)
        {
            sendEvent(device, [name: deviceString, value: value, unit: optionalUnits])
        }
        else
        {
            sendEvent(device, [name: deviceString, value: value])
        }
    }
    catch (java.lang.NullPointerException e)
    {
        LogDebug("Thermostate Does not Support: ${deviceString} (${cloudString})")
        return false;
    }

    return true;
}

// when refreshing a single device
def refreshSingleThermostat(com.hubitat.app.DeviceWrapper device)
{
    LogDebug("refreshSingleThermostat()")

    def ids = parseThermostatDni(device.getDeviceNetworkId())
    if (ids == null)
    {
        LogError("Unexpected thermostat network id: ${device.getDeviceNetworkId()}")
        return
    }

    def result = apiRequest("GET", "/v2/devices/thermostats/${ids.deviceId}", [locationId: ids.locationId])
    if (result.ok)
    {
        refreshThermostatNew(device, result.data)
    }
}

// Kept for thermostat drivers that still call the pre-refactor name.
def refreshThermosat(com.hubitat.app.DeviceWrapper device)
{
    refreshSingleThermostat(device)
}

// Honeywell mode ("EmergencyHeat", "Heat", ...) -> Hubitat thermostatMode ("emergency heat", "heat", ...)
def toHubitatMode(honeywellMode)
{
    if (honeywellMode == null)
    {
        return null
    }
    def mode = honeywellMode.toString().toLowerCase()
    return mode == "emergencyheat" ? "emergency heat" : mode
}

def toBool(value)
{
    return value == true || value?.toString()?.toLowerCase() == "true"
}

def updateDataValueIfChanged(com.hubitat.app.DeviceWrapper device, String name, value)
{
    if (value != null && device.getDataValue(name) != value.toString())
    {
        device.updateDataValue(name, value.toString())
    }
}

def refreshThermostatNew(com.hubitat.app.DeviceWrapper device, def reJson)
{
    LogDebug("refreshThermostatNew()")

    def tempUnits = "F"
    if (reJson.units != "Fahrenheit")
    {
        tempUnits = "C"
    }
    sendEvent(device, [name: "units", value: tempUnits])
    LogDebug("updateThermostats-tempUnits: ${tempUnits}")

    refreshHelper(reJson, "indoorTemperature", "temperature", device, tempUnits, false, false)
    refreshHelper(reJson, "indoorHumidity", "humidity", device, "%", false, false)

    // Hubitat 2.3.3+ expects supported modes as JSON arrays.
    if (reJson.allowedModes != null)
    {
        sendEvent(device, [name: "allowedModes", value: JsonOutput.toJson(reJson.allowedModes)])
        sendEvent(device, [name: "supportedThermostatModes", value: JsonOutput.toJson(reJson.allowedModes.collect { toHubitatMode(it) })])
    }

    // Setpoint limits, used to clamp requested setpoints before sending them.
    ["minHeatSetpoint", "maxHeatSetpoint", "minCoolSetpoint", "maxCoolSetpoint", "deadband"].each { key ->
        updateDataValueIfChanged(device, key, reJson[key])
    }

    def changeableValues = reJson.changeableValues ?: [:]
    refreshHelper(changeableValues, "heatSetpoint", "heatingSetpoint", device, tempUnits, false, false)
    refreshHelper(changeableValues, "coolSetpoint", "coolingSetpoint", device, tempUnits, false, false)

    if (changeableValues.thermostatSetpointStatus != null)
    {
        sendEvent(device, [name: "holdStatus", value: changeableValues.thermostatSetpointStatus.toString()])
    }

    def autoChangeover = changeableValues.containsKey("autoChangeoverActive") ? changeableValues.autoChangeoverActive.toString() : "unsupported"
    sendEvent(device, [name: "autoChangeoverActive", value: autoChangeover])

    def emergencyHeat = changeableValues.containsKey("emergencyHeatActive") ? changeableValues.emergencyHeatActive.toString() : "unsupported"
    sendEvent(device, [name: "emergencyHeatActive", value: emergencyHeat])

    def mode = toHubitatMode(changeableValues.mode)
    if (mode == "heat" && emergencyHeat == "true")
    {
        mode = "emergency heat"
    }
    if (mode != null)
    {
        sendEvent(device, [name: "thermostatMode", value: mode])
    }

    def operationStatus = reJson.operationStatus?.mode

    // thermostatSetpoint is the setpoint currently in effect.
    def activeSetpoint = changeableValues.heatSetpoint
    if (mode == "cool" || (mode == "auto" && operationStatus == "Cool"))
    {
        activeSetpoint = changeableValues.coolSetpoint
    }
    if (activeSetpoint != null)
    {
        sendEvent(device, [name: "thermostatSetpoint", value: activeSetpoint, unit: tempUnits])
    }

    def fan = reJson.settings?.fan
    if (fan != null)
    {
        if (fan.allowedModes != null)
        {
            sendEvent(device, [name: "supportedThermostatFanModes", value: JsonOutput.toJson(fan.allowedModes.collect { it.toString().toLowerCase() })])
        }
        if (fan.changeableValues?.mode != null)
        {
            sendEvent(device, [name: "thermostatFanMode", value: fan.changeableValues.mode.toString().toLowerCase()])
        }
        if (fan.fanRunning != null)
        {
            sendEvent(device, [name: "thermostatFanState", value: fan.fanRunning.toString()])
        }
    }

    def formatedOperationStatus = null
    if (operationStatus == "EquipmentOff")
    {
        formatedOperationStatus = toBool(fan?.fanRunning) ? "fan only" : "idle"
    }
    else if(operationStatus == "Heat")
    {
        formatedOperationStatus = "heating";
    }
    else if(operationStatus == "Cool")
    {
        formatedOperationStatus = "cooling";
    }
    else if(operationStatus == "Fan")
    {
        formatedOperationStatus = "fan only";
    }
    else
    {
        LogWarn("Unexpected Operation Status: ${operationStatus}")
    }

    if (formatedOperationStatus != null)
    {
        LogDebug("updateThermostats-thermostatOperatingState: ${formatedOperationStatus}")
        sendEvent(device, [name: 'thermostatOperatingState', value: formatedOperationStatus])
        updateRuntime(device, formatedOperationStatus)
    }
}

def getRoomNames(String parentDeviceId, String locationId, String groupId)
{
    LogDebug("getRoomNames()")
    def names = [:]
    def result = apiRequest("GET", "/v2/devices/thermostats/${parentDeviceId}/group/${groupId}/rooms", [locationId: locationId])
    result.data?.rooms?.each { room ->
        names[room.id as Integer] = room.name
    }
    return names
}

def refreshOccupiedAttr(jsonString, com.hubitat.app.DeviceWrapper device)
{
    try
    {
        LogDebug("refreshOccupiedAttr() - device:${device}")

        def value = jsonString.get("overallMotion")
        LogDebug("overallMotion: ${value}")

        if (value == null)
        {
            LogDebug("Remote Sensor Error -  Missing overallMotion entry")
            return false
        }

        def occupancy = value ? "Occupied" : "Vacant"
        sendEvent(device, [name: "occupied", value: occupancy])
    }
    catch (Exception e)
    {
        LogDebug("refreshOccupiedAttr() error: ${e.getLocalizedMessage()}")
        return false
    }
    return true
}

// Remote sensors grouped by the thermostat they report through: "<locationId>|<parentDeviceId>" -> [sensors]
def getRemoteSensorsByThermostat()
{
    return getChildDevices().findAll { isRemoteSensor(it) }.groupBy { sensorGroupKey(it) }
}

def sensorGroupKey(com.hubitat.app.DeviceWrapper sensor)
{
    return "${sensor.currentValue('locationId')}|${sensor.currentValue('parentDeviceId')}".toString()
}

// The /priority endpoint returns every room for a thermostat, so all of its sensors are updated from one API call.
def refreshRemoteSensorGroup(List sensors)
{
    def activeSensors = sensors.findAll { !it.isDisabled() }
    if (activeSensors.isEmpty())
    {
        return
    }

    def first = activeSensors[0]
    def honeywellDeviceID = first.currentValue("parentDeviceId")
    def honeywellLocation = first.currentValue("locationId")
    def result = apiRequest("GET", "/v2/devices/thermostats/${honeywellDeviceID}/priority", [locationId: honeywellLocation])
    if (!result.ok)
    {
        return
    }

    def tempUnits = getChildDevice(first.currentValue("parentDeviceNetId")?.toString())?.currentValue("units") ?: "F"
    def rooms = result.data?.currentPriority?.rooms ?: []

    activeSensors.each { sensor ->
        def roomID = sensor.currentValue("roomId")
        def roomJson = rooms.find { (it.id as Integer) == (roomID as Integer) }
        if (roomJson == null)
        {
            LogError("No room ${roomID} reported for ${sensor.getLabel()}")
            return
        }
        LogDebug( "roomJson: ${roomJson}")
        //TO DO: Fix accessory indexing workaround (if possible)
        refreshHelper(roomJson, "roomAvgTemp", "temperature", sensor, tempUnits, false, false)
        refreshHelper(roomJson, "roomAvgHumidity", "humidity", sensor, "%", false, false)
        if (roomJson.accessories)
        {
            refreshHelper(roomJson.accessories[0], "status", "batterystatus", sensor, null, false, false)
        }
        refreshHelper(roomJson, "roomName", "roomName", sensor, null, false, false)
        fixPlaceholderSensorLabel(sensor, roomID, roomJson.roomName)
        refreshOccupiedAttr(roomJson, sensor)
    }
}

def RefreshAllRemoteSensors(manual=false)
{
    LogDebug("RefreshAllRemoteSensors()")
    if (!manual && skipScheduledPoll())
    {
        return
    }
    getRemoteSensorsByThermostat().each { key, sensors ->
        refreshRemoteSensorGroup(sensors)
    }
}

// Called from the sensor driver. Refreshes every sensor on the same thermostat since it costs the same single call.
def refreshRemoteSensor(com.hubitat.app.DeviceWrapper device)
{
    LogDebug("refreshRemoteSensor()")

    if (device.isDisabled()) {
        LogDebug("refreshRemoteSensor - device disabled")
        return;
    }

    def sensors = getRemoteSensorsByThermostat()[sensorGroupKey(device)] ?: [device]
    refreshRemoteSensorGroup(sensors)
}

//
// Commands
//

// A range is usable only if the thermostat reports one; heat-only models report the cool range as 0-0.
def hasSetpointRange(com.hubitat.app.DeviceWrapper device, String minKey, String maxKey)
{
    def min = device.getDataValue(minKey)
    def max = device.getDataValue(maxKey)
    return min != null && max != null && min.isNumber() && max.isNumber() && (max as BigDecimal) > 0 && (max as BigDecimal) >= (min as BigDecimal)
}

def clampSetpoint(com.hubitat.app.DeviceWrapper device, BigDecimal value, String minKey, String maxKey)
{
    if (!hasSetpointRange(device, minKey, maxKey))
    {
        return value
    }
    def min = device.getDataValue(minKey)
    def max = device.getDataValue(maxKey)
    if (min != null && min.isNumber() && value < (min as BigDecimal))
    {
        LogWarn("Setpoint ${value} below thermostat minimum ${min}, using ${min}")
        return min as BigDecimal
    }
    if (max != null && max.isNumber() && value > (max as BigDecimal))
    {
        LogWarn("Setpoint ${value} above thermostat maximum ${max}, using ${max}")
        return max as BigDecimal
    }
    return value
}

// Honeywell takes whole degrees in Fahrenheit and half degrees in Celsius.
def roundSetpoint(BigDecimal value, String units)
{
    if (units == "C")
    {
        return Math.round(value.doubleValue() * 2) / 2.0
    }
    return Math.round(value.doubleValue())
}

// holdMode: "permanent" (default), "hours" (TemporaryHold ending holdHours from now) or "resume" (NoHold: return to the schedule). LCC models only.
def setThermosatSetPoint(com.hubitat.app.DeviceWrapper device, mode=null, autoChangeoverActive=false, emergencyHeatActive=null, heatPoint=null, coolPoint=null, holdMode=null, holdHours=null)
{
    LogDebug("setThermosatSetPoint()")
    def ids = parseThermostatDni(device.getDeviceNetworkId())
    if (ids == null)
    {
        LogError("Unexpected thermostat network id: ${device.getDeviceNetworkId()}")
        return false
    }

    if (mode == null)
    {
        mode=device.currentValue('thermostatMode');
    }

    def emergencyHeatSupported = emergencyHeatActive != null && emergencyHeatActive.toString() != "unsupported"
    def allowedModes = device.currentValue("allowedModes")?.toString() ?: ""
    def wantEmergencyHeat = false

    //The Honeywell API expects uppercase.
    def apiMode
    switch (mode?.toString()?.toLowerCase()?.replace(" ", ""))
    {
        case "heat":
            apiMode = "Heat"
            break
        case "cool":
            apiMode = "Cool"
            break
        case "off":
            apiMode = "Off"
            break
        case "auto":
            apiMode = "Auto"
            break
        case "emergencyheat":
            wantEmergencyHeat = true
            if (allowedModes.contains("EmergencyHeat"))
            {
                apiMode = "EmergencyHeat"
            }
            else if (emergencyHeatSupported)
            {
                apiMode = "Heat"
            }
            else
            {
                LogWarn("Emergency heat is not supported by this thermostat.")
                return false
            }
            break
        default:
            LogError("Invalid Mode Specified: ${mode}")
            return false;
    }

    def units = device.currentValue("units") ?: "F"
    def heatRequested = heatPoint != null
    if (heatPoint == null)
    {
        heatPoint=device.currentValue('heatingSetpoint');
    }

    if (coolPoint == null)
    {
        coolPoint=device.currentValue('coolingSetpoint');
    }

    if (heatPoint == null || coolPoint == null)
    {
        LogError("Current setpoints unknown, refresh the thermostat first.")
        return false
    }

    heatPoint = roundSetpoint(clampSetpoint(device, heatPoint as BigDecimal, "minHeatSetpoint", "maxHeatSetpoint"), units)
    coolPoint = roundSetpoint(clampSetpoint(device, coolPoint as BigDecimal, "minCoolSetpoint", "maxCoolSetpoint"), units)

    // Keep heat at or below cool, moving the one that wasn't requested. Only thermostats with Auto (dual setpoint) need the
    // deadband gap; others use one setpoint at a time and just mustn't be inverted.
    def deadband = device.getDataValue("deadband")
    def gap = (deadband != null && deadband.isNumber() && (deadband as BigDecimal) > 0) ? (deadband as BigDecimal) : (units == "C" ? 1 : 2)
    if (!allowedModes.contains("Auto"))
    {
        gap = 0
    }
    def heatAndCool = hasSetpointRange(device, "minHeatSetpoint", "maxHeatSetpoint") && hasSetpointRange(device, "minCoolSetpoint", "maxCoolSetpoint")
    if (heatAndCool && heatPoint > coolPoint - gap)
    {
        if (heatRequested)
        {
            coolPoint = heatPoint + gap
        }
        else
        {
            heatPoint = coolPoint - gap
        }
        LogInfo("Adjusted setpoints to keep heat below cool: heat ${heatPoint}, cool ${coolPoint}")
    }

    LogDebug("Attempting to Set DeviceID: ${ids.deviceId}, With LocationID: ${ids.locationId}");

    def body = [
            mode:apiMode,
            heatSetpoint:heatPoint,
            coolSetpoint:coolPoint]

    // For LCC devices thermostatSetpointStatus = "NoHold" will return to schedule. "TemporaryHold" will hold the set temperature until "nextPeriodTime". "PermanentHold" will hold the setpoint until user requests another change.
    if (ids.deviceId.startsWith("LCC"))
    {
        if (holdMode == "resume")
        {
            body.thermostatSetpointStatus = "NoHold"
        }
        else if (holdMode == "hours")
        {
            body.thermostatSetpointStatus = "TemporaryHold"
            body.nextPeriodTime = getHoldEndTime((holdHours ?: 2) as Integer)
        }
        else
        {
            body.thermostatSetpointStatus = "PermanentHold"
        }
    }
    else if (holdMode == "resume")
    {
        LogWarn("Resuming the schedule is only supported on Lyric (LCC) thermostats.")
        return false
    }

    if (autoChangeoverActive != null && autoChangeoverActive.toString() != "unsupported")
    {
        body.autoChangeoverActive = toBool(autoChangeoverActive)
    }

    if (emergencyHeatSupported)
    {
        body.emergencyHeatActive = wantEmergencyHeat
    }

    def result = apiRequest("POST", "/v2/devices/thermostats/${ids.deviceId}", [locationId: ids.locationId], body)
    if (!result.ok)
    {
        return false
    }
    LogInfo("SetThermostate() Mode: ${apiMode}; Heatsetpoint: ${heatPoint}; CoolPoint: ${coolPoint}; Hold: ${body.thermostatSetpointStatus ?: 'n/a'}${body.nextPeriodTime ? ' until ' + body.nextPeriodTime : ''} API Response: ${result.status}")
    if (body.thermostatSetpointStatus)
    {
        sendEvent(device, [name: "holdStatus", value: body.thermostatSetpointStatus])
    }

    // Reflect the change immediately; the deferred refresh corrects anything the thermostat didn't accept.
    def hubitatMode = wantEmergencyHeat ? "emergency heat" : toHubitatMode(apiMode)
    sendEvent(device, [name: "thermostatMode", value: hubitatMode])
    sendEvent(device, [name: "heatingSetpoint", value: heatPoint, unit: units])
    sendEvent(device, [name: "coolingSetpoint", value: coolPoint, unit: units])
    sendEvent(device, [name: "thermostatSetpoint", value: (hubitatMode == "cool" ? coolPoint : heatPoint), unit: units])
    scheduleCommandRefresh()
    return true;
}

// Hold end for TemporaryHold: hub-local time holdHours from now, rounded up to Honeywell's 15 minute increments, as HH:mm:ss.
def getHoldEndTime(int holdHours)
{
    def quarterHourMs = 15 * 60 * 1000L
    def endMs = now() + holdHours * 60 * 60 * 1000L
    endMs = ((long)Math.ceil(endMs / (double)quarterHourMs)) * quarterHourMs
    return new Date(endMs).format("HH:mm:00")
}

def resumeThermostatSchedule(com.hubitat.app.DeviceWrapper device)
{
    LogDebug("resumeThermostatSchedule()")
    return setThermosatSetPoint(device, null, device.currentValue("autoChangeoverActive"), device.currentValue("emergencyHeatActive"), null, null, "resume")
}

def setThermosatFan(com.hubitat.app.DeviceWrapper device, fan=null)
{
    LogDebug("setThermosatFan()"  )
    def ids = parseThermostatDni(device.getDeviceNetworkId())
    if (ids == null)
    {
        LogError("Unexpected thermostat network id: ${device.getDeviceNetworkId()}")
        return false
    }

    if (fan == null)
    {
        fan=device.currentValue('thermostatFanMode');
    }

    def apiFan
    switch (fan?.toString()?.toLowerCase())
    {
        case "auto":
            apiFan = "Auto"
            break
        case "on":
            apiFan = "On"
            break
        case "circulate":
            apiFan = "Circulate"
            break
        default:
            LogError("Invalid Fan Mode Specified: ${fan}")
            return false;
    }

    LogDebug("Attempting to Set Fan For DeviceID: ${ids.deviceId}, With LocationID: ${ids.locationId}");
    def result = apiRequest("POST", "/v2/devices/thermostats/${ids.deviceId}/fan", [locationId: ids.locationId], [mode: apiFan])
    if (!result.ok)
    {
        return false
    }
    LogDebug("SetThermostateFan Response: ${result.status}")

    sendEvent(device, [name: "thermostatFanMode", value: apiFan.toLowerCase()])
    scheduleCommandRefresh()
    return true;
}
