# Health check APIs

**Notes:**
* [notation reference](api-notation-reference.md)
* These APIs are only callable from IP addresses whitelisted in
[`internal-api.conf`](http://bit.ly/2JmXrqn). [By default, only localhost
is allowed](../../guidelines/provide-safe-and-helpful-defaults.md).


## app state
**To see agent service state:**

    GET <agency-url>/agency/internal/health-check/application-state

**response:** 
    
    [
      "Degraded",
      "Listening",
      "Initializing"
    ]

**To see agent service state with detail:**

    GET <agency-url>/agency/internal/health-check/application-state?detail=Y

**response:**

    {
    	"stateStack": [
            {
                "date": "2018-05-30T06:25:18.076Z[Etc/UTC]",
                "state": "Listening",
                "cause": "app-started-successfully"
            },
            {
                "date": "2018-05-30T06:25:15.100Z[Etc/UTC]",
                "state": "Initializing",
                "cause": "App start"
            }
    	],
    	"stateCauses": {
            "Listening": [
                {
                    "code": "app-started",
                    "msg": "app-started-successfully"
                }
            ]
    	},
    	"causesByContexts": {
            "app-start-preparation": [
                {
                    "code": "app-started",
                    "msg": "app-started-successfully"
                }
            ]
    	}
    }

## config state

**To see all loaded configs:**

    GET <agency-url>/agency/internal/health-check/config

**To see configs at some path:**

    GET <agency-url>/agency/internal/health-check/config?path=agency
            
**To see configs at nested path (provide dotted notation):**

    GET <agency-url>/agency/internal/health-check/config?path=agency.lib-indy

**To see configs with comments:**

    GET <agency-url>/agency/internal/health-check/config?path=agency&comments=y

**To see configs with origin comments:**

    GET <agency-url>/agency/internal/health-check/config?path=agency&originComments=y

**To see configs without formatting:**

    GET <agency-url>/agency/internal/health-check/config?path=agency&formatted=n

