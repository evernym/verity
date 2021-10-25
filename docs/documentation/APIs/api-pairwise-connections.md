# Pairwise connection APIs

**Notes:**
* [notation reference](api-notation-reference.md)
* Message structure shown in below examples are at very high level (as far as packaging detail goes), 
see **'Details'**  section [here](api-msg-packaging.md) to know exact packaging detail.
* Below apis are used to establish pairwise connections and then exchanging messages with them.


## Create Key (for every new connection)

**Request**

POST <agency-url>/agency/msg

```
forwardedMsg = auth_crypt ( {
    “bundled”: [
        { 
            “@type”:{“name”:”CREATE_KEY”, “ver”:”1.0”},
            “forDID”:”F0b”,
            “forDIDVerKey”:”<F0b’s ver key>”
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
            “@fwd”:“A0f0”,
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
            “@type”:{“name”:”KEY_CREATED”, “ver”:”1.0”},
            “withPairwiseDID”:”A0fb”,
            “withPairwiseDIDVerKey”:”<A0fb’s verkey>”
        }
    ]
} ) ~F0 + A0f0~F0
```

**Notes:**
* To support generic message protocol which supports multiple types of message, 
let the top level msg type be “CREATE_MSG”, and then, there would be “mtype” attribute, 
which will define actual msg sub type (cred offer, cred, proof etc).
* Then, ‘MSG_DETAIL’ (second msg in the bundle) will be different for different 'mtype' message.


## Create and/or Send Connection Request

**Request**

POST <agency-url>/agency/msg

```
forwardedMsg2 = auth_crypt ( {
    bundled”: [
        { 
            “@type”:{“name”:”CREATE_MSG”, “ver”:”1.0”}
            “mtype”:”connReq”,
            "sendMsg":true
        },
        { 
            “@type”:{“name”:”MSG_DETAIL”, “ver”:”1.0”},
            “keyDlgProof”:{
                “agentDID”:”A0fb”,
                ”agentDelegatedKey”:”<A0fb’s verkey>”, 
                “signature”:”base64_encoded(<signature (signed by F0b)>)”
            },
            “targetName”:”<target username>”,
            “phoneNo”:”<phone number>”
        }
    ]
} ) ~A0fb + F0b~A0fb
```

```
forwardedMsg1 = auth_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”FWD”, “ver”:”1.0”},
            “@fwd”:“A0fb”,
            “@msg”:<forwardedMsg2>
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
            “@fwd”:“A0f0”,
            “@msg”:<forwardedMsg1>
        }
    ]
} ) ~E0
```     

**Response**

```
auth_crypt ( { 
    “bundled”: [
        {
            “@type”:{“name”:”MSG_CREATED”, “ver”:”1.0”},
            “uid”:”kfi5k2k4”
        },
        {
            “@type”:{“name”:”MSG_DETAIL”, “ver”:”1.0”},
            “inviteDetail”: {json invite detail},
            “urlToInviteDetail”:<url to get invite detail>
        },
        {
            “@type”:{“name”:”MSGS_SENT”, “ver”:”1.0”},
            “uids”:["kfi5k2k4]”
        }
    ]
} ) ~F0 + A0fb~F0
```

## Create and/or send Connection Request Answer

**Request**

POST <agency-url>/agency/msg

```
forwardedMsg2 = auth_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”CREATE_MSG”,”ver”:”1.0”},
            “mtype”:”connReqAnswer”,
            “replyToMsgId”:”<received conn request uid>”,
            "sendMsg":true
        },
        { 
            “@type”:{“name”:”MSG_DETAIL”, “ver”:”1.0”},
            “senderDetail”:”<invite sender detail>”,
            “senderAgencyDetail”:”<sender agency detail>”,
            “answerStatusCode”:”<answer(accept/reject) status code>”,
            “keyDlgProof”:{
                “agentDID”:”A0bf”,
                ”agentDelegatedKey”:”<A0bf’s verkey>”, 
                “signature”:”<signature>”
            }
        }
    ]
} ) ~A0bf + B0f~A0bf
```

```
forwardedMsg1 = auth_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”FWD”, “ver”:”1.0”},
            “@fwd”:“A0bf”,
            “@msg”:<forwardedMsg2>
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
            “@fwd”:“A0b0”,
            “@msg”:<forwardedMsg1>
        }
    ]
} ) ~C0
```     

**Response**

```
auth_crypt ( { 
    “bundled”: [
        {
            “@type”:{“name”:”MSG_CREATED”, “ver”:”1.0”},
            “uid”:”kdu5j3j”
        },
        {
            "@type":{"name":"MSGS_SENT", "ver":"1.0"},
            "uids":["kdu5j3j"]
        }
    ]
} ) ~B0 + A0bf~B0
```

## Create and/or send general msgs (it supports reply msg as well)

**Notes:**
* For vcx, we are using these sub message types for 'CREATE_MSG': 
credOffer, credReq, cred, proofReq, proof,
tokenTransferOffer, tokenTransferReq, tokenTransferred

**Request**

POST <agency-url>/agency/msg

```
forwardedMsg3 = auth_crypt ( {
    <general msg data>
} ) ~F0b + B0f~F0b
```

```
forwardedMsg2 = auth_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”CREATE_MSG”,”ver”:”1.0”},
            “mtype”:”credOffer”,
            “replyToMsgId”:”<msg id to which you are replying>”,
            "sendMsg":true
        },
        { 
            “@type”:{“name”:”MSG_DETAIL”, “ver”:”1.0”},
            “@msg”:<forwardedMsg3>,
            “title”:<title for the msg, used to show in the app>,
            “detail”:”<detail for the msg, used to show in push notif body and in the app>”
        }
    ]
} ) ~A0bf + B0f~A0bf
```

```
forwardedMsg1 = auth_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”FWD”, “ver”:”1.0”},
            “@fwd”:“A0bf”,
            “@msg”:<forwardedMsg2>
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
            “@fwd”:“A0b0”,
            “@msg”:<forwardedMsg1>
        }
    ]
} ) ~C0
```     

**Response**

```
auth_crypt ( { 
    “bundled”: [
        {
            “@type”:{“name”:”MSG_CREATED”, “ver”:”1.0”},
            “uid”:”kfumk23k”
        },
        {
            "@type":{"name":"MSGS_SENT", "ver":"1.0"},
            "uids":["kfumk23k"]
        }
    ]
} ) ~B0 + A0bf~B0
```


## Get messages
**Request**

POST <agency-url>/agency/msg

```
forwardedMsg2 = auth_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”GET_MSGS”,”ver”:”1.0”},
            “excludePayload”:”Y”,
            “uids”:[”<msg uid1>”, ”<msg uid2>”, ...],
            “statusCodes”:[”<msg-status-code1>”, ”<msg-status-code2>”, ...]
        }
    ]
} ) ~A0bf + B0f~A0bf
```

```
forwardedMsg1 = auth_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”FWD”, “ver”:”1.0”},
            “@fwd”:“A0bf”,
            “@msg”:<forwardedMsg2>
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
            “@fwd”:“A0b0”,
            “@msg”:<forwardedMsg1>
        }
    ]
} ) ~C0
```
     
**Response**

```
auth_crypt ( { 
    “bundled”: [
        {
            “@type”:{“name”:”MSGS”, “ver”:”1.0”},
            “msgs”: [
                {
                    “uid”:”<unique msg id>”
                    “statusCode”:”MS-101”,
                    “senderDID”:”<DID>”,
                    “type”:”<msg type>”,
                    “payload”:”<core payload msg>”
                    “refMsgId”:”<referenced msg id>”
                }
            ]
        }
    ]
} ) ~B0 + A0bf~B0
```

## Update msg status
**Request**

POST <agency-url>/agency/msg

```
forwardedMsg2 = auth_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”UPDATE_MSG_STATUS”,”ver”:”1.1”},
            “uids”:[”<msg uid 1>”, ”<msg uid 2>”, ...],
            “statusCode”:”<new status code>”
        }
    ]
} ) ~A0bf + B0f~A0bf
```

```
forwardedMsg1 = auth_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”FWD”, “ver”:”1.0”},
            “@fwd”:“A0bf”,
            “@msg”:<forwardedMsg2>
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
            “@fwd”:“A0b0”,
            “@msg”:<forwardedMsg1>
        }
    ]
} ) ~C0
```
     
**Response**

```
auth_crypt ( { 
    “bundled”: [
        {
            “@type”:{“name”:”MSG_STATUS_UPDATED”, “ver”:”1.1”},
            “uids”:[”<msg uid1>”, ”<msg uid2>”, ...],
            “statusCode”:”<updated status code>”
        }
    ]
} ) ~B0 + A0bf~B0
```

## Update connection status

**Request**

POST <agency-url>/agency/msg

```
forwardedMsg2 = auth_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”UPDATE_CONN_STATUS”,”ver”:”1.0”},
            “statusCode”:”<new status code>”
        }
    ]
} ) ~A0bf + B0f~A0bf
```

```
forwardedMsg1 = auth_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”FWD”, “ver”:”1.0”},
            “@fwd”:“A0bf”,
            “@msg”:<forwardedMsg2>
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
            “@fwd”:“A0b0”,
            “@msg”:<forwardedMsg1>
        }
    ]
} ) ~C0
```     

**Response**

```
auth_crypt ( { 
    “bundled”: [
        {
            “@type”:{“name”:”CONN_STATUS_UPDATED”, “ver”:”1.0”},
            “statusCode”:”<updated status code>”
        }
    ]
} ) ~B0 + A0bf~B0
```

## Get messages by connections

**Request**

POST <agency-url>/agency/msg

Note: In below message

* "pairwiseDIDs" is optional, if not given, it will query all pairwise DIDs.
   Here pairwise DID refers to the DID which you would have supplied in 'CREATE_KEY' request for the pairwise connection.
* "uids" is optional, if not given, it will get all messages
* "statusCodes" is optional, if not given, msgs won't be filtered by status codes

```
forwardedMsg1 = auth_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”GET_MSGS_BY_CONNS”, “ver”:”1.0”},
            “pairwiseDIDs”:[“F0b”, ...],
            “excludePayload”:<Y/N>,
            "uids":["msg-uid1", ...],
            "statusCodes": ["status-code1", ...]
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
            “@fwd”:“A0b0”,
            “@msg”:<forwardedMsg1>
        }
    ]
} ) ~C0
```

**Response**

```
auth_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”MSGS_BY_CONNS”, “ver”:”1.0”},
            “msgsByConns”: [
                {
                    "pairwiseDID": <pairwise DID>,
                    "msgs": [
                        {
                            “uid”:”<unique msg id>”
                            “statusCode”:”MS-101”,
                            “senderDID”:”<DID>”,
                            “type”:”<msg type>”,
                            “payload”:”<core payload msg>”
                            “refMsgId”:”<referenced msg id>”
                        },
                        ...,
                        ...
                    ]
                },
                ...,
                ...
            ]
        }
    ]
} ) ~B0 + A0bf~B0
```


## Update messages by connections

**Request**

POST <agency-url>/agency/msg

Note: In below message

* "pairwiseDIDs" is optional, if not given, it will query all pairwise DIDs
   Here pairwise DID refers to the DID which you would have supplied in 'CREATE_KEY' request for the pairwise connection.
* "uids" is optional, if not given, it will get all messages
* "statusCodes" is optional, if not given, msgs won't be filtered by status codes

```
forwardedMsg1 = auth_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”UPDATE_MSG_STATUS_BY_CONNS”, “ver”:”1.0”},
            “statusCode”:<status code to update with>,
            “uidsByConns”: [
                {
                    "pairwiseDID": "<pairwiseDID1>",
                    "uids": ["msg-uid1", "msg-uid2", ...]
                },
                ...,
                ...
            ]
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
            “@fwd”:“A0b0”,
            “@msg”:<forwardedMsg1>
        }
    ]
} ) ~C0
```

**Response**

```
auth_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”MSG_STATUS_UPDATED_BY_CONNS”, “ver”:”1.0”},
            “updatedUidsByConns”: [
                {
                    {
                        "pairwiseDID": "<pairwiseDID1>",
                        "uids": ["msg-uid1", "msg-uid2", ...]
                    },
                    ...,
                    ...
                },
                ...,
                ...
            ],
            "failed": [
                {
                    "pairwiseDID": "<pairwiseDID1>",
                    "statusCode": "<error-status-code>",
                    "statusMsg": "<error-status-description>"
                },
                ...,
                ...
            ]
        }
    ]
} ) ~B0 + A0bf~B0
```
