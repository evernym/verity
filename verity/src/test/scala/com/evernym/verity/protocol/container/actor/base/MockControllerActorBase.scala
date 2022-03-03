package com.evernym.verity.protocol.container.actor.base

import akka.actor.ActorRef
import com.evernym.verity.actor.{ActorMessage, ForIdentifier}
import com.evernym.verity.actor.agent._
import com.evernym.verity.actor.agent.msghandler.incoming.ProcessSignalMsg
import com.evernym.verity.actor.agent.msghandler.outgoing.SendSignalMsg
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, InternalMsgRouteParam, SetRoute}
import com.evernym.verity.actor.agent.user.GetSponsorRel
import com.evernym.verity.actor.base.{CoreActor, Done}
import com.evernym.verity.actor.persistence.HasActorResponseTimeout
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.did.DidStr
import com.evernym.verity.did.didcomm.v1.messages.TypedMsgLike
import com.evernym.verity.observability.logs.{HasLogger, LoggingUtil}
import com.evernym.verity.protocol.container.actor.{ActorDriverGenParam, ActorProtocol, InitProtocolReq, MsgEnvelope}
import com.evernym.verity.protocol.{Control, engine}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.registry.{PinstIdPair, ProtocolRegistry}
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.{PublicIdentifier, PublicIdentifierCreated}
import com.evernym.verity.util.MsgIdProvider
import com.typesafe.scalalogging.Logger

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * base class for a controller (client side) actor, responsible for:
 * a) sending 'control' messages to its own ActorProtocolContainer
 * b) receiving 'signal' messages sent by its own ActorProtocolContainer
 * c) receiving 'protocol' messages sent by their (other participant) ActorProtocolContainer
 *
 * assumptions:
 * a) this mock controller actor is reachable by agent msg router to be able to send
 *    'protocol' messages to their ActorProtocolContainer
 */
