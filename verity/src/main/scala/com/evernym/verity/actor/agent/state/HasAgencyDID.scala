//package com.evernym.verity.actor.agent.state
//
//import com.evernym.verity.protocol.engine.DID
//
///**
// * A trait meant to be mixed into the state object of an agent
// */
//trait AgencyDID {
//  private var _agencyDID: Option[DID] = None
//  def agencyDID: Option[DID] = _agencyDID
//  def setAgencyDID(did: DID): Unit = _agencyDID = Option(did)
//
//  def agencyDIDReq: DID = agencyDID.getOrElse(throw new RuntimeException("agency DID not available"))
//}
//
//trait HasAgencyDID {
//  type StateType <: AgencyDID
//  def state: StateType
//}
