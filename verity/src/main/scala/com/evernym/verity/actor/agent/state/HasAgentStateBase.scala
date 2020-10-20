package com.evernym.verity.actor.agent.state

import com.evernym.verity.actor.agent.agency.SponsorRel
import com.evernym.verity.actor.agent.relationship.Tags.AGENT_KEY_TAG
import com.evernym.verity.actor.agent.{ThreadContext, ThreadContextDetail}
import com.evernym.verity.actor.agent.relationship.{AuthorizedKeyLike, DidDoc, KeyId, Relationship}
import com.evernym.verity.protocol.engine.{DID, PinstId, ProtoDef, ProtoRef, VerKey}

/**
 * interface for agent's common state update
 * agent common/base classes will call below function to update agent's state
 */
trait AgentStateUpdateInterface {
  def setAgentWalletSeed(seed: String): Unit
  def setAgencyDID(did: DID): Unit
  def setSponsorRel(rel: SponsorRel): Unit
  def addThreadContextDetail(pinstId: PinstId, threadContextDetail: ThreadContextDetail): Unit
  def addPinst(protoRef: ProtoRef, pinstId: PinstId): Unit
  def addPinst(inst: (ProtoRef, PinstId)): Unit
}

/**
 * interface for agent's common state
 * agent common/base classes may/will use below members for reading/querying purposes.
 *
 */
trait AgentStateInterface {

  def relationshipOpt: Option[Relationship]
  def relationshipReq: Relationship = relationshipOpt.getOrElse(throw new RuntimeException("relationship not found"))

  def agentWalletSeed: Option[String]

  def agencyDID: Option[DID]
  def agencyDIDReq: DID = agencyDID.getOrElse(throw new RuntimeException("agency DID not available"))

  def sponsorRel: Option[SponsorRel]

  def threadContextDetail(pinstId: PinstId): ThreadContextDetail
  def threadContextsContains(pinstId: PinstId): Boolean

  def getPinstId(protoDef: ProtoDef): Option[PinstId]

  def myDidDoc: Option[DidDoc]
  def myDid: Option[DID]
  def myDid_! : DID

  def theirDidDoc: Option[DidDoc]
  def theirDid: Option[DID]

  def thisAgentKeyId: Option[KeyId]

  def thisAgentAuthKey: Option[AuthorizedKeyLike] = thisAgentKeyId.flatMap(keyId => relationshipOpt.flatMap(_.myDidDocAuthKeyById(keyId)))
  def thisAgentKeyDID: Option[KeyId] = thisAgentAuthKey.map(_.keyId)
  def thisAgentKeyDIDReq: DID = thisAgentKeyDID.getOrElse(throw new RuntimeException("this agent key id not found"))
  def thisAgentVerKey: Option[VerKey] = thisAgentAuthKey.filter(_.verKeyOpt.isDefined).map(_.verKey)
  def thisAgentVerKeyReq: VerKey = thisAgentVerKey.getOrElse(throw new RuntimeException("this agent ver key not found"))

  def theirAgentAuthKey: Option[AuthorizedKeyLike] = relationshipOpt.flatMap(_.theirDidDocAuthKeyByTag(AGENT_KEY_TAG))
  def theirAgentAuthKeyReq: AuthorizedKeyLike = theirAgentAuthKey.getOrElse(
    throw new RuntimeException("their agent auth key not yet set")
  )
  def theirAgentKeyDID: Option[DID] = theirAgentAuthKey.map(_.keyId)
  def theirAgentKeyDIDReq: DID = theirAgentKeyDID.getOrElse(throw new RuntimeException("their agent auth key not yet set"))
  def theirAgentVerKey: Option[VerKey] = theirAgentAuthKey.flatMap(_.verKeyOpt)
  def theirAgentVerKeyReq: VerKey = theirAgentVerKey.getOrElse(throw new RuntimeException("their agent ver key not yet set"))

  def serializedSize: Int
}

/**
 * this trait would be used for non proto buf state actors (AgencyAgentPairwise, UserAgent and UserAgentPairwise actor)
 */
trait LegacyAgentStateUpdateImpl extends AgentStateUpdateInterface {

  type StateType <: LegacyAgentStateImpl
  def state: StateType

  override def setAgentWalletSeed(seed: String): Unit =
    state.setAgentWalletSeed(seed)

  override def setAgencyDID(did: DID): Unit =
    state.setAgencyDID(did)

  override def setSponsorRel(rel: SponsorRel): Unit =
    state.setSponsorRel(rel)

  override def addThreadContextDetail(pinstId: PinstId, threadContextDetail: ThreadContextDetail): Unit =
    state.addThreadContextDetail(pinstId, threadContextDetail)

  override def addPinst(protoRef: ProtoRef, pinstId: PinstId): Unit =
    state.addPinst(protoRef, pinstId)

  override def addPinst(inst: (ProtoRef, PinstId)): Unit =
    state.addPinst(inst)
}

/**
 * implementation of 'AgentStateInterface' inherited by for non proto buf state actors
 * (AgencyAgentPairwise, UserAgent and UserAgentPairwise actor)
 */
trait LegacyAgentStateImpl extends AgentStateInterface with RelationshipState {

  override def relationshipOpt: Option[Relationship] = Option(relationship)

  private var _agentWalletSeed: Option[String] = None
  def agentWalletSeed: Option[String] = _agentWalletSeed
  def setAgentWalletSeed(seed: String): Unit = _agentWalletSeed = Option(seed)

  private var _agencyDID: Option[DID] = None
  def agencyDID: Option[DID] = _agencyDID
  def setAgencyDID(did: DID): Unit = _agencyDID = Option(did)

  private var _sponsorRel: Option[SponsorRel] = None
  def sponsorRel: Option[SponsorRel] = _sponsorRel
  def setSponsorRel(rel: SponsorRel): Unit = {
    _sponsorRel = _sponsorRel match {
      case None => Some(rel)
      case Some(x) => throw new UnsupportedOperationException(s"sponsor relationship can't be overridden: $x")
    }
  }

  private var _threadContexts: ThreadContext = ThreadContext()
  def addThreadContextDetail(pinstId: PinstId, threadContextDetail: ThreadContextDetail): Unit = {
    _threadContexts = _threadContexts.copy(contexts = _threadContexts.contexts + (pinstId -> threadContextDetail))
  }
  def threadContextDetail(pinstId: PinstId): ThreadContextDetail = _threadContexts.contexts(pinstId)
  def threadContextsContains(pinstId: PinstId): Boolean = _threadContexts.contexts.contains(pinstId)

  private var _instances: Map[ProtoRef, PinstId] = Map.empty
  def addPinst(protoRef: ProtoRef, pinstId: PinstId): Unit = addPinst(protoRef -> pinstId)
  def addPinst(inst: (ProtoRef, PinstId)): Unit = _instances = _instances + inst
  def getPinstId(protoDef: ProtoDef): Option[PinstId] = _instances.get(protoDef.msgFamily.protoRef)

}