abstract class MockControllerActorBase(val appConfig: AppConfig, agentActorContext: AgentActorContext, ec: ExecutionContext)
  extends CoreActor
    with ActorLaunchesProtocol
    with HasActorResponseTimeout
    with HasLogger {

  implicit val executionContext: ExecutionContext = ec

  override def receiveCmd: Receive = {

    case sc: SetupController =>
      caller = sender()
      controllerDataOpt = Option(sc.data)
      agentActorContext.agentMsgRouter.execute(
        SetRoute(domainId, ActorAddressDetail(MOCK_CONTROLLER_ACTOR_TYPE, entityId))).map { _ =>
        setNewReceiveBehaviour(receivePostSetup)
        caller ! Done
      }
  }

  def postSetupCmdHandler: Receive = {
    case ipr: InitProtocolReq      => handleInitProtocolReq(ipr, None)
    case scm: SendControlMsg       => buildAndSendToProtocol(scm.buildSendMsg, sendResp = true)
    case stp: SendToProtocolActor  => sendToProtocolActor(stp)

    case ssc: SendSystemCmd        => sendSystemCmd(ssc)
    case gpp: GetPinstId           => sendPinstId(gpp)
    case GetSponsorRel             => //TODO: decide what to do
  }

  /**
   * receives outgoing signal message and send it to the owner/caller of this 'controller actor'
   * @return
   */
  def receiveOutgoingSignalMsg: Receive = {
    case ssm: SendSignalMsg =>
      logger.info(s"[$actorId] received signal msg: " + ssm.msg)
      caller ! ssm.msg
  }

  /**
   * receives local signal messages to be handled by controller (the agent actor in real case)
   * @return
   */
  def receiveLocalSignalMsg: Receive = {
    case psm: ProcessSignalMsg =>
      logger.info(s"[$actorId] received signal msg: " + psm.smp.signalMsg)
      caller ! psm.smp.signalMsg
      if (handleLocalSignalMsg.isDefinedAt(psm.smp.signalMsg)) {
        handleLocalSignalMsg(psm.smp.signalMsg)
      }
  }

  def handleLocalSignalMsg: PartialFunction[Any, Unit] = {
    case pic: PublicIdentifierCreated => publicIdentifier = pic.identifier
  }

  /**
   * receives outgoing protocol message and send it to the
   * controller actor of their (other participant) domain which then will send/forward it to its protocol actor
   */
  def receiveOutgoingProtoMsg: Receive = {
    case pom: ProtocolOutgoingMsg =>
      logger.info(s"[$actorId] about to send protocol outgoing msg: " + pom.msg)
      agentActorContext.agentMsgRouter.execute(
        InternalMsgRouteParam(controllerData.theirDID, ProtoIncomingMsg(pom.msg, pom.threadContextDetail.threadId)))
  }

  /**
   * receives incoming protocol message (from other participant) and send it to
   * corresponding protocol actor on receiving side
   * @return
   */
  def receiveIncomingProtoMsg: Receive = {
    case pom: ProtoIncomingMsg =>
      logger.info(s"[$actorId] received protocol incoming msg: " + pom.msg)
      buildAndSendToProtocol(SendMsg(pom.msg, pom.threadId))
  }

  val receivePostSetup: Receive =
    postSetupCmdHandler orElse
      receiveOutgoingSignalMsg orElse
      receiveLocalSignalMsg orElse
      receiveOutgoingProtoMsg orElse
      receiveIncomingProtoMsg

  /**
   * keeps actor ref of the caller (test actor) to be used to send back any messages
   */
  var caller: ActorRef = ActorRef.noSender

  var controllerDataOpt: Option[ControllerData] = None
  def controllerData: ControllerData = controllerDataOpt.getOrElse(
    throw new RuntimeException("controller data not yet setup"))
  def registeredProtocols: ProtocolRegistry[ActorDriverGenParam] = agentActorContext.protocolRegistry

  lazy val selfParticipantId: String = s"$domainId/$domainId"
  lazy val senderParticipantId: String =
    controllerDataOpt.flatMap(_.theirDIDOpt)
    .getOrElse(CommonSpecUtil.generateNewDid().did)

  lazy val relationshipId: Option[RelationshipId] = Option(controllerData.myDID)

  def sendSystemCmd(ssc: SendSystemCmd): Unit = {
    ActorProtocol(ssc.pinstIdPair.protoDef)
      .region
      .tell(
        ForIdentifier(ssc.pinstIdPair.id, ssc.cmd),
        self
      )
  }

  def sendToProtocolActor(stp: SendToProtocolActor): Unit = {
    tellProtocolActor(stp.pinstIdPair, stp.msg, self)
  }

  def sendPinstId(gpp: GetPinstId): Unit = {
    val entry = registeredProtocols.find_!(gpp.protoDef.protoRef)
    val pinstId = resolvePinstId(
      gpp.protoDef,
      entry.pinstIdResol,
      relationshipId,
      gpp.threadId,
      None
    )
    sender() ! pinstId
  }

  def buildAndSendToProtocol(sm: SendMsg, sendResp: Boolean = false): Unit = {
    val pinstIdPair = getPinstIdPair(sm.msg, sm.threadId)
    val msgEnvelope = buildMsgEnvelope(sm.msg, sm.threadId)
    tellProtocol(pinstIdPair, sm.threadContextDetail, msgEnvelope, self)
    if (sendResp) {
      sender() ! pinstIdPair
    }
  }

  def getPinstIdPair(msg: Any, threadId: ThreadId): PinstIdPair = {
    val msgEnvelope = buildMsgEnvelope(msg, threadId)
    pinstIdForMsg_!(msgEnvelope.typedMsg, relationshipId, threadId)
  }

  def buildMsgEnvelope(msg: Any, threadId: ThreadId): MsgEnvelope = {
    val protoDef = protocolRegistry.`entryForUntypedMsg_!`(msg).protoDef
    val typedMsg = protoDef.msgFamily.typedMsg(msg)
    buildMsgEnvelope(typedMsg, threadId)
  }

  def buildMsgEnvelope(typedMsg: TypedMsgLike, threadId: ThreadId): MsgEnvelope = {
    MsgEnvelope(
      typedMsg.msg,
      typedMsg.msgType,
      selfParticipantId,
      senderParticipantId,
      Option(MsgIdProvider.getNewMsgId),
      Option(threadId)
    )
  }

  /**
   * this is base implementation which can be overridden by specific controller actor
   * @return
   */
  override def stateDetailsFor(protoRef: ProtoRef): Future[PartialFunction[String, engine.Parameter]] = Future {
    case SELF_ID                  => Parameter(SELF_ID, domainId)
    case OTHER_ID                 => Parameter(OTHER_ID, controllerData.theirDID)
    case MY_PAIRWISE_DID          => Parameter(MY_PAIRWISE_DID, CommonSpecUtil.generateNewDid().did)
    case THEIR_PAIRWISE_DID       => Parameter(THEIR_PAIRWISE_DID, CommonSpecUtil.generateNewDid().did)
    case DATA_RETENTION_POLICY    => Parameter(DATA_RETENTION_POLICY, "360d")

    case NAME                     => Parameter(NAME, "name")
    case LOGO_URL                 => Parameter(LOGO_URL, "logo-url")
    case MY_PUBLIC_DID            => Parameter(MY_PUBLIC_DID, "my-public-did")
    case AGENCY_DID_VER_KEY       => Parameter(AGENCY_DID_VER_KEY, "agency-ver-key")
    case DEFAULT_ENDORSER_DID     => Parameter(DEFAULT_ENDORSER_DID, "default-endorser-DID")

  }

  var publicIdentifier: PublicIdentifier = null

  override def agentWalletIdReq: String = controllerData.walletId
  override def domainId: DomainId = controllerData.myDID
  override def logger: Logger = LoggingUtil.getLoggerByClass(getClass)
}

object ControllerData {
  def apply(myDID: DidStr, theirDIDOpt: Option[DidStr]): ControllerData =
    ControllerData (UUID.randomUUID().toString, myDID, theirDIDOpt)
}
/**
 *
 * @param walletId wallet id
 * @param myDID my DID
 * @param theirDIDOpt optional, present/provided if you want to test their side of the protocol to
 */
case class ControllerData(walletId: String, myDID: DidStr, theirDIDOpt: Option[DidStr]) {
  def theirDID: DidStr = theirDIDOpt.getOrElse(throw new RuntimeException("their DID not supplied"))
}
case class SetupController(data: ControllerData) extends ActorMessage

case class SendToProtocolActor(msg: Any, pinstIdPair: PinstIdPair) extends ActorMessage
case class SendControlMsg(msg: Control, threadId: ThreadId = "thread-id-1") extends ActorMessage {
  def buildSendMsg: SendMsg = SendMsg(msg, threadId)
}

case class SendMsg(msg: Any, threadId: ThreadId) extends ActorMessage {
  var threadContextDetail: ThreadContextDetail =
    ThreadContextDetail(threadId, MsgPackFormat.MPF_INDY_PACK, TypeFormat.STANDARD_TYPE_FORMAT)
}

case class ProtoIncomingMsg(msg: Any, threadId: ThreadId) extends ActorMessage
case class GetPinstId(protoDef: ProtoDef, threadId: ThreadId) extends ActorMessage

case class SendSystemCmd(pinstIdPair: PinstIdPair, cmd: Any) extends ActorMessage