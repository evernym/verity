# Message packaging

**Notes:**
* [notation reference](api-notation-reference.md)
* Use this [msgpack site](https://kawanet.github.io/msgpack-lite) to see how native 
data gets converted to msg packed binary data


### Details

##### msg-packed-msg

Applies [message pack](https://msgpack.org/index.html) transformation on native
data types. For example, given a structure like this:
    
```JSON
{
    "@type": {
        "name": "API_AUTH_DETAIL",
        "ver": "1.0"
    },
    "token": "t1"
}
```

Message packing transforms it to:
```
[130, 165, 64, 116, 121, 112, 101, 130, 164, 110, 97, 109, 101, 175, 65, 80, 
73, 95, 65, 85, 84, 72, 95, 68, 69, 84, 65, 73, 76, 163, 118, 101, 114, 163, 
49, 46, 48, 165, 116, 111, 107, 101, 110, 162, 116, 49]

```

* **api auth detail msg:** used to send 'api-token' to agency so that agency can recognize who 
is the client and accordingly apply checking/tracking/blocking rules.     
    ```
    {
        "@type": {
            "name": "API_AUTH_DETAIL",
            "ver": "1.0"
        },
        "token": "t1"
    }
    
* **bundled msg:** it contains list of 'msg_packed' messages, this is high level message unit we send 
either to 'agency-routing-service' or 'agent' itself (off course there will be some more transformations 
on top of that, for simplicity, I am only explaining what bundled message contains inside)
    ```
    {
        “bundled”: [
            {
                <msg_packed-msg1>,
                <msg_packed-msg2>,
                ...
            }
        ]
    } 
    ```
    
* **fwd msg:**
    ```
    {
      "@type": {
        "name": "FWD",
        "ver": "1.0"
      },
      "@fwd": "<agent DID>",
      "@msg": <auth crypted msg for the agent>
    }
    ```

* **anon_crypt msg for agency routing service:**
    ```
    anon_crypt ( msg_packed ( {
        “bundled”: [
            msg_packed ( {
                api-auth-detail-msg     [this msg is OPTIONAL]
            } ),
            msg_packed ( {
                fwd-msg
            } ),
            ...,
            ...
        ]
    } ) )
    ```
    
* **auth_crypt msg for an agent:**
    ```
    auth_crypt ( msg_packed ( {
      “bundled”: [
          msg_packed ( {
              msg1
          } ),
          msg_packed ( {
              msg2
          } ),
          ...,
          ...
      ]
    } ) )
    ```
    
* **other msgs:**
    for other different messages, please see below docs:
    * [Owner On boarding](api-owner-onboarding.md)
    * [Owner Agent configuration](api-agent-configuration.md)
    * [Owner Agent pairwise connections api](api-pairwise-connections.md)
