# Resource Usage Configuration and APIs

**Note:** These APIs are only callable from IP addresses whitelisted in
[`internal-api.conf`](http://bit.ly/2JmXrqn). [By default, only the localhost
is allowed](../../guidelines/provide-safe-and-helpful-defaults.md).

## Design Considerations
### Asynchronous Counting
By design, the resource usage counting is done asynchronous relative to
enforcement. Eventually consistent (asynchronous non-blocking synchronization)
management of usage statistics across all nodes in both single node and
clustered environment (more than one Akka node) configurations eliminates a
bottleneck for the majority of requests where all requests/messages are allowed.

A side effect of this design is that a client may be able to access a resource
beyond the configured limit(s), albeit for a short time. The extent by which a
client may exceed the configured limits depends on how quickly stats can be
accrued and/or synced between nodes.

For example, if an Agency admin configures sources (clients/callers) to be
blocked after x calls to a specific API endpoint, the sources' `id`
may get blocked after x+5 requests under normal load, or after x+10 requests
while under heavy load.

### Usage and Enforcement Persistence
Most usage counts are *not* persisted. They can optionally be persisted (see
below for rule configuration). Persisting usage counts for timed buckets is
normally not needed or recommended.

Blocking, Unblocking, Warning, and Unwarning actions are always persisted and
will be in the same state after a service restart.

## Resource Usage Control Flow
Each resource request to the Agent Service will be considered. The source of the
request will be categorized by the senders `id`. An id is one of
the following: client ip-address, user DID, or "global".

The following steps will determine if the resource will be allowed:
1. blacklist/whitelist will be considered. If the source is found in one of
   these lists, it will be either completely allowed or completely blocked. No
   further consideration is given. 
1. Source and/or resource will be checked against the current
   blocked/unblocked/warned/unwarned lists. If the source and/or resource are
   in these lists, consideration will be given to either block, warn, or allow
   the resource.
1. If the resource has been allowed, an asynchronous message will be sent about
   the usage of the resources and the resource will be granted. 
1. The asynchronous message will increment various counters in various buckets
   (according to the rules configured). If any of these counters are found to be
   in violation of the configured rules, a violation action is performed. The
   actions range from logging a message to blocking the id/resource.

## Resource Usage Configuration
```
Note: Config changes will take effect as soon config is reloaded by hitting 
config reload API (it does not require service restart)
```

Configuration consists of four elements:
* Usage Rules
* Violation Actions
* Rule to Tokens Map
* Blacklists/Whitelists

See an [Example configuration file](https://gitlab.com/evernym/verity/verity/-/blob/main/verity/src/main/resources/debian-package-resources/resource-usage-rule.conf).

### Usage Rules
#### Rule Hierarchy
##### Named Rules
Usage rules are grouped by a name. The name is defined as an object in the
configuration (ex. `agency.usage-rules.custom-rules` has a name of
`custom-rules`) The name is used by the Rule to Tokens Map. The `default` name
has special meaning and is used when a source is not explicitly mapped to a set
of rules.

##### Resources

There are two categories of resources:
* endpoint - refers to the HTTP endpoint used by the source.
* message - refers to the agent message being processed.

**Endpoints**
* `GET_agency` (GET /agency): This endpoint can be used by anyone who knows the
  URL of the agency to get the agency’s DID and ver key
* `GET_agency_invite` (GET /agency/invite?t=&lt;connection-request-token&gt;): This
  endpoint can be used by anyone who knows the connection request token to get
  more detail about the connection requests (as of today, connect.me uses this)
* `GET_agency_invite_did`
  (GET /agency/invite/&lt;DID&gt;?uid=&lt;connection-request-token&gt;): This endpoint
  can be used by invitation sender to get the invitation detail back. The logic
  behind this URL and the previous URL (GET_agency_invite) are the same.
* `POST_agency_msg` (POST /agency/msg): This endpoint is used to send any/all
  agent messages (like connect with agency, signup with agency, create agent
  etc)

**Messages**
* `CONNECT:` This is used by the owner (consumer or institution) to establish a
  connection with agency.
* `SIGNUP:` This is used by the owner (consumer or institution) to
  signup/register with agency.
* `CREATE_AGENT:` This is used by the owner (consumer or institution) to create
  an agent.
* `CREATE_KEY:` This is used by the owner (consumer or institution) to create a
  pairwise key for the new connection (sending or accepting)
* `UPDATE_COM_METHOD:` This is used by the owner (consumer only as of today) to
  update its communication method (as of today it is push communication method)
* `UPDATE_CONFIGS:` This is used to update agent’s configuration (like name,
  logo URL etc)
* `UPDATE_CONN_STATUS:` This is used to change the status of the pairwise
  connection (like DELETED etc) “connect.me” uses this only as of today.
* `CREATE_MSG_connReq:` This is to create/send connection offer/request.
* `CREATE_MSG_connReqAnswer:` This is to answer connection request.
* `CREATE_MSG_credOffer:` This is to send a credential offer.
* `CREATE_MSG_credReq:` This is to send credential request.
* `CREATE_MSG_cred:` This is to send credential.
* `CREATE_MSG_proofReq:` This is to send a proof request.
* `CREATE_MSG_proof:` This is to send proof.
* `UPDATE_MSG_STATUS:` This is to update an existing message’s status (like
   reviewed etc)
* `UPDATE_MSG_STATUS_BY_CONNS:` This is to update an existing message's status
  belonging to different pairwise connections.
* `GET_MSGS:` This is to get messages based on given criteria. When “connect.me”
  receives a push notification (alert or silent one), this message is sent to
  get more detail about the received msg. “VerityUI” uses this heavily to poll
  to see if any new message have been received or if the status of an existing
  message has changed.
* `GET_MSGS_BY_CONNS:`: This is to get messages based on given criteria
  belonging to different pairwise connections.

##### Rules
The rules define acceptable use. (See Rule Definition for how to construct them)

**Example Hierarchy**
```
usage-rules {
  rule-name {
    resource-type {
      resource{
        rule       
      }
    }
```

### Rule Definition
Rules consist of:
* `bucket-interval` - The time interval of the usage rule (in seconds). This
  value can be `-1` to specify an indefinite period time. 
* `allowed-counts` - The max allowed usage count for the rule.
* `violation-action-id` - The id of the Violation Action that should be executed
  when the allowed count is exceeded. 
* `persist-usage-state` - (Optional) Is a boolean and effects if the counts are
  persisted (survive a service restart). By default, usage state is not
  persisted.

**Pattern**

```
<bucket-interval>: {"allowed-counts":<allowed-counts>, "violation-action-id":<violation-action-id>, "persist-usage-state":<persist-usage-state>}
```

**Examples**:

```
300: {"allowed-counts": 100, "violation-action-id": 50}
-1: {"allowed-counts": 100, "violation-action-id": 70, "persist-usage-state": true}
```

## Violation Actions
### Definition
Violation actions consist of:
* `id` - A numeric id that is referenced in rules 
* `log-msg` - A log action that will cause a log message to be logged when the
  violation happens. A `level` must be specified. Can be normal log levels:
  `info`, `warn`, `error`.
* `warn-resource` - Warn action on a resource (i.e. message type, API endpoint)
  based on a `track-by` id. A track-by id can be `ip`, `user`, or `global`. The
  `track-by` attribute must be defined. `ip` indicates stats should be tracked
  by the client's IP address, `user` indicates stats should be tracked by the
  user's DID, and `global` indicates that stats should be tracked across all
  clients/users regardless of their IP/DID. All unsupported values will fail
  configuration validation on Agency service start-up. After a warn-resource
  violation action is triggered, subsequent requests by the same id will NOT be
  rejected. Rather, Agency admins monitoring warning stats will use warnings to
  potentially react *before* the source is blocked by a block-resource action.
  For example, admins may choose to clear counts on a given id, or use other
  channels (i.e. slack, monitoring dashboard, etc.) to communicate with (warn)
  the client/source. A `period` must also be defined (in seconds) which will be
  the interval where the id will be warned. `-1` can be used to specify an
  indefinite period (permanent warning that must be cleared by an admin).
* `warn-user` - Warn action based on a `track-by` id regardless of resource
  (i.e.  message type, API endpoint). The `track-by` attribute can be `ip`,
  `user`, or `global`. All other values will fail config validation on Agency
  service start-up. After a warn-user violation action is triggered, subsequent
  requests by the same id will NOT be warned. Rather, Agency admins monitoring
  warning stats will use warnings to potentially react *before* the source is
  blocked by a block-user action. For example, admins may choose to clear counts
  on a given id, or use other channels (i.e. slack, monitoring dashboard, etc.)
  to communicate with (warn) the client/source. A `period` must also be defined
  (in seconds) which will be the interval where the id will be warned. `-1` can
  be used to specify an indefinite period (permanent warning that must be
  cleared by an admin).
* `block-resource` - Block action on a resource (i.e. message type, API endpoint)
  based on a `track-by` id. A track-by id can be `ip`, `user`, or `global`. The
  `track-by` attribute must be defined. `ip` indicates stats should be tracked by
  the client's IP address, `user` indicates stats should be tracked by the user's
  DID, and `global` indicates that stats should be tracked across all
  clients/users regardless of their IP/DID. All unsupported values will fail
  configuration validation on Agency service start-up. After a block-resource
  violation action is triggered, subsequent requests by the same id WILL BE
  rejected. A `period` must also be defined (in seconds) which will be the
  interval where the id will be blocked. `-1` can be used to specify an
  indefinite period (permanent block that must be cleared by an admin).
* `block-user` - Block action based on a `track-by` id regardless of resource
  (i.e.  message type, API endpoint). The `track-by` attribute can be `ip`,
  `user`, or `global`. All other values will fail config validation on Agency
  service start-up. After a block-user violation action is triggered, subsequent
  requests by the same id WILL BE blocked. A `period` must also be defined (in
  seconds) which will be the interval where the id will be blocked. `-1` can be
  used to specify an indefinite period (permanent blocking that must be cleared
  by an admin).

**Examples**:

```
  50 {
    log-msg: {"level": "info"}
    warn-resource: {"track-by": "ip", "period": 600}
  }
  70 {
    log-msg: {"level": "info"}
    warn-user: {"track-by": "ip", "period": -1}
    block-resource: {"track-by": "ip", "period": 600}
  }
  90 {
    log-msg: {"level": "info"}
    block-user: {"track-by": "ip", "period": -1}
  }
```

### Rule to Tokens Map
Maps source tokens to rules.

**Examples**:

```
rule-to-tokens {
  default: []
  custom: ["127.0.0.5/16", "127.1.0.1", "randomToken", "128.0.0.1"]
}
```

### Blacklists/Whitelists
Explicitly list blocked (blacklisted) and fully permitted (whitelisted) sources.
Only add sources (ids) to the blacklisted-tokens list if they are to be
permanently blocked. Likewise, only add sources to the whitelisted-tokens list
if they are to be permanently permitted/trusted.

**Examples**:

```
    blacklisted-tokens = ["128.0.0.1"]

    whitelisted-tokens = ["128.0.0.1"]
}
```

## APIs
```
Notes:
In the below API calls, <id> will always be the “ip address” of the client, "DID" of the
user, or "global".
```

##### See warned resources
    GET <agency-url>/agency/internal/resource-usage/warned?onlyWarned=Y&onlyUnwarned=N&onlyActive=Y
Notes:
* If “warnFrom”/”unwarnFrom” is present but corresponding “warnTill”/“unwarnTill” is not present, 
  meaning, that is warned/unwarned for indefinite time.

##### See blocked resources
    GET <agency-url>/agency/internal/resource-usage/blocked?onlyBlocked=Y&onlyUnblocked=N&onlyActive=Y
Notes:
* If “blockFrom”/”unblockFrom” is present but corresponding “blockTill”/“unblockTill” is not present, 
  meaning, that is blocked/unblocked for indefinite time.

##### See resource usage
    GET <agency-url>/agency/internal/resource-usage/id/<id>
    for example: localhost:<port>/agency/internal/resource-usage/id/<id>

##### Reset Usage Count
Reset particular usage counts for a particular ip address.
    `curl -H 'Content-Type: application/json' -X PUT -d '{"resourceUsageCounters": [{"resourceName":"<resource_name>",  "bucketId":<bucketId>, "newCount":<new_count_number>}]}' <agency-url>/agency/internal/resource-usage/id/<id>/counter`

##### Warn id/resource usage

To warn an id indefinitely manually:

    curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"warn"}' <agency-url>/agency/internal/resource-usage/id/<id>

