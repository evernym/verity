# Agent configuration APIs

**Notes:**
* [notation reference](api-notation-reference.md)
* Message structure shown in below examples are at very high level (as far as packaging detail goes), 
see **'Details'**  section [here](api-msg-packaging.md) to know exact packaging detail.


## Set/Update Agent configs

**Notes:**
* This is basically key value pair stored in agent. 
* For now, it is used for institution owner to set 'name' and 'logo url'.

**Request**

POST <agency-url>/agency/msg

```
forwardedMsg = auth_crypt ( {
    “bundled”: [
        { 
            “@type”:{“name”:”UPDATE_CONFIGS”, “ver”:”1.0”},
            “configs”: [
                {“name”:”<conf1>”, “value”:”value1”},
                {“name”:”<conf2>”, “value”:”value2”}
            ]
        }
    ]
} ) ~A0f0 + F0~A0f0
```

**request body**
```
anon_crypt ( { 
    “bundled”: [
        {
            “@type”:{“name”:”FWD”, “ver”:”1.0”},
            “@fwd”: “A0f0”,
            “@msg”:<forwardedMsg>
        }
    ]
} ) ~E0
```

**Response**

```
auth_crypt ( { 
    “bundled”: [
        {
            “@type”:{“name”:”CONFIGS_UDPATED”, “ver”:”1.0”}
        }
    ]
} ) ~F0 + A0f0~F0
```

## Set/Update Agent com method

**Notes:** 
* This will be used to add/update agent’s communication method. 
* For now it is used for Consumer App to add push notification com method.

**Request**

POST <agency-url>/agency/msg

```
forwardedMsg = auth_crypt ( {
    “bundled”: [
        { 
            “@type”:{“name”:”UPDATE_COM_METHOD”, “ver”:”1.0”},
            “comMethod”: {
                “id”:”<unique com method id>”, 
                “type”:”<com method type>”, 
                “value’:”<com method value>”
            }
        }
    ]
} ) ~A0b0 + B0~A0b0
```

**request body**
```
anon_crypt ( { 
    “bundled”: [
        {
            “@type”:{“name”:”FWD”, “ver”:”1.0”},
            “@fwd”: “A0b0”,
            “@msg”:<forwardedMsg>
        }
    ]
} ) ~C0
```

**Response**

```
auth_crypt ( { 
    “bundled”: [
        {
            “@type”:{“name”:”COM_METHOD_UPDATED”, “ver”:”1.0”},
            “id”:”<supplied unique com method id>”
        }
    ]
} ) ~B0 + A0b0~B0
```