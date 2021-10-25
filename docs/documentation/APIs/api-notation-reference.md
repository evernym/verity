# API Notation Reference

### General
agency-url = agency's domain URL portion (for example: http://agency.evernym.com) 

### institution related

##### when admin bootstraps agency
`E0` = Institution agency’s DID

##### when institution owner (Faber for example) connects and creates its agent in agency
`F0` = Faber’s DID

`E0f0` = Institution agency’s pairwise DID for Faber

`A0f0` = Faber’s agent’s DID **(Soon, we'll stop having DIDs for agents)**


##### when institution owner creates pairwise key for new connection
`F0b` = Faber’s DID for Bob

`A0fb` = Faber’s agent's pairwise DID for Bob

### consumer agency related

##### when admin bootstrap agency
`C0` = Consumer agency’s DID

##### when consumer first time accepting invitation
`B0` = Bob’s DID (student of Faber)

`C0b0` = Consumer agency’s pairwise DID for Bob

`A0b0` = Bob’s agent’s DID **(Soon, we'll stop having DIDs for agents)**

##### when consumer accepts invitation
`B0f` = Bob’s DID for Faber

`A0bf` = Bob’s agent's pairwise DID for Faber


### other general notations
`anon_crypt` = `~E0` (meaning, sealed for `E0`)

`auth_crypt` = `~E0 + F0~E0`  (meaning, sealed for `E0`, encrypted from `F0` for `E0`)

`msg_packed` = [msgpack transformation](https://msgpack.org/index.html)

`verkey` = verification key