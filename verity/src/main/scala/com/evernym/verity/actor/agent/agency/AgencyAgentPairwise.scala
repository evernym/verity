package com.evernym.verity.actor.agent.agency

import akka.event.LoggingReceive
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent._
import com.evernym.verity.actor.agent.msghandler.incoming.{ControlMsg, SignalMsgFromDriver}
import com.evernym.verity.actor.agent.relationship.RelationshipUtil._
import com.evernym.verity.actor.agent.relationship.tags.EdgeAgentKeyTag
import com.evernym.verity.actor.agent.relationship.{DidDoc, PairwiseRelationship}
import com.evernym.verity.actor.agent.state._
import com.evernym.verity.actor.agent.user.AgentProvisioningDone
import com.evernym.verity.actor.persistence.Done
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily.pairwise.AcceptConnReqMsg_MFV_0_6
import com.evernym.verity.agentmsg.msgpacker.{AgentBundledMsg, AgentMsgParseUtil, AgentMsgWrapper}
import com.evernym.verity.cache.CacheQueryResponse
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.actor.ProtocolIdDetail
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.engine.{DID, ParticipantId, VerKey, _}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningDefinition
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.CompleteAgentProvisioning
import com.evernym.verity.protocol.protocols.connecting.common.ConnReqReceived
import com.evernym.verity.util.ParticipantUtil

import scala.concurrent.Future

/**
 The subset or shard of an agency's agent that is dedicated to
 managing one pairwise relationship between the agency and a user.
 */
