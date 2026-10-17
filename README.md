# hubitat-honeywell
An app and driver for the Honeywell Home thermostats (Lyric, T5, T9 etc…). This uses the official Honeywell Home APIs and services.

## Notes
I would consider this beta code, there are definitely rough edges that still need to be worked out but it’s running in “production” at my house. Feel free to submit pull requests for improvements, on major improvements I will rev the release and the package.

## Install
- Import the driver and the app (or use Hubitat Package Manager)
- Create a new instance of the app.
- Authenticate with Honeywell (establish OAuth Link)
- Discover devices

## API keys and rate limits
The app no longer ships a shared Consumer Key/Secret. Create your own (free) at https://developer.honeywellhome.com/ and enter it under Login Options; the per-key rate limit is the main constraint on how often devices can be polled.

How the app spends API calls:
- **Thermostat polling**: one call per interval covers every thermostat on the account (default every 10 minutes).
- **Remote sensor polling**: separate, optional interval (off by default). One call per interval for each thermostat that has enabled sensors, no matter how many sensors it has. Disable a sensor device to stop polling it; if all sensors on a thermostat are disabled no call is made.
- **Commands**: the change is reflected on the device immediately, then a single refresh of all thermostats runs ~15 seconds later. Several commands in quick succession share that one refresh.
- **Rate limited (HTTP 429)**: scheduled polling pauses (honoring `Retry-After`, otherwise 15 minutes) and the app page shows when it resumes. Commands still go through.

### Not Yet Implemented/Tested
- Events (honeywell supports Azure Event Grid, that needs to be implemented and might require per user accounts)
- Schedules via Honeywelll (I am using the Hubitat Thermostat Scheduler personally)
- Celsius (I put some code in for this but didn't test it)
- Name changes (if you change the name of the device)
- If Honeywell rejects the refresh token the app page shows a reconnect message; use Login Options > Connect to Honeywell.

## Credits
- I got a lot of example and inspiration from @dkilgore90 (https://github.com/dkilgore90/google-sdm-api/blob/develop/sdm-api-app.groovy)
- Credit to Tim in the Hubitat community for patently testing and improving this with me over days/weeks.
