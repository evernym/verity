//package com.evernym.verity.actor.agent.state
//
//import com.evernym.verity.actor.agent.AgentDetail
//import com.evernym.verity.protocol.engine.DID
//
///**
// * A trait meant to be mixed into the state object of an agent
// *
// * information of agents belonging to pairwise connections
// * right now only used from UserAgent
// */
//
//trait RelationshipAgents {
//
//  private var _relationshipAgents: List[AgentDetail] = List.empty
//  def relationshipAgents: List[AgentDetail] = _relationshipAgents
//  def addRelationshipAgent(ad: AgentDetail): Unit = {
//    _relationshipAgents = _relationshipAgents :+ ad
//  }
//
//  def relationshipAgentsContains(ad: AgentDetail): Boolean = _relationshipAgents.contains(ad)
//  def relationshipAgentsForDids: List[DID] = _relationshipAgents.map(_.forDID)
//  def relationshipAgentsForDidsSubtractedFrom(src: List[DID]): List[DID] = src diff relationshipAgentsForDids
//
//  def relationshipAgentsFindByForDid(did: DID): AgentDetail = _relationshipAgents.find(_.forDID == did).getOrElse(
//    throw new RuntimeException("relationship agent doesn't exists for DID: " + did)
//  )
//}
