package com.evernym.verity.actor.protocols

import akka.actor.ActorRef
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent._
import com.evernym.verity.actor.agent.msghandler.outgoing.SendSignalMsg
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, InternalMsgRouteParam, SetRoute}
import com.evernym.verity.actor.agent.user.GetSponsorRel
import com.evernym.verity.actor.base.{CoreActor, Done}
import com.evernym.verity.actor.persistence.HasActorResponseTimeout
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.constants.ActorNameConstants.ACTOR_TYPE_USER_AGENT_ACTOR
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.logging.LoggingUtil
import com.evernym.verity.protocol.actor.{ActorDriverGenParam, InitProtocolReq, MsgEnvelope}
import com.evernym.verity.protocol.engine
import com.evernym.verity.protocol.engine._
import com.evernym.verity.util.MsgIdProvider
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

/**
 * base class for a controller (client side) of the protocol actor caller
 * protocol actor depends
 *  1) on caller being an actor (at least as of today) for sending back outgoing messages (signal or protocol)
 *  2) it assumes the caller would be reachable by using routing information and
 *     depends on agent message router to send outgoing "protocol message" back to the caller
 *     (in real case agent actors, in tests, it would be this MockController actor)
 *     for further processing
 */
trait MockControllerActorBase
  extends CoreActor
    with ActorLaunchesProtocol
    with HasActorResponseTimeout
    with HasLogger {

  override def receiveCmd: Receive = {

    case sc: SetupController =>
      caller = sender()
      controllerDataOpt = Option(sc.data)
      agentActorContext.agentMsgRouter.execute(
        SetRoute(domainId, ActorAddressDetail(ACTOR_TYPE_USER_AGENT_ACTOR, entityId))).map { _ =>
        context.become(receivePostSetup)
        caller ! Done
      }
  }

  def postSetupCmdHandler: Receive = {
    case ipr: InitProtocolReq   => handleInitProtocolReq(ipr, None)
    case SendActorMsg(msg)      => sendToProtocolActor(msg)
    case SendControlMsg(msg)    => buildAndSendToProtocol(msg)
    case GetSponsorRel          => //TODO: decide what to do
  }

  /**
   * receives outgoing signal message and send it to the controller actor
   * @return
   */
  def receiveOutgoingSignalMsg: Receive = {
    case ssm: SendSignalMsg =>
      logger.info(s"[$actorId] received signal msg: " + ssm.msg)
      caller ! ssm.msg
  }

  /**
   * receives outgoing protocol message and send it to the
   * controller actor of their domain which then will send/forward it to its protocol actor
   */
  def receiveOutgoingProtoMsg: Receive = {
    case pom: ProtocolOutgoingMsg =>
      logger.info(s"[$actorId] about to send protocol outgoing msg: " + pom.msg)
      agentActorContext.agentMsgRouter.execute(
        InternalMsgRouteParam(controllerData.theirDID, ProtoIncomingMsg(pom.msg)))
  }

  /**
   * receives incoming protocol message and send it to corresponding protocol actor
   * @return
   */
  def receiveIncomingProtoMsg: Receive = {
    case pom: ProtoIncomingMsg =>
      logger.info(s"[$actorId] received protocol incoming msg: " + pom.msg)
      buildAndSendToProtocol(pom.msg)
  }

  val receivePostSetup: Receive =
    postSetupCmdHandler orElse
      receiveOutgoingSignalMsg orElse
      receiveOutgoingProtoMsg orElse
      receiveIncomingProtoMsg

  /**
   * keeps actor ref of the caller (test actor) to be used to send back any messages
   */
  var caller: ActorRef = ActorRef.noSender

  var controllerDataOpt: Option[ControllerData] = None
  def controllerData: ControllerData = controllerDataOpt.getOrElse(
    throw new RuntimeException("controller data not yet setup"))
  def agentActorContext: AgentActorContext = controllerData.agentActorContext
  def registeredProtocols: ProtocolRegistry[ActorDriverGenParam] = agentActorContext.protocolRegistry

  lazy val selfParticipantId: String = s"$domainId/$domainId"
  lazy val senderParticipantId: String =
    controllerDataOpt.flatMap(_.theirDIDOpt)
    .getOrElse(CommonSpecUtil.generateNewDid().DID)

  def sendToProtocolActor(msg: Any): Unit = {
    tellProtocolActor(controllerData.pinstIdPair, msg, self)
  }

  def buildAndSendToProtocol(msg: Any): Unit = {
    val typedMsg = controllerData.pinstIdPair.protoDef.msgFamily.typedMsg(msg)
    val msgEnvelope = buildMsgEnvelope(typedMsg)
    tellProtocol(controllerData.pinstIdPair, controllerData.threadContextDetail, msgEnvelope, self)
  }

  def buildMsgEnvelope[A](typedMsg: TypedMsgLike): MsgEnvelope = {
    MsgEnvelope(typedMsg.msg, typedMsg.msgType, selfParticipantId,
      senderParticipantId, Option(MsgIdProvider.getNewMsgId), Option(controllerData.threadContextDetail.threadId))
  }

  /**
   * this is generic implementation can be overridden by specific controller actor
   * @return
   */
  override def stateDetailsFor: Future[PartialFunction[String, engine.Parameter]] = Future {
    case SELF_ID                  => Parameter(SELF_ID, domainId)
    case OTHER_ID                 => Parameter(OTHER_ID, controllerData.theirDID)
    case MY_PAIRWISE_DID          => Parameter(MY_PAIRWISE_DID, CommonSpecUtil.generateNewDid().DID)
    case THEIR_PAIRWISE_DID       => Parameter(THEIR_PAIRWISE_DID, CommonSpecUtil.generateNewDid().DID)

    case NAME                     => Parameter(NAME, "name")
    case LOGO_URL                 => Parameter(LOGO_URL, "logo-url")
    case MY_PUBLIC_DID            => Parameter(MY_PUBLIC_DID, "my-public-did")
    case AGENCY_DID_VER_KEY       => Parameter(AGENCY_DID_VER_KEY, "agency-ver-key")
  }

  override def agentWalletIdReq: String = getClass.getSimpleName
  override def domainId: DomainId = controllerData.myDID
  override def logger: Logger = LoggingUtil.getLoggerByClass(getClass)
}

case class ControllerData(myDID: DID,
                          theirDIDOpt: Option[DID],
                          agentActorContext: AgentActorContext,
                          pinstIdPair: PinstIdPair,
                          threadContextDetailOpt: Option[ThreadContextDetail]=None) {

  var threadContextDetail: ThreadContextDetail = threadContextDetailOpt.getOrElse(
    ThreadContextDetail("thread-id-1", MsgPackFormat.MPF_INDY_PACK, TypeFormat.STANDARD_TYPE_FORMAT))
  def theirDID: DID = theirDIDOpt.getOrElse(throw new RuntimeException("other DID not supplied"))
}

case class SetupController(data: ControllerData) extends ActorMessage
case class SendActorMsg(msg: Any) extends ActorMessage
case class SendControlMsg(msg: Any) extends ActorMessage
case class ProtoIncomingMsg(msg: Any) extends ActorMessage