class AgencyAgentPairwise(val agentActorContext: AgentActorContext)
  extends AgencyAgentCommon
    with PairwiseConnState {

  type StateType = State
  val state = new State
  /**
   * actor persistent state object
   */
  class State
    extends AgentStateBase
      with HasConnectionStatus {

    override type RelationshipType = PairwiseRelationship
    override def initialRel: PairwiseRelationship = PairwiseRelationship.empty
    override def updatedWithNewMyDidDoc(didDoc: DidDoc): PairwiseRelationship =
      relationship.copy(myDidDoc = Option(didDoc))
  }

  override final def receiveAgentCmd: Receive = commonCmdReceiver orElse cmdReceiver

  val cmdReceiver: Receive = LoggingReceive.withLabel("cmdReceiver") {
    case saw: SetAgentActorDetail      => setAgentActorDetail(saw)
    case scke: SetupCreateKeyEndpoint  => handleSetupCreateKeyEndpoint(scke)
    case apd: AgentProvisioningDone    =>
      //dhh Why is this message untyped?
      sendUntypedMsgToProtocol(
        CompleteAgentProvisioning(apd.selfDID, apd.agentVerKey),
        AgentProvisioningDefinition,
        apd.threadId
      )
  }

  override def handleSpecificSignalMsgs: PartialFunction[SignalMsgFromDriver, Future[Option[ControlMsg]]] = {
    case SignalMsgFromDriver(crr: ConnReqReceived, _, _, _) => handleConnReqReceived(crr); Future.successful(None)
  }

  override val receiveActorInitSpecificCmd: Receive = LoggingReceive.withLabel("receiveActorInitSpecificCmd") {
    case saw: SetAgentActorDetail => setAgentActorDetail(saw)
  }

  override final def receiveAgentEvent: Receive = eventReceiver orElse pairwiseConnReceiver

  val eventReceiver: Receive = {

    case ads: AgentDetailSet => handleSetupRelationship(ads.agentKeyDID, ads.forDID)

    //kept it for backward compatibility
    case ac:AgentCreated    => handleSetupRelationship(ac.agentKeyDID, ac.forDID)
    case _: SignedUp        => //nothing to do, kept it for backward compatibility
  }

  def handleSetupRelationship(myPairwiseDID: DID, theirPairwiseDID: DID): Unit = {
    state.setThisAgentKeyId(myPairwiseDID)
    val myDidDoc = state.prepareMyDidDoc(myPairwiseDID, myPairwiseDID, Set(EdgeAgentKeyTag))
    val theirDidDoc = state.prepareTheirDidDoc(theirPairwiseDID, theirPairwiseDID)
    val pairwiseRel = PairwiseRelationship.apply("pairwise", Option(myDidDoc), Option(theirDidDoc))
    state.setRelationship(pairwiseRel)
  }

  def handleSetupCreateKeyEndpoint(scke: SetupCreateKeyEndpoint): Unit = {
    scke.pid.foreach { pd =>
      writeAndApply(ProtocolIdDetailSet(pd.protoRef.msgFamilyName, pd.protoRef.msgFamilyVersion, pd.pinstId))
    }
    writeAndApply(AgentDetailSet(scke.forDID, scke.newAgentKeyDID))

    val setRouteFut = setRoute(scke.newAgentKeyDID)
    val sndr = sender()
    setRouteFut.map( _ =>
      sndr ! Done
    ).recover {
      case x: Exception => throw new RuntimeException("error while initializing agency agent pairwise endpoint: " + x.getMessage)
    }
  }

  //NOTE: this is self answering to the connection request
  def handleConnReqReceived(crp: ConnReqReceived): Unit = {
    writeAndApply(ConnectionStatusUpdated(reqReceived = true))
    val msg = DefaultMsgCodec.toJson(
      AcceptConnReqMsg_MFV_0_6(
        MSG_TYPE_DETAIL_ACCEPT_CONN_REQ,
        getNewMsgUniqueId,
        sendMsg = false,
        crp.inviteDetail.senderDetail,
        crp.inviteDetail.senderAgencyDetail,
        crp.inviteDetail.connReqId
      )
    )
    val agentMsgs = List(AgentMsgParseUtil.agentMsg(msg))
    val amw = AgentMsgWrapper(MPV_INDY_PACK, AgentBundledMsg(agentMsgs,
      state.thisAgentVerKey, None, None))
    handleAgentMsgWrapper(amw)
  }

  def authedMsgSenderVerKeys: Set[VerKey] = state.allAuthVerKeys.toSet

  def prepareAgencyPairwiseDetailForActor(): Future[Any] = {
    getAgencyDIDFut(req = true).mapTo[CacheQueryResponse].flatMap { cqr =>
      cqr.getAgencyDIDOpt.map { aDID =>
        setAgentActorDetail(aDID)
      }.getOrElse {
        Future.successful("agency agent not yet created")
      }
    }
  }

  override def stateDetailsFor: Future[String ?=> Parameter] = {
    def paramMap(agencyVerKey: VerKey): String ?=> Parameter = {
      case SELF_ID     => Parameter(SELF_ID, ParticipantUtil.participantId(state.myDid_!, None))
      case OTHER_ID    => Parameter(OTHER_ID, ParticipantUtil.participantId(state.theirDid_!, None))
    }
    for (
      agencyVerKey <- getAgencyVerKeyFut
    ) yield  {
      paramMap(agencyVerKey) orElse super.stateDetailsWithAgencyVerKey(agencyVerKey)
    }
  }

  // Here, "actor recovery" means the process of restoring
  // state from an event source.
  override def postActorRecoveryCompleted(): List[Future[Any]] = {
    List(prepareAgencyPairwiseDetailForActor())
  }

  /**
   * this function gets executed post successful actor recovery (meaning all events are applied to state)
   * the purpose of this function is to update any 'LegacyAuthorizedKey' to 'AuthorizedKey'
   */
  override def postSuccessfulActorRecovery(): Unit = {
    if (state.relationship.nonEmpty) {
      val updatedMyDidDoc = updatedDidDocWithMigratedAuthKeys(state.myDidDoc)
      val updatedTheirDidDoc = updatedDidDocWithMigratedAuthKeys(state.theirDidDoc)
      val updatedRel = state.relationship.copy(myDidDoc = updatedMyDidDoc, theirDidDoc = updatedTheirDidDoc)
      state.updateRelationship(updatedRel)
    }
  }

  def ownerDID: Option[DID] = state.agencyDID
  def ownerAgentKeyDID: Option[DID] = state.agencyDID

  override def userDIDForResourceUsageTracking(senderVerKey: Option[VerKey]): Option[DID] = state.theirDid
  override def relationshipState: PairwiseRelationship = state.relationship
  override def updateRelationship(rel: PairwiseRelationship): Unit = state.updateRelationship(rel)

  override def senderParticipantId(senderVerKey: Option[VerKey]): ParticipantId = {
    val didDocs = state.relationship.myDidDoc ++ state.relationship.theirDidDoc
    didDocs.find(_.authorizedKeys.keys.exists(ak => senderVerKey.exists(svk => ak.containsVerKey(svk)))) match {
      case Some (dd)  => ParticipantUtil.participantId(dd.did, None)
      case None       => throw new RuntimeException("unsupported use case")
    }
  }

  /**
    * there are different types of actors (agency agent, agency pairwise, user agent and user agent pairwise)
    * when we store the persistence detail, we store these unique id for each of them
    * which then used during routing to know which type of region actor to be used to route the message
    *
    * @return
    */
  override def actorTypeId: Int = ACTOR_TYPE_AGENCY_AGENT_PAIRWISE_ACTOR
}


/**
 *
 * @param newAgentKeyDID DID belonging to the new agent ver key
 * @param forDID pairwise DID for which new pairwise actor needs to be setup
 * @param mySelfRelDID my self relationship DID
 * @param ownerAgentKeyDID DID belonging to owner's agent's ver key
 * @param ownerAgentActorEntityId entity id of owner's agent actor
 * @param pid
 */
case class SetupCreateKeyEndpoint(
                                   newAgentKeyDID: DID,
                                   forDID: DID,
                                   mySelfRelDID: DID,
                                   ownerAgentKeyDID: Option[DID] = None,
                                   ownerAgentActorEntityId: Option[String]=None,
                                   pid: Option[ProtocolIdDetail]=None
                                 ) extends ActorMessageClass

trait SetupEndpoint extends ActorMessageClass {
  def ownerDID: DID
  def agentKeyDID: DID
}

case class SetupAgentEndpoint(
                               override val ownerDID: DID,
                               override val agentKeyDID: DID
                             ) extends SetupEndpoint

case class SetupAgentEndpoint_V_0_7 (
                                      threadId: ThreadId,
                                      override val ownerDID: DID,
                                      override val agentKeyDID: DID,
                                      requesterVerKey: VerKey,
                                      sponsorId: Option[String]=None,
                                      sponseeId: Option[String]=None
                                   ) extends SetupEndpoint
