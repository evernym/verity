package com.evernym.verity.actor.protocols

import akka.actor.ActorRef
import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.actor.agent.{ActorLaunchesProtocol, AgentActorContext, MsgPackFormat, ThreadContextDetail, TypeFormat}
import com.evernym.verity.actor.agent.msghandler.outgoing.SendSignalMsg
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, InternalMsgRouteParam, SetRoute}
import com.evernym.verity.actor.persistence.{BaseNonPersistentActor, Done, HasActorResponseTimeout}
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.user.GetSponsorRel
import com.evernym.verity.constants.ActorNameConstants.ACTOR_TYPE_USER_AGENT_ACTOR
import com.evernym.verity.constants.InitParamConstants.{AGENCY_DID_VER_KEY, LOGO_URL, MY_PAIRWISE_DID, MY_PUBLIC_DID, NAME, OTHER_ID, SELF_ID, THEIR_PAIRWISE_DID}
import com.evernym.verity.logging.LoggingUtil
import com.evernym.verity.protocol.actor.{InitProtocolReq, MsgEnvelope}
import com.evernym.verity.protocol.engine
import com.evernym.verity.protocol.engine.{DID, DomainId, HasLogger, Parameter, PinstIdPair, ProtocolOutgoingMsg, TypedMsgLike}
import com.evernym.verity.util.MsgIdProvider
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

trait MockControllerActorBase
  extends BaseNonPersistentActor
    with ActorLaunchesProtocol
    with HasActorResponseTimeout
    with HasLogger {

  override def receiveCmd: Receive = {

    case cd: ControllerData =>
      caller = sender()
      controllerDetailOpt = Option(cd)
      agentActorContext.agentMsgRouter.execute(
        SetRoute(domainId, ActorAddressDetail(ACTOR_TYPE_USER_AGENT_ACTOR, entityId))).map { _ =>
        context.become(receivePostSetup)
        caller ! Done
      }
  }

  def postSetupCmdHandler: Receive = {
    case ipr: InitProtocolReq   => handleInitProtocolReq(ipr)
    case sm: SendControlMsg            => buildAndSendToProtocol(sm.msg)
    case GetSponsorRel          => //TODO: decide what to do
  }

  def receiveSignalMsg: Receive = {
    case ssm: SendSignalMsg =>
      logger.info(s"[$actorId] received signal msg: " + ssm.msg)
      caller ! ssm.msg
  }

  def receiveProtoOutgoingMsg: Receive = {
    case pom: ProtocolOutgoingMsg =>
      logger.info(s"[$actorId] about to send protocol outgoing msg: " + pom.msg)
      agentActorContext.agentMsgRouter.execute(
        InternalMsgRouteParam(controllerDetail.otherDID, ProtoIncomingMsg(pom.msg)))
  }

  def receiveProtoIncomingMsg: Receive = {
    case pom: ProtoIncomingMsg =>
      logger.info(s"[$actorId] received protocol incoming msg: " + pom.msg)
      buildAndSendToProtocol(pom.msg)
  }

  val receivePostSetup: Receive =
    postSetupCmdHandler orElse
      receiveSignalMsg orElse
      receiveProtoOutgoingMsg orElse
      receiveProtoIncomingMsg

  var caller: ActorRef = ActorRef.noSender
  var controllerDetailOpt: Option[ControllerData] = None
  def controllerDetail: ControllerData = controllerDetailOpt.getOrElse(
    throw new RuntimeException("controller data not yet setup"))
  def agentActorContext: AgentActorContext = controllerDetail.agentActorContext

  lazy val selfParticipantId: String = s"$domainId/$domainId"
  lazy val senderParticipantId: String = controllerDetailOpt.flatMap(_.theirDIDOpt).getOrElse(CommonSpecUtil.generateNewDid().DID)

  def buildAndSendToProtocol(msg: Any): Unit = {
    val typedMsg = controllerDetail.pinstIdPair.protoDef.msgFamily.typedMsg(msg)
    val msgEnvelope = buildMsgEnvelope(typedMsg)
    tellProtocol(controllerDetail.pinstIdPair, controllerDetail.threadContextDetail, msgEnvelope, self)
  }

  def buildMsgEnvelope[A](typedMsg: TypedMsgLike[A]): MsgEnvelope[A] = {
    MsgEnvelope(typedMsg.msg, typedMsg.msgType, selfParticipantId,
      senderParticipantId, Option(MsgIdProvider.getNewMsgId), Option(controllerDetail.threadContextDetail.threadId))
  }

  override def stateDetailsFor: Future[PartialFunction[String, engine.Parameter]] = Future {
    case SELF_ID                  => Parameter(SELF_ID, domainId)
    case OTHER_ID                 => Parameter(OTHER_ID, controllerDetail.otherDID)
    case MY_PAIRWISE_DID          => Parameter(MY_PAIRWISE_DID, CommonSpecUtil.generateNewDid().DID)
    case THEIR_PAIRWISE_DID       => Parameter(THEIR_PAIRWISE_DID, CommonSpecUtil.generateNewDid().DID)

    case NAME                     => Parameter(NAME, "name")
    case LOGO_URL                 => Parameter(LOGO_URL, "logo-url")
    case MY_PUBLIC_DID            => Parameter(MY_PUBLIC_DID, "my-public-did")
    case AGENCY_DID_VER_KEY       => Parameter(AGENCY_DID_VER_KEY, "agency-ver-key")
  }

  override def walletSeed: String = getClass.getSimpleName
  override def domainId: DomainId = controllerDetail.myDID
  override def logger: Logger = LoggingUtil.getLoggerByClass(getClass)
}

case class ControllerData(myDID: DID,
                          theirDIDOpt: Option[DID],
                          agentActorContext: AgentActorContext,
                          pinstIdPair: PinstIdPair,
                          threadContextDetailOpt: Option[ThreadContextDetail]=None) extends ActorMessageClass {

  var threadContextDetail: ThreadContextDetail = threadContextDetailOpt.getOrElse(
    ThreadContextDetail("thread-id-1", MsgPackFormat.MPF_INDY_PACK, TypeFormat.STANDARD_TYPE_FORMAT))

  def otherDID: DID = theirDIDOpt.getOrElse(throw new RuntimeException("other DID not supplied"))
}

case class SendControlMsg(msg: Any) extends ActorMessageClass
case class ProtoIncomingMsg(msg: Any) extends ActorMessageClass