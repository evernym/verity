package com.evernym.verity.actor.agent.agency

import akka.event.LoggingReceive
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent._
import com.evernym.verity.actor.agent.msghandler.incoming.{ControlMsg, SignalMsgParam}
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.actor.agent.msghandler.ProcessUnpackedMsg
import com.evernym.verity.actor.agent.relationship.Tags.EDGE_AGENT_KEY
import com.evernym.verity.actor.agent.relationship.{DidDocBuilder, PairwiseRelationship, Relationship}
import com.evernym.verity.actor.agent.state._
import com.evernym.verity.actor.agent.state.base.{AgentStatePairwiseImplBase, AgentStateUpdateInterface}
import com.evernym.verity.actor.base.Done
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily.pairwise.AcceptConnReqMsg_MFV_0_6
import com.evernym.verity.agentmsg.msgpacker.{AgentBundledMsg, AgentMsgParseUtil, AgentMsgWrapper}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.engine.{DID, ParticipantId, VerKey, _}
import com.evernym.verity.protocol.protocols.connecting.common.ConnReqReceived
import com.evernym.verity.util.ParticipantUtil

import scala.concurrent.Future

/**
 The subset or shard of an agency's agent that is dedicated to
 managing one pairwise relationship between the agency and a user.
 */
class AgencyAgentPairwise(val agentActorContext: AgentActorContext)
  extends AgencyAgentCommon
    with AgencyAgentPairwiseStateUpdateImpl
    with PairwiseConnState
    with AgentSnapshotter[AgencyAgentPairwiseState] {

  type StateType = AgencyAgentPairwiseState
  var state = new AgencyAgentPairwiseState

  override final def receiveAgentCmd: Receive = commonCmdReceiver orElse cmdReceiver

  val cmdReceiver: Receive = LoggingReceive.withLabel("cmdReceiver") {
    case scke: SetupCreateKeyEndpoint   => handleSetupCreateKeyEndpoint(scke)
  }

  override def handleSpecificSignalMsgs: PartialFunction[SignalMsgParam, Future[Option[ControlMsg]]] = {
    case SignalMsgParam(crr: ConnReqReceived, _) => handleConnReqReceived(crr); Future.successful(None)
  }

  override final def receiveAgentEvent: Receive = eventReceiver orElse pairwiseConnEventReceiver

  val eventReceiver: Receive = {

    case ads: AgentDetailSet => handleSetupRelationship(
      ads.agentKeyDID, ads.agentKeyDIDVerKey, ads.forDID, ads.forDIDVerKey
    )

    //kept it for backward compatibility
    case ac:AgentCreated      =>
      if (state.relationship.isEmpty && ac.forDID.nonEmpty && ac.agentKeyDID.nonEmpty)
        handleSetupRelationship(ac.agentKeyDID, "", ac.forDID, "")
    case _ @ (_: OwnerSetForAgent | _: SignedUp) => //nothing to do, kept it for backward compatibility
  }

  def handleSetupRelationship(myPairwiseDID: DID, myPairwiseDIDVerKey: VerKey,
                              theirPairwiseDID: DID, theirPairwiseDIDVerKey: VerKey): Unit = {
    state = state.withThisAgentKeyId(myPairwiseDID)
    val myDidDoc =
      DidDocBuilder()
      .withDid(myPairwiseDID)
      .withAuthKey(myPairwiseDID, myPairwiseDIDVerKey, Set(EDGE_AGENT_KEY))
      .didDoc
    val theirDidDoc =
      DidDocBuilder()
        .withDid(theirPairwiseDID)
        .withAuthKey(theirPairwiseDID, theirPairwiseDIDVerKey)
        .didDoc
    val pairwiseRel = PairwiseRelationship.apply("pairwise", Option(myDidDoc), Option(theirDidDoc))
    state = state.withRelationship(pairwiseRel)
  }

  def handleSetupCreateKeyEndpoint(scke: SetupCreateKeyEndpoint): Unit = {
    scke.pid.foreach { pd =>
      writeAndApply(ProtocolIdDetailSet(pd.protoRef.msgFamilyName, pd.protoRef.msgFamilyVersion, pd.pinstId))
    }
    writeAndApply(
      AgentDetailSet(
        scke.forDIDPair.DID, scke.newAgentKeyDIDPair.DID,
        scke.forDIDPair.verKey, scke.newAgentKeyDIDPair.verKey
      )
    )

    val setRouteFut = setRoute(scke.newAgentKeyDIDPair.DID)
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
    val amw = AgentMsgWrapper(MPF_INDY_PACK, AgentBundledMsg(agentMsgs,
      state.thisAgentVerKey, None, None))
    sendToAgentMsgProcessor(ProcessUnpackedMsg(amw))
  }

  def authedMsgSenderVerKeys: Set[VerKey] = state.allAuthedVerKeys

  def prepareAgencyPairwiseDetailForActor(): Future[Any] = {
    agencyDidPairFut().flatMap { adp =>
      setAgentActorDetail(adp)
    }
  }

  override def stateDetailsFor: Future[String ?=> Parameter] = {
    def paramMap(agencyVerKey: VerKey): String ?=> Parameter = {
      case SELF_ID     => Parameter(SELF_ID, ParticipantUtil.participantId(state.myDid_!, None))
      case OTHER_ID    => Parameter(OTHER_ID, ParticipantUtil.participantId(state.theirDid_!, None))
    }
    for (
      agencyDidPair <- agencyDidPairFut()
    ) yield  {
      paramMap(agencyDidPair.verKey) orElse super.stateDetailsWithAgencyVerKey(agencyDidPair.verKey)
    }
  }

  // Here, "actor recovery" means the process of restoring
  // state from an event source.
  override def preAgentStateFix(): Future[Any] = {
    prepareAgencyPairwiseDetailForActor()
  }

  def ownerDID: Option[DID] = state.agencyDIDPair.map(_.DID)
  def ownerAgentKeyDIDPair: Option[DidPair] = state.agencyDIDPair

  override def senderParticipantId(senderVerKey: Option[VerKey]): ParticipantId = {
    val didDocs = state.relationship.flatMap(_.myDidDoc) ++ state.relationship.flatMap(_.theirDidDoc)
    didDocs.find(_.authorizedKeys_!.keys.exists(ak => senderVerKey.exists(svk => ak.containsVerKey(svk)))) match {
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

trait AgencyAgentPairwiseStateImpl extends AgentStatePairwiseImplBase {
  def domainId: DomainId = agencyDIDReq
}

trait AgencyAgentPairwiseStateUpdateImpl
  extends AgentStateUpdateInterface { this : AgencyAgentPairwise =>

  override def setAgentWalletId(walletId: String): Unit = {
    state = state.withAgentWalletId(walletId)
  }

  override def setAgencyDIDPair(didPair: DidPair): Unit = {
    state = state.withAgencyDIDPair(didPair)
  }

  def addThreadContextDetail(threadContext: ThreadContext): Unit = {
    state = state.withThreadContext(threadContext)
  }

  def removeThreadContext(pinstId: PinstId): Unit = {
    val afterRemoval = state.currentThreadContexts - pinstId
    state = state.withThreadContext(ThreadContext(afterRemoval))
  }

  def addPinst(pri: ProtocolRunningInstances): Unit = {
    state = state.withProtoInstances(pri)
  }

  def updateConnectionStatus(reqReceived: Boolean, answerStatusCode: String): Unit = {
    state = state.withConnectionStatus(ConnectionStatus(reqReceived, answerStatusCode))
  }

  def updateRelationship(rel: Relationship): Unit = {
    state = state.withRelationship(rel)
  }
}