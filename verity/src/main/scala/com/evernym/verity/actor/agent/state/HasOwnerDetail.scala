//package com.evernym.verity.actor.agent.state
//
//import com.evernym.verity.protocol.engine.DID
//
////TODO refactor to immutable by...
//// (1) make vars private and create setters,
//// (2) convert to vals and make setters return new states
///**
//  * A trait meant to be mixed into the state object of an agent
//  */
//trait OwnerDetail {
//
//  /**
//   * self relationship DID
//   * in case of CAS/EAS (hosting cloud agents) : it is provided by edge agent during agent creation
//   * in case of VAS (hosting edge agents)      : it is not set ('agentPairwiseKeyDID' is used as ownerDID)
//   */
//  private var _mySelfRelDID: Option[DID] = None
//  def setMySelfRelDID(did: DID): Unit = _mySelfRelDID = Option(did)
//  def mySelfRelDID: Option[DID] = _mySelfRelDID
//
//  /**
//   * ownerAgentKeyDID is a DID belonging/related to the agent (UserAgent actor)
//   * created in CAS/EAS/VAS (edge/cloud).
//   * this is wrong design (agents should NOT have any DID associated with it)
//   */
//  private var _ownerAgentKeyDID: Option[DID] = None
//  def setOwnerAgentKeyDID(did: DID): Unit = _ownerAgentKeyDID = Option(did)
//  def ownerAgentKeyDID: Option[DID] = _ownerAgentKeyDID
//  def ownerAgentKeyDIDReq: DID = ownerAgentKeyDID.getOrElse(throw new RuntimeException("owner agent key DID not yet set"))
//
//}
//
//trait HasOwnerDetail {
//  type StateType <: OwnerDetail
//  def state: StateType
//}