To warn an id for certain time (in seconds) manually:

    curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"warn", "period":600}' <agency-url>/agency/internal/resource-usage/id/<id>

To warn an id resource indefinitely manually:

    curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"warn"}' <agency-url>/agency/internal/resource-usage/id/<id>/resource/<resource-name>

To warn an id resource for certain time (in seconds) manually:

    curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"warn", "period":600}' <agency-url>/agency/internal/resource-usage/id/<id>/resource/<resource-name>

##### Unwarn id/resources

**Notes:**
* Unwarning can be done in two ways:
    1. Updating a warning's warning period to 0 will effectively remove/delete a warning and reset usage stats (set to 0).
       Immediately after setting a warning's warning period to 0, requests will to go through the tracking/checking
       process (starting from 0 usage) and therefore can be warned again if configured rules are violated.
       ```bash
       # Remove a warning on an id and clear usage stats
       curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"warn", "period":0}' <agency-url>/agency/internal/resource-usage/id/<id>

       # Remove warning on an id and all of it's associated resources. All usage stats associated with each
       # warning are also reset.
       curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"warn", "period":0, "allResource":"Y"}' <agency-url>/agency/internal/resource-usage/id/<id>

       # Remove a warning on a resource for a given id and its usage stats
       curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"warn", "period":0}' <agency-url>/agency/internal/resource-usage/id/<id>/resource/<resource-name>
       ```
    1. Update unwarning detail (as mentioned in this section) such that either id or an id's resource is
       unwarned indefinitely or for some period of time. During the unwarning period, requests are not tracked/checked
       so there is no chance that it would be warned again in that period. Usage stats are NOT reset to 0 when an
       id or id's resource is unwarned. Once the unwarning period lapses, usage counts will resume from
       the point they left off before the unwarning period was applied. If this is not the desired behavior, consider
       resetting usage stats.
       ```bash
       # Unwarn an id indefinitely
       curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"unwarn"}' <agency-url>/agency/internal/resource-usage/id/<id>
       # Unwarn an id and all its' associated resources indefinitely
       curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"unwarn", "allResource":"Y"}' <agency-url>/agency/internal/resource-usage/id/<id>
       # Unwarn a resource indefinitely for a given id
       curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"unwarn"}' <agency-url>/agency/internal/resource-usage/id/<id>/resource/<resource-name>
       ```
* If unwarning an id, only warned ids will be unwarned. Any id's warned resources will continue to
  be warned.

      Examples:
        Unwarn indefinitely:
          '{"msgType":"unwarn"}'
        Remove warning and clear usage counts on id:
          '{"msgType":"warn", "period":0}'

  * If you also want to apply this unwarning on warned resources, send an extra parameter called “allResources” with the
    value “Y”.

        Examples:
          Unwarn id and all associated resources indefinitely:
            '{"msgType":"unwarn", "allResource": "Y"}'
          Remove warning and clear usage counts on id and all associated resources:
            '{"msgType":"warn", "period":0, "allResource": "Y"}'

To unwarn an id indefinitely:

    curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"unwarn"}' <agency-url>/agency/internal/resource-usage/id/<id>

To unwarn an id for certain time (in seconds) manually:

    curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"unwarn", "period":600}' <agency-url>/agency/internal/resource-usage/id/<id>

To unwarn an id resource indefinitely:

    curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"unwarn"}' <agency-url>/agency/internal/resource-usage/id/<id>/resource/<resource-name>

To unwarn an id resource for certain time (in seconds) manually:

    curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"unwarn", "period":600}' <agency-url>/agency/internal/resource-usage/id/<id>/resource/<resource-name>

##### Block id/resource usage

To block an id indefinitely manually:

    curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"block"}' <agency-url>/agency/internal/resource-usage/id/<id>

To block an id for certain time (in seconds) manually:

    curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"block", "period":600}' <agency-url>/agency/internal/resource-usage/id/<id>

To block an id resource indefinitely manually:

    curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"block"}' <agency-url>/agency/internal/resource-usage/id/<id>/resource/<resource-name>

To block an id resource for certain time (in seconds) manually:

    curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"block", "period":600}' <agency-url>/agency/internal/resource-usage/id/<id>/resource/<resource-name>

##### Unblock id/resources

**Notes:**
* Unblocking can be done in two ways:
    1. Updating a block's blocking period to 0 will effectively remove/delete a block and reset usage stats (set to 0).
       Immediately after setting a block's blocking period to 0, requests will to go through the tracking/checking
       process (starting from 0 usage) and therefore can be blocked again if configured rules are violated.
       ```bash
       # Remove a block on an id and clear usage stats
       curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"block", "period":0}' <agency-url>/agency/internal/resource-usage/id/<id>

       # Remove blocks on an id and all of it's associated resources. All usage stats associated with each block
       # are also reset.
       curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"block", "period":0, "allResource":"Y"}' <agency-url>/agency/internal/resource-usage/id/<id>

       # Remove a block on a resource for a given id and its usage stats
       curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"block", "period":0}' <agency-url>/agency/internal/resource-usage/id/<id>/resource/<resource-name>
       ```
    1. Update unblock detail (as mentioned in this section) such that either id or an id's resource is
       unblocked indefinitely or for some period of time. During the unblock period, requests are not tracked/checked
       so there is no chance that it would be blocked again in that period. Usage stats are NOT reset to 0 when an
       id or id's resource is unblocked. Once the unblocking period lapses, usage counts will resume from
       the point they left off before the unblock period was applied. If this is not the desired behavior, consider
       resetting usage stats.
       ```bash
       # Unblock an id indefinitely
       curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"unblock"}' <agency-url>/agency/internal/resource-usage/id/<id>
       # Unblock an id and all its associated resources indefinitely
       curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"unblock", "allResource":"Y"}' <agency-url>/agency/internal/resource-usage/id/<id>
       # Unblock a resource indefinitely for a given id
       curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"unblock"}' <agency-url>/agency/internal/resource-usage/id/<id>/resource/<resource-name>
       ```
* If unblocking an id, only blocked ids will be unblocked. Any id's blocked resources will continue
  to be blocked.

      Example: '{"msgType":"unblock"}'
      Example: '{"msgType":"block", "period":0}'

  * If you also want to apply this unblocking on blocked resources, send an extra parameter called “allResources” with the
    value “Y”.

        Example: '{"msgType":"unblock", "allResource": "Y"}'
        Example: '{"msgType":"block", "period":0, allResource": "Y"}'

To unblock an id indefinitely:

    curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"unblock"}' <agency-url>/agency/internal/resource-usage/id/<id>

To unblock an id for certain time (in seconds) manually:

    curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"unblock", "period":600}' <agency-url>/agency/internal/resource-usage/id/<id>

To unblock an id resource indefinitely:

    curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"unblock"}' <agency-url>/agency/internal/resource-usage/id/<id>/resource/<resource-name>

To unblock an id resource for certain time (in seconds) manually:

    curl -H 'Content-Type: application/json' -X PUT -d '{"msgType":"unblock", "period":600}' <agency-url>/agency/internal/resource-usage/id/<id>/resource/<resource-name>
