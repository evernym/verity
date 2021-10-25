# Owner onboarding APIs

These APIs are used by an identity owner (institution or individual) to onboard
themselves with an agency. The caller is public (outside the agency's private
network). The message structure examples are very high level.
See [here](api-msg-packaging.md#msg-packed-msg) for details about packaging.
See [notation reference](api-notation-reference.md) for an explanation of
symbols like `~E0 + F0~E0`.


### Get agency detail

##### Request

    GET <agency-url>/agency

##### Response
    
    {"DID":"<agency DID>", "verKey":"<agency DID verkey>"}

### Connect

##### Request

    POST <agency-url>/agency/msg

```
msgToBeForwarded = auth_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”CONNECT”, “ver”:”1.0”},
            “fromDID”:”F0”,
            “fromDIDVerKey”:”<F0’s ver key>”
        }
    ]
} ) ~E0 + F0~E0
```

##### Request body
```
anon_crypt ( {
    “bundled”: [
        {
          “@type”:{“name”:”FWD”, “ver”:”1.0”},
          “@fwd”:“E0”,
          “@msg”:<msgToBeForwarded>
        }
    ]
} ) ~E0
``` 
    
##### Response

```
auth_crypt ( { 
    “bundled”: [
        {
            “@type”:{“name”:”CONNECTED”, “ver”:”1.0”},
            “withPairwiseDID”:”E0f0”,
            “withPairwiseDIDVerKey”:”<E0f0’s verkey>”
        }
    ]
} ) ~F0 + E0~F0
```

## SignUp (Register)

##### Request

POST <agency-url>/agency/msg

```
msgToBeForwarded = auth_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”SIGNUP”, “ver”:”1.0”}
        }
    ]
} ) ~E0f0 + F0~E0f0
```

##### Request body
```
anon_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”FWD”, “ver”:”1.0”},
            “@fwd”:“E0f0”,
            “@msg”:<msgToBeForwarded>
        }
    ]
} ) ~E0
```     

##### Response

```
auth_crypt ( { 
    “bundled”: [
        {
            “@type”:{“name”:”SIGNED_UP”, “ver”:”1.0”}
        }
    ]
} ) ~F0 + E0f0~F0
```

### Provisioning (create agent)

##### Request

POST <agency-url>/agency/msg

```
msgToBeForwarded = auth_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”CREATE_AGENT”, “ver”:”1.0”}
        }
    ]
} ) ~E0f0 + F0~E0f0
```

##### Request body
```
anon_crypt ( {
    “bundled”: [
        {
            “@type”:{“name”:”FWD”, “ver”:”1.0”},
            “@fwd”:“E0f0”,
            “@msg”:<msgToBeForwarded>
        }
    ]
} ) ~E0
```

##### Response

```
auth_crypt ( { 
    “bundled”: [
        {
            “@type”:{“name”:”AGENT_CREATED”, “ver”:”1.0”},
            “withPairwiseDID”:”A0f0”,
            “withPairwiseDIDVerKey”:”<A0f0’s verkey>”
        }
    ]
} ) ~F0 + E0f0~F0
```