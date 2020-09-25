# Glossary

* ownerDetail = AgentDetail(forDID, agentKeyDID)
  * forDID = DID for the given agent detail (this is self DID)
  * agentKeyDID = see below

* agentDetail = AgentDetail(forDID, agentKeyDID)
  * forDID = DID for the given agent detail (this is pairwise DID)
  * agentKeyDID = see below

* agentVerKey = agent's ver key

* agentKeyDID = DID associated with the agent's ver key
    * NOTE: Associating a DID to an agent is wrong/legacy design.

## Notes
 * for "agency agent" and "user agent" actors, 'ownerDetail' and 'agentDetail' would be always same
 
 * for "agency agent pairwise" and "user agent pairwise" actors,
    ownerDetail will be of "agency agent" or "user agent" actor
    agentDetail will be of the pairwise actor itself