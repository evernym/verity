The goal behind this package is to test verity 1 api flow
(CAS and EAS which uses libvcx based edge agent with 0.5 and 0.5 protocols for provisioning and connecting)

Mostly there are two actors (issuer and holder) involve to test the whole flow,
but as of now libvcx is single threaded and can't have multiple instances for 
multiple user at the same time, so in 'BaseVcxFlowSpec' there are few constructs/methods 
(for example: withIdentityOwner, withConnection) which helps test to use libvcx 
for multiple users and test code is expected to wisely use them else it may cause 
some test issues.
 
This package contains specs which tests different combination
of issuer and holder using same/different protocol versions.

`v1_0` = 'message packed' legacy messages (0.5 version)
`v2_0` = 'indy packed' legacy messages (0.6 version)
