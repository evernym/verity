# Resource Usage Tracker

## Goal behind this feature
* To be able to easily configure rules around resource usage tracking to
  define actions to be executed when a rule is violated.

### Entity
A tracking item. For now there are four types of entities:
  * global: There will be one instance of this entity in a verity cluster.
  * <ip-address>: There can be many instances (each of one ip address) of this type of entity in a verity cluster.
  * owner-<userid>: There can be many instances of this type of entity in a verity cluster.
  * counterparty-<userid>: There can be many instances of this type of entity in a verity cluster.

### Resource
An item to be tracked. As of now there are two different types of resources being tracked:
  * http endpoint
  * messages

### Bucket
A time period for which resource usages to be tracked. For example 5 minute bucket, 10 min bucket etc.
Each bucket defines `allowed-counts` and `violation-action`. Here is how a bucket entry looks like:

`300: {"allowed-counts": 200, "violation-action-id": 50}`

Here '300' is a bucket id and determines the bucket duration, in this case it is 5 min (300 seconds) bucket.
So within a '5' minute, allowed-counts of the concerned resource is 200 and if that is exceeded then it will 
execute `tasks` mentioned in violation-action-id "50"

There is a special bucket id `-1` to track usage for indefinite period. Mostly this may make sense
to use it with `persist-usage-state`. This may be useful when for certain entity, we want 
certain resources to be only used a finite number of times. For example, we want to make sure
that we only want to allow '5' agent provisioning from an ip address '127.0.0.1' (example ip address), 
then here is how that bucket rule may look like:

`-1: {"allowed-counts": 5, "violation-action-id": 50, "persist-usage-state": true}`

Unless a bucket rule is configured with `persist-usage-state` all those bucket tracking is just in memory only
and won't be persisted.

### Violation action
A violation action consists one or many tasks. Here is an example of how a violation action entry looks like:

```
70 {
  log-msg: {"level": "info"} 
  warn-entity: {"entity-types": "ip", "period": 1200} 
  block-resource: {"entity-types": "ip", "period": 600}
}
```

There are below mentioned supported tasks:
  * **log-msg**: to log a violation message with configured log level
  * **warn-resource**: put an entity for a given resource usage on warning list before it gets blocked.
  * **warn-entity**: put an entity for any resource usage in a warning list
  * **block-resource**: put an entity for a given resource usage on blocking list
  * **block-entity**: put an entity for any resource usage in a blocking list

In each task, there is a field called `entity-types` which can take different comma separated 
entity types ('global', 'ip' or 'user'). If `entity-types` is defined that task will only 
be executed if the tracked entity's type is one of those defined entity types.

### Whitelisted tokens
Those entities matching with any whitelisted tokens won't be tracking resource usages.

### Blacklisted tokens
Those entities matching with any blacklisted tokens will be immediately responded with appropriate http error.