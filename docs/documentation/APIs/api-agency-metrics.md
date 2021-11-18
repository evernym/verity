# Agency metrics APIs

**Notes:**
* [notation reference](api-notation-reference.md)
* These APIs are only callable from IP addresses whitelisted in
[`internal-api.conf`](http://bit.ly/2JmXrqn). [By default, only localhost
is allowed](../../guidelines/provide-safe-and-helpful-defaults.md).


**Details:**
* metadata: high level data about the metrics
* nodeName: Hostname of the node
* timestamp: The time when request was fulfilled
* lastResetTimestamp: The time when metrics was last reset (explicitly by user or implicitly 
    by service restart etc)
* metrics: contains metrics for configured targets.
    * name: There are different types of metrics (see all of them [here](https://kamon.io/documentation/1.x/instrumentation/akka/actor-system-metrics/)), 
    few metric name examples are given below:
    
        akka.actor.time-in-mailbox
        
        akka.actor.processing-time
        
        akka.actor.mailbox-size
        
    * target: provides object name (like actor system name, actor name, router name, dispatcher name) 
        to which the metric value is related to. So for example, if someone only tells you that 
        the value of ‘akka.actor.time-in-mailbox’ is ‘5’, you would like to know for which 
        target (actor system, actor, router, dispatcher etc) that metric is all about, 
        that is the target.
    * tags: provides more detail about the target-name object. [Here](https://kamon.io/documentation/1.x/instrumentation/akka/actor-system-metrics/) you can see what tags (search 'following tags' in that page) are 
        there for which type of metrics. Also, see examples mentioned below to know what it 
        may look like for different metrics.

**Request:**

    GET <agency-url>/agency/internal/metrics?includeMetadata=Y/N&includeTags=Y/N&filtered=Y/N&allNodes=Y/N


**Response Template:**

    {
        "data":
            [
                {
                    “metadata”: {
                        “nodeName”: “<node-name>”, 
                        “timestamp”: “<timestamp>”,
                        “lastResetTimestamp”: “<last reset timestamp>”
                    },
                    “metrics”: [
                        {
                            “name”: “<metrics-name>”,
                            “target”: “<target object name of this metric>”,
                            “value”: <value>, 
                            “tags”: {
                                “<tag-name-1>”: "<tag-value>",
                                “<tag-name-2>”: "<tag-value>",
                            }
                        },
                        ..., 
                        ...,
                    ]
                },
                ...,
                ...
            ]
    }


**Example:**

    {
        "data":
            [
                {
                    “metadata”: {
                        “nodeName”: “cagency01”, 
                        “timestamp”: “2018-04-10T06:28:42+00:00”,
                        “lastResetTimestamp”: “2018-04-10T03:28:42+00:00”
                    },
                    “metrics”: [
                        {
                            “name”: “akka_system_dead_letters_total”,
                            “target”: “actor-system”,
                            “value”: 6.0, 
                            “tags”: {
                                “system”: "consumer-agency"
                            }
                        },
                        {
                            “name”: “akka_actor_processing_time_seconds_sum”,
                            “target”: “key-value-mapper”,
                            “value”: 0.041877504, 
                            “tags”: {
                                "path": "consumer-agency/user/cluster-singleton-mngr/singleton/key-value-mapper",
                                "system": "consumer-agency",
                                "dispatcher": "akka.actor.default-dispatcher",
                                "class": "com.evernym.agency.common.actor.KeyValueMapper"
                            }
                        },
                        {
                            “name”: “akka_actor_time_in_mailbox_seconds_sum”,
                            “target”: “key-value-mapper”,
                            “value”: 0.020084992, 
                            “tags”: {
                                "path": "consumer-agency/user/cluster-singleton-mngr/singleton/key-value-mapper",
                                "system": "consumer-agency",
                                "dispatcher": "akka.actor.default-dispatcher",
                                "class": "com.evernym.agency.common.actor.KeyValueMapper"
                            }
                        },
                        ...,
                        ...
                    ]
                },
                ...,
                ...
            ]
    }
