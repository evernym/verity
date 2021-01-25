package com.evernym.verity.actor.agent.msghandler

import java.util.UUID

import akka.pattern.ask
import akka.actor.{ActorRef, ActorSystem}
import com.evernym.verity.Exceptions.{NotFoundErrorException, UnauthorisedErrorException}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_MSG_PACK, MPF_PLAIN, Unrecognized}
import com.evernym.verity.actor.agent.TypeFormat.STANDARD_TYPE_FORMAT
import com.evernym.verity.actor.agent.{ActorLaunchesProtocol, HasAgentActivity, MsgPackFormat, PayloadMetadata, ProtocolEngineExceptionHandler, ProtocolRunningInstances, SponsorRel, Thread, ThreadContextDetail, TypeFormat}
import com.evernym.verity.actor.agent.msghandler.incoming.{IncomingMsgParam, MsgForRelationship, ProcessPackedMsg, ProcessRestMsg, STOP_GAP_MsgTypeMapper, SignalMsgFromDriver}
import com.evernym.verity.actor.agent.msghandler.outgoing.{JsonMsg, OutgoingMsg, OutgoingMsgContext, OutgoingMsgParam, ProtocolSyncRespMsg, SendSignalMsg}
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.actor.agent.relationship.AuthorizedKeyLike
import com.evernym.verity.actor.agent.user.ComMethodDetail
import com.evernym.verity.Status._
import com.evernym.verity.actor.base.{CoreActorExtended, DoNotRecordLifeCycleMetrics, Done}
import com.evernym.verity.actor.msg_tracer.progress_tracker.{ProtoParam, TrackMsgParam, TrackingParam}
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageCommon
import com.evernym.verity.actor.wallet.{PackedMsg, VerifySigByVerKey}
import com.evernym.verity.agentmsg.buildAgentMsg
import com.evernym.verity.agentmsg.msgcodec.AgentJsonMsg
import com.evernym.verity.util.MsgIdProvider.getNewMsgId
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily.pairwise._
import com.evernym.verity.agentmsg.msgfamily.routing.FwdMsgHelper
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgPackagingUtil, AgentMsgWrapper, ParseParam, UnpackParam}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.Constants.{MSG_PACK_VERSION, RESOURCE_TYPE_MESSAGE, UNKNOWN_SENDER_PARTICIPANT_ID}
import com.evernym.verity.libindy.wallet.operation_executor.CryptoOpExecutor
import com.evernym.verity.logging.LoggingUtil
import com.evernym.verity.msg_tracer.MsgTraceProvider
import com.evernym.verity.msg_tracer.MsgTraceProvider._
import com.evernym.verity.protocol.actor.{ActorDriverGenParam, InitProtocolReq, MsgEnvelope, ServiceDecorator}
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.{DEFAULT_THREAD_ID, DID, DomainId, HasLogger, MsgId, MsgName, MsgType, Parameter, ParticipantId, PinstId, PinstIdPair, ProtoDef, ProtocolOutgoingMsg, ProtocolRegistry, RelationshipId, ThreadId, TypedMsg, TypedMsgLike, UnsupportedMessageType, VerKey}
import com.evernym.verity.protocol.protocols
import com.evernym.verity.protocol.protocols.connecting.v_0_6.{ConnectingProtoDef => ConnectingProtoDef_v_0_6}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.AgentCreated
import com.evernym.verity.protocol.protocols.connecting.common.GetInviteDetail
import com.evernym.verity.protocol.protocols.tokenizer.TokenizerMsgFamily.PushToken
import com.evernym.verity.push_notification.PushNotifData
import com.evernym.verity.util.{Base58Util, MsgUtil, ParticipantUtil, ReqMsgContext, RestAuthContext}
import com.evernym.verity.vault.{KeyParam, WalletAPIParam}
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Left, Success}


//Protocol msg flow with this AgentMsgProcessor in between
//  InMsg  (CTL/PRT)  ->  AgentActor   ->  AgentMsgProcessor  -> ActorProtocolContainer  \
//                                                                                        --> Protocol
//  OutMsg (SIG/PRT)  <-  AgentActor   <-  AgentMsgProcessor  <- ActorProtocolContainer  /

//Legacy msg flow with this AgentMsgProcessor in between
//  InMsg (0.5,0.6)   ->  AgentActor   \
//                                      --> AgentMsgProcessor
//  OutMsg (0.5,0.6)  <-  AgentActor   /

class AgentMsgProcessor(val appConfig: AppConfig,
                        val walletAPI: WalletAPI,
                        val agentMsgRouter: AgentMsgRouter,
                        val registeredProtocols: ProtocolRegistry[ActorDriverGenParam],
                        param: StateParam)
  extends CoreActorExtended
    with DoNotRecordLifeCycleMetrics
    with ProtocolEngineExceptionHandler
    with ActorLaunchesProtocol
    with ResourceUsageCommon
    with HasAgentActivity
    with MsgTraceProvider
    with HasLogger {

  val logger: Logger = LoggingUtil.getLoggerByName("AgentMsgProcessor")

  override def receiveCmd: Receive = incomingCmdReceiver orElse outgoingCmdReceiver

  def incomingCmdReceiver: Receive = {

    //incoming message from agent actors
    case ppm: ProcessPackedMsg        => handleProcessPackedMsg(ppm)
    case prm: ProcessRestMsg          => handleRestMsg(prm)
    case mfr: MsgForRelationship      => handleMsgForRel(mfr)

    //incoming message from agent actors for specific cases
    case ptm: ProcessTypedMsg         => sendTypedMsg(ptm)       //only from user agent pairwise
    case pum: ProcessUntypedMsgV1     => sendUntypedMsgToProtocolV1(pum.msg, pum.relationshipId)
    case pum: ProcessUntypedMsgV2     => sendUntypedMsgToProtocolV2(pum.msg, pum.protoDef, pum.threadId)

    //self sent messages (mostly to use async api call)
    case har: HandleAuthedRestMsg     => handledAuthedRestMsg(har.prm)
    case pum: ProcessUnpackedMsgV1    => handleUnpackedMsgV1(pum.amw, pum.msgThread, pum.rmc)
    case pum: ProcessUnpackedMsgV2    => handleUnpackedMsgV2(pum.amw, pum.internalPayloadWrapper, pum.rmc)

    //from/to protocol container actor
    case stp: SendToProtocolActor     => tellProtocolActor(stp.pinstIdPair, stp.msgEnvelope, stp.sndr)
    case ipr: InitProtocolReq         => handleInitProtocolReq(ipr, param.sponsorRel)
  }

  //protocol actor to this actor
  def outgoingCmdReceiver: Receive = {
    //[LEGACY] pinst -> actor protocol container (sendRespToCaller method) -> this actor
    case psrp: ProtocolSyncRespMsg    => handleProtocolSyncRespMsg(psrp)

    //pinst -> actor protocol container (send method) -> this actor
    case ProtocolOutgoingMsg(sd: ServiceDecorator, _, _, rmId, _, pDef, tcd) =>
      handleProtocolServiceDecorator(sd, rmId, pDef, tcd)

    //pinst -> actor protocol container (send method) -> this actor
    case pom: ProtocolOutgoingMsg     => handleProtocolOutgoingMsg(pom)

    //pinst -> actor driver (sendToForwarder method) -> this actor [to be sent to edge agent]
    case ssm: SendSignalMsg           => handleSignalOutgoingMsg(ssm)

    //pinst -> actor driver (processSignalMsg method) -> this actor [for further processing]
    case mfd: SignalMsgFromDriver     => sendToAgentActor(mfd)
  }

  /**
   * this is sent by driver/controller (who handles outgoing signal messages)
   * @param ssm send signal message
   */
  def handleSignalOutgoingMsg(ssm: SendSignalMsg): Unit = {
    logger.trace(s"sending signal msg to endpoint: $ssm")
    val outMsg = OutgoingMsg(
      msg = ssm.msg,
      to = ParticipantUtil.participantId(param.thisAgentAuthKey.keyId, Option(domainId)), //assumption, signal msgs are always sent to domain id participant
      from = param.selfParticipantId,   //assumption, signal msgs are always sent from self participant id
      pinstId = ssm.pinstId,
      protoDef = protocols.protoDef(ssm.protoRef),
      threadContextDetail = ssm.threadContextDetail,
      requestMsgId = ssm.requestMsgId
    )
    handleOutgoingMsg(outMsg, isSignalMsg = true)
  }

  /**
   * this is used when protocol container 'send msg api' sends 'ProtocolOutgoingMsg' to this agent actor
   * @param pom protocol outgoing message
   */
  def handleProtocolOutgoingMsg(pom: ProtocolOutgoingMsg): Unit = {
    logger.trace(s"sending protocol outgoing message: $pom")
    handleOutgoingMsg(OutgoingMsg(pom.msg, pom.to, pom.from, pom.pinstId,
      pom.protoDef, pom.threadContextDetail, Option(pom.requestMsgId)))
  }

  def handleOutgoingMsg[A](oam: OutgoingMsg[A], isSignalMsg: Boolean=false): Unit = {
    MsgProgressTracker.recordOutMsgPackagingStarted(
      inMsgParam = TrackMsgParam(msgId = oam.context.requestMsgId))

    logger.debug(s"preparing outgoing agent message: $oam")
    logger.debug(s"outgoing msg: native msg: " + oam.msg)

    val agentMsg = createAgentMsg(oam.msg, oam.protoDef,
      oam.context.threadContextDetail, isSignalMsg=isSignalMsg)
    logger.debug("outgoing msg: prepared agent msg: " + oam.context.threadContextDetail)

    if (!isSignalMsg) {
      /* When the AgencyAgentPairwise is creating a User Agent, activity should be tracked for the newly created agent
         not the AgencyAgentPairwise. The key in AgentCreated is the domainId of the new agent
      */
      val myDID = ParticipantUtil.agentId(oam.context.from)
      val selfDID = oam match {
        case OutgoingMsg(AgentCreated(selfDID, _), _, _, _) => selfDID
        case _                                              => domainId
      }
      logger.debug(s"outgoing msg: my participant DID: " + myDID)
      AgentActivityTracker.track(agentMsg.msgType.msgName, selfDID, Some(myDID))
    }

    prepareAndSendOutgoingMsg(agentMsg, oam.context.threadContextDetail, oam.context)
  }

  /**
   * handles outgoing message processing
   * @param agentJsonMsg agent json message to be sent
   * @param threadContext thread context to be used during packaging
   * @param omc: message context (msgId, to and from)
   */
  def prepareAndSendOutgoingMsg(agentJsonMsg: AgentJsonMsg,
                                threadContext: ThreadContextDetail,
                                omc: OutgoingMsgContext): Unit = {
    val agentJsonStr = if (threadContext.usesLegacyGenMsgWrapper) {
      AgentMsgPackagingUtil.buildPayloadWrapperMsg(agentJsonMsg.jsonStr, wrapperMsgType = agentJsonMsg.msgType.msgName)
    } else {
      AgentMsgPackagingUtil.buildAgentMsgJson(List(JsonMsg(agentJsonMsg.jsonStr)), threadContext.usesLegacyBundledMsgWrapper)
    }
    logger.debug(s"outgoing msg: json msg: " + agentJsonMsg)
    val toDID = ParticipantUtil.agentId(omc.to)
    logger.debug(s"outgoing msg: to participant DID: " + toDID)
    logger.debug(s"outgoing msg: final agent msg: " + agentJsonStr)

    // msg is sent as PLAIN json. Packing is done later if needed.
    val omp = OutgoingMsgParam(JsonMsg(agentJsonStr), Option(PayloadMetadata(agentJsonMsg.msgType, MPF_PLAIN)))
    sendToWaitingCallerOrSendToNextHop(omp, omc, agentJsonMsg.msgType, threadContext)
  }

  /**
   * once outgoing message is packed and ready,
   * it checks
   *   if there is a caller waiting for this response
   *        (which is true for legacy 0.5 version of messages or any other expecting a synchronous response)
   *   else sends it to appropriate agent (edge agent or to edge/cloud agent of the given connection)
   * @param omp
   * @param msgType
   * @param omc
   * @param threadContext
   */
  private def sendToWaitingCallerOrSendToNextHop(omp: OutgoingMsgParam,
                                                 omc: OutgoingMsgContext,
                                                 msgType: MsgType,
                                                 threadContext: ThreadContextDetail): Unit = {
    val respMsgId = getNewMsgId
    //tracking related
    omc.requestMsgId.foreach(updateAsyncReqContext(_, respMsgId, Option(msgType.msgName)))
    MsgProgressTracker.recordOutMsgPackagingFinished(
      inMsgParam = TrackMsgParam(msgId = omc.requestMsgId),
      outMsgParam = TrackMsgParam(msgId = Option(respMsgId), msgName = Option(msgType.msgName)))
    //tracking related
    omc.requestMsgId.map(reqMsgId => (reqMsgId, msgRespContext.get(reqMsgId))) match {
      case Some((rmid, Some(MsgRespContext(_, packForVerKey, Some(sar))))) =>
        // pack the message if needed.
        val updatedOmpFut = threadContext.msgPackFormat match {
          case MPF_PLAIN => Future.successful(omp)
          case MPF_INDY_PACK | MPF_MSG_PACK =>
            // we pack the message if needed.
            packOutgoingMsg(omp, omc.to, threadContext.msgPackFormat, packForVerKey.map(svk => KeyParam(Left(svk))))
          case Unrecognized(_) =>
            throw new RuntimeException("unsupported msgPackFormat: Unrecognized can't be used here")
        }
        updatedOmpFut.map { updatedOmp =>
          logger.debug(s"outgoing msg will be sent to waiting caller...")
          sendMsgToWaitingCaller(updatedOmp, rmid, sar)
        }
      case Some((_, _))  =>
        sendOutgoingMsg(omp, omc, respMsgId, msgType, threadContext)
      case None => ???
    }
  }

  def packOutgoingMsg(omp: OutgoingMsgParam,
                      toParticipantId: ParticipantId,
                      msgPackFormat: MsgPackFormat,
                      msgSpecificRecipVerKey: Option[KeyParam]=None): Future[OutgoingMsgParam] = {
    logger.debug(s"packing outgoing message: $omp to $msgPackFormat (msgSpecificRecipVerKeyOpt: $msgSpecificRecipVerKey")
    val toDID = ParticipantUtil.agentId(toParticipantId)
    val recipKeys = Set(msgSpecificRecipVerKey.getOrElse(KeyParam.fromDID(toDID)))
    msgExtractor.packAsync(msgPackFormat, omp.jsonMsg_!(), recipKeys).map { packedMsg =>
      OutgoingMsgParam(packedMsg, omp.metadata.map(x => x.copy(msgPackFormatStr = msgPackFormat.toString)))
    }
  }

  def sendOutgoingMsg(omp: OutgoingMsgParam,
                      omc: OutgoingMsgContext,
                      msgId: MsgId,
                      msgType: MsgType,
                      threadContext: ThreadContextDetail): Unit = {
    val thread = Option(Thread(Option(threadContext.threadId)))
    logger.debug("sending outgoing msg => self participant id: " + param.selfParticipantId)
    logger.debug("sending outgoing => toParticipantId: " + omc.to)
    val (result, nextHop) = if (ParticipantUtil.DID(param.selfParticipantId) == ParticipantUtil.DID(omc.to)) {
      val fut = msgType match {
        // These signals should not be stored because of legacy reasons.
        case mt: MsgType if isLegacySignalMsgNotToBeStored(mt) =>
          askToAgentActor(SendUnStoredMsgToMyDomain(omp))
        // Other signals go regularly.
        case _ =>
          askToAgentActor(StoreAndSendMsgToMyDomain(omp, msgId, msgType.msgName, ParticipantUtil.DID(omc.from), thread))
      }
      (fut, NEXT_HOP_MY_EDGE_AGENT)
    } else {
      // between cloud agents, we don't support sending MPF_PLAIN messages, so default to MPF_INDY_PACK in that case
      val msgPackFormat =  threadContext.msgPackFormat match {
        case MPF_PLAIN => MPF_INDY_PACK
        case other => other
      }

      // pack the message
      val fut = packOutgoingMsg(omp, omc.to, msgPackFormat).map { outgoingMsg =>
        logger.debug(s"outgoing msg will be stored and sent ...")
        askToAgentActor(StoreAndSendMsgToTheirDomain(
          outgoingMsg, msgId, msgType.msgName, ParticipantUtil.DID(omc.from), thread))
      }
      (fut, NEXT_HOP_THEIR_ROUTING_SERVICE)
    }
    MsgTracerProvider.recordMetricsForAsyncRespMsgId(msgId, nextHop)   //tracing related
    withRespMsgId(msgId, { arc =>
      result.onComplete {
        case Success(_) => MsgProgressTracker.recordMsgSentToNextHop(nextHop, arc)
        case Failure(e) => MsgProgressTracker.recordMsgSendingFailed(nextHop, e.getMessage, arc)
      }
    })
  }

  /**
   * this is a workaround to handle scenarios with legacy messages where we do wanted to send response messages
   * as a signal message but those are already stored so we wanted to avoid re-storing them again.
   * once we move to newer versions of connecting protocols and deprecate support of these legacy protocols
   * we should remove this code too.
   * @param msgType msg type
   * @return
   */
  def isLegacySignalMsgNotToBeStored(msgType: MsgType): Boolean = {
    val legacySignalMsgTypes =
      List(
        MSG_TYPE_CONN_REQ_RESP,
        MSG_TYPE_CONN_REQ_ACCEPTED,
        MSG_TYPE_CONN_REQ_REDIRECTED
      ).map(ConnectingProtoDef_v_0_6.msgFamily.msgType)

    if (legacySignalMsgTypes.contains(msgType)) true
    else false
  }

  /**
   * this is only for legacy agent messages which used to expect synchronous responses
   * @param psrm protocol synchronous response message
   */
  def handleProtocolSyncRespMsg(psrm: ProtocolSyncRespMsg): Unit = {
    psrm.requestMsgId.foreach { requestMsgId =>
      msgRespContext.get(requestMsgId).flatMap(_.senderActorRef).foreach { senderActorRef =>
        sendMsgToWaitingCaller(psrm.msg, requestMsgId, senderActorRef)
      }
    }
  }

  private def sendMsgToWaitingCaller(om: Any, reqMsgId: MsgId, sar: ActorRef): Unit = {
    msgRespContext = msgRespContext.filterNot(_._1 == reqMsgId)
    val msg = om match {
      case OutgoingMsgParam(om, _)   => om
      case other                     => other
    }
    sar ! msg
    MsgTracerProvider.recordMetricsForAsyncReqMsgId(reqMsgId, NEXT_HOP_MY_EDGE_AGENT_SYNC)   //tracing related
    withReqMsgId(reqMsgId, { arc =>
      MsgProgressTracker.recordMsgSentToNextHop(NEXT_HOP_MY_EDGE_AGENT_SYNC, arc)
    })
  }

  def handleProtocolServiceDecorator(sd: ServiceDecorator,
                                     requestMsgId: MsgId,
                                     protoDef: ProtoDef,
                                     tcd: ThreadContextDetail): Unit = {
    val agentMsg: AgentJsonMsg = createAgentMsg(sd.msg, protoDef,
      tcd, Option(TypeFormat.STANDARD_TYPE_FORMAT))

    sd match {
      case pushToken: PushToken =>
        val pnd = PushNotifData(requestMsgId, agentMsg.msgType.msgName, sendAsAlertPushNotif = true, Map.empty,
          Map("type" -> agentMsg.msgType.msgName, "msg" -> agentMsg.jsonStr))
        sendToAgentActor(SendPushNotif(Set(sd.deliveryMethod), pnd, Some(pushToken.msg.sponsorId)))
      case x => throw new RuntimeException("unsupported Service Decorator: " + x)
    }
  }

  def createAgentMsg(msg: Any,
                     protoDef: ProtoDef,
                     threadContextDetail: ThreadContextDetail,
                     msgTypeFormat: Option[TypeFormat]=None,
                     isSignalMsg: Boolean=false): AgentJsonMsg = {

    def getNewMsgId: MsgId = UUID.randomUUID().toString

    val (msgId, mtf, msgOrders) = {
      val mId = if (threadContextDetail.msgOrders.exists(_.senderOrder == 0)
        && threadContextDetail.msgOrders.exists(_.receivedOrders.isEmpty) ){
        //this is temporary workaround to solve an issue between how
        // thread id is determined by libvcx (and may be by other third parties) vs verity/agency
        // here, we are basically checking if this msg is 'first' protocol msg and in that case
        // the @id of the msg is assigned the thread id itself
        threadContextDetail.threadId
      } else {
        getNewMsgId
      }
      (mId, msgTypeFormat.getOrElse(threadContextDetail.msgTypeFormat), threadContextDetail.msgOrders)
    }

    //need to find better way to handle this
    //during connections protocol, when first message 'request' is received from other side,
    //that participant is unknown and hence it is stored as 'unknown_sender_participant_id' in the thread context
    //and when it responds with 'response' message, it just adds that in thread object
    //but for recipient it may look unfamiliar and for now filtering it.
    val updatedMsgOrders = msgOrders.map { pmd =>
      pmd.copy(receivedOrders = pmd.receivedOrders.filter(_._1 != UNKNOWN_SENDER_PARTICIPANT_ID))
    }
    buildAgentMsg(msg, msgId, threadContextDetail.threadId, protoDef, mtf, updatedMsgOrders)
  }

  def handleProcessPackedMsg(implicit ppm: ProcessPackedMsg): Unit = {
    val sndr = sender()
    // flow diagram: fwd + ctl + proto + legacy, step 7 -- Receive and decrypt.
    //msg progress tracking related
    MsgProgressTracker.recordMsgReceivedByAgent(getClass.getSimpleName, param.trackingParam)(ppm.reqMsgContext)
    logger.debug(s"incoming packed msg: " + ppm.packedMsg.msg)
    msgExtractor.unpackAsync(ppm.packedMsg, unpackParam = UnpackParam(ParseParam(useInsideMsgIfPresent = true))).map { amw =>
      //msg progress tracking related
      MsgProgressTracker.recordMsgUnpackedByAgent(getClass.getSimpleName,
        inMsgParam = TrackMsgParam(msgName = Option(amw.msgType.msgName)))(ppm.reqMsgContext)
      logger.debug(s"incoming unpacked (mpf: ${amw.msgPackFormat}) msg: " + amw)
      preMsgProcessing(amw.msgType, amw.senderVerKey)(ppm.reqMsgContext)
      self.tell(ProcessUnpackedMsgV1(amw, ppm.reqMsgContext, ppm.msgThread), sndr)
    }.recover {
      case e =>
        handleException(convertProtoEngineException(e), sndr)
    }
  }

  /**
   * handles incoming rest messages
   * @param prm rest message param
   */
  def handleRestMsg(prm: ProcessRestMsg): Unit = {
    //msg progress tracking related
    MsgProgressTracker.recordMsgReceivedByAgent(getClass.getSimpleName,
      param.trackingParam,
      inMsgParam = TrackMsgParam(msgName = Option(prm.restMsgContext.msgType.msgName)))(prm.restMsgContext.reqMsgContext)
    logger.debug(s"incoming rest msg: " + prm.msg)
    val sndr = sender()
    verifySignature(prm.restMsgContext.auth).onComplete {
      case Success(true)  => self.tell(HandleAuthedRestMsg(prm), sndr)
      case Success(false) => handleException(new UnauthorisedErrorException, sndr)
      case Failure(ex)    => handleException(ex, sndr)
    }
  }

  def handledAuthedRestMsg(prm: ProcessRestMsg): Unit = {
    logger.debug(s"processing authorized rest msg: " + prm.msg)
    preMsgProcessing(prm.restMsgContext.msgType, Option(prm.restMsgContext.auth.verKey))(prm.restMsgContext.reqMsgContext)
    val imp = IncomingMsgParam(ProcessRestMsg(prm.msg, prm.restMsgContext), prm.restMsgContext.msgType)
    val amw = imp.msgToBeProcessed
    implicit val reqMsgContext: ReqMsgContext = buildReqMsgContext(amw, prm.restMsgContext.reqMsgContext)
    try {
      extractMsgAndSendToProtocol(imp, prm.restMsgContext.thread)(prm.restMsgContext.reqMsgContext)
    } catch  {
      case e @ (_: NotFoundErrorException) =>
        forwardToAgentActor(UnhandledMsg(amw, reqMsgContext, e))
    }
  }

  /**
   * handles message for relationship handled by this actor (pairwise actor)
   * which is sent from user/agency agent actor.
   * @param mfr message for relationship
   */
  def handleMsgForRel(mfr: MsgForRelationship): Unit = {
    // flow diagram: ctl + proto, step 13
    logger.debug(s"msg for relationship received : " + mfr)
    val tm = typedMsg(mfr.msgToBeSent)
    mfr.reqMsgContext.foreach { rmc: ReqMsgContext =>
      MsgProgressTracker.recordMsgReceivedByAgent(getClass.getSimpleName,
        trackingParam = param.trackingParam,
        inMsgParam = TrackMsgParam(msgName = Option(tm.msgType.msgName)))(rmc)
    }
    sendTypedMsgToProtocol(tm, param.relationshipId, mfr.threadId, mfr.senderParticipantId,
      mfr.msgRespConfig, mfr.msgPackFormat, mfr.msgTypeDeclarationFormat)(mfr.reqMsgContext.getOrElse(ReqMsgContext()))
  }

  protected def sendTypedMsg(ptm: ProcessTypedMsg): Unit = {
    sendTypedMsgToProtocol(ptm.tmsg,
      ptm.relationshipId,
      ptm.threadId,
      ptm.senderParticipantId,
      ptm.msgRespConfig,
      ptm.msgPackFormat,
      ptm.msgTypeDeclarationFormat,
      ptm.usesLegacyGenMsgWrapper,
      ptm.usesLegacyBundledMsgWrapper)
  }

  protected def sendUntypedMsgToProtocolV1(msg: Any,
                                           relationshipId: Option[RelationshipId]): Unit = {
    val tm = typedMsg(msg)
    sendTypedMsgToProtocol(tm, relationshipId, DEFAULT_THREAD_ID, UNKNOWN_SENDER_PARTICIPANT_ID,
      Option(MsgRespConfig(isSyncReq(msg))), None, None)
  }

  def isSyncReq(msg: Any): Boolean = {
    msg match {
      case _: GetInviteDetail => true
      case _ => false
    }
  }

  //dhh What does an "untyped message" mean?
  //this overloaded method would be only used to send untyped msgs to protocol
  protected def sendUntypedMsgToProtocolV2(msg: Any,
                                           protoDef: ProtoDef,
                                           threadId: ThreadId = DEFAULT_THREAD_ID,
                                           msgRespConfig: MsgRespConfig = MsgRespConfig(isSyncReq = false),
                                           msgPackFormat: Option[MsgPackFormat]=None,
                                           msgTypeDeclarationFormat: Option[TypeFormat]=None,
                                           usesLegacyGenMsgWrapper: Boolean=false,
                                           usesLegacyBundledMsgWrapper: Boolean=false): Unit = {
    val typedMsg = protoDef.msgFamily.typedMsg(msg)
    sendTypedMsgToProtocol(
      typedMsg,
      param.relationshipId,
      threadId,
      param.selfParticipantId,
      Option(msgRespConfig),
      msgPackFormat,
      msgTypeDeclarationFormat,
      usesLegacyGenMsgWrapper,
      usesLegacyBundledMsgWrapper
    )
  }

  protected def verifySignature(senderAuth: RestAuthContext): Future[Boolean] = {
    Base58Util.decode(senderAuth.signature) match {
      case Success(signature) =>
        val toVerify = VerifySigByVerKey(senderAuth.verKey, senderAuth.verKey.getBytes, signature)
        CryptoOpExecutor.verifySig(toVerify).map(_.verified)
      case Failure(_)         => throw new UnauthorisedErrorException
    }
  }

  def handleUnpackedMsgV1(amw: AgentMsgWrapper,
                          msgThread: Option[Thread]=None,
                          reqMsgContext: ReqMsgContext = ReqMsgContext()): Unit = {
    implicit val rmc: ReqMsgContext = buildReqMsgContext(amw, reqMsgContext)
    try {
      extractMsgAndSendToProtocol(IncomingMsgParam(amw, amw.headAgentMsgDetail.msgType), msgThread)
    } catch {
      case e: NotFoundErrorException =>   //no protocol found for the inc
        val sndr = sender()
        internalPayloadWrapper(amw).map {
          case Some(dp) =>
            self.tell(ProcessUnpackedMsgV2(amw, dp, rmc, e), sndr)
          case None     =>
            forwardToAgentActor(UnhandledMsg(amw, rmc, e), sndr)
        }
    }
  }

  def handleUnpackedMsgV2(outerAgentMsgWrapper: AgentMsgWrapper,
                          internalPayloadWrapper: InternalPayloadWrapper,
                          rmc: ReqMsgContext = ReqMsgContext()): Unit = {
    //NOTE: this reqMsgContext is to pass some msg context information (like client's ip address, msg sender ver key etc)
    //which was being used by existing agent message
    implicit val reqMsgContext: ReqMsgContext = buildReqMsgContext(outerAgentMsgWrapper, rmc)
    internalPayloadWrapper.decryptedMsg match {
        //NOTE: don't change the order of below 'case' clauses else things may not work properly

      case Some(amw) =>
        //given internal payload (in agent msg wrapper) got decrypted by this agent
        //and hence asked to process that payload instead
        extractMsgAndSendToProtocol(
          IncomingMsgParam(amw, amw.headAgentMsgDetail.msgType),
          internalPayloadWrapper.payload.thread)
      case None if routingMsgHandler(reqMsgContext).isDefinedAt(outerAgentMsgWrapper) =>
        //given internal payload (in outerAgentMsgWrapper) didn't get decrypted by this agent
        // mostly it should be destined for edge agent to be decrypted
        // so check if it is a forward message and process it if it is
        routingMsgHandler(reqMsgContext)(outerAgentMsgWrapper)
      case _ =>
        forwardToAgentActor(UnhandledMsg(outerAgentMsgWrapper, rmc, new NotFoundErrorException(UNSUPPORTED_MSG_TYPE.statusCode)))
    }
  }

  def extractMsgAndSendToProtocol(givenImp: IncomingMsgParam,
                                  msgThread: Option[Thread]=None)
                                 (implicit rmc: ReqMsgContext = ReqMsgContext()): Unit = {
    try {
      import scala.language.existentials
      // flow diagram: ctl + proto, step 9 -- add context to actor if sender expects sync response.

      // THIS BELOW LINE IS STOPGAP WORKAROUND to support connect.me using vcx version 0.8.70229609
      // THIS IS A STOPGAP AND SHOULD NOT BE EXPANDED
      val imp = STOP_GAP_MsgTypeMapper.changedMsgParam(givenImp)

      val (msgToBeSent: TypedMsg, threadId: ThreadId, forRelationship, respDetail: Option[MsgRespConfig]) =
        (imp.msgType.familyName, imp.msgType.familyVersion, imp.msgType.msgName) match {

          //this is special case where connecting protocols (0.5 & 0.6)
          // still uses 'amw' as inputs and expects synchronous response
          case (MSG_FAMILY_CONNECTING, MFV_0_5 | MFV_0_6, msgName)                   =>
            val msgRespConf = msgName match {
              case MSG_TYPE_CONNECTING_GET_STATUS => if (imp.isSync(default = false)) Option(MsgRespConfig(isSyncReq = true)) else None
              case _ => if (imp.isSync(default = true)) Option(MsgRespConfig(isSyncReq = true)) else None
            }
            val (_, _, _, rd) = extract(imp, msgRespConf)
            (TypedMsg(imp.msgToBeProcessed, imp.msgType), DEFAULT_THREAD_ID, None, rd)

          case (MSG_FAMILY_AGENT_PROVISIONING, MFV_0_7, "create-edge-agent")         =>
            extract(imp, Option(MsgRespConfig(isSyncReq = true, imp.senderVerKey)))

          case (MSG_FAMILY_TOKEN_PROVISIONING, MFV_0_1, "get-token")                 =>
            extract(imp, Option(MsgRespConfig(isSyncReq = true, imp.senderVerKey)))

          //this is special case where agent provisioning protocols (0.5 & 0.6)
          // uses native messages but expects synchronous response
          case (MSG_FAMILY_AGENT_PROVISIONING, _, _)                                 =>
            extract(imp, Option(MsgRespConfig(isSyncReq = true)))

          case (MSG_FAMILY_DEAD_DROP, MFV_0_1_0, MSG_TYPE_DEAD_DROP_RETRIEVE)        =>
            extract(imp, Option(MsgRespConfig(isSyncReq = true, imp.senderVerKey)))

          case (MSG_FAMILY_WALLET_BACKUP, MFV_0_1_0, MSG_TYPE_WALLET_BACKUP_RESTORE) =>
            extract(imp, Option(MsgRespConfig(isSyncReq = true, imp.senderVerKey)))

          case (_, _, _)                                                             =>
            val msgRespConfig = if (imp.isSync(false)) Option(MsgRespConfig(isSyncReq = true)) else None
            extract(imp, msgRespConfig, msgThread)

          case _ => throw new UnsupportedMessageType(imp, List.empty)
        }

      val senderPartiId = param.senderParticipantId(imp.senderVerKey)

      if (forRelationship.isDefined && forRelationship != param.relationshipId) {
        forRelationship.foreach { relId =>
          val msgForRel = MsgForRelationship(msgToBeSent.msg, threadId, senderPartiId,
            imp.msgPackFormat, imp.msgFormat, respDetail, Option(rmc))
          // flow diagram: ctl.pairwise + proto.pairwise, step 10 -- Handle msg for specific connection.
          agentMsgRouter.forward(InternalMsgRouteParam(relId, msgForRel), sender())
        }
      } else {
        // flow diagram: ctl.self, step 10 -- Handle msg for self relationship.
        sendTypedMsgToProtocol(msgToBeSent, param.relationshipId, threadId, senderPartiId,
          respDetail, imp.msgPackFormat,
          imp.msgFormat, imp.usesLegacyGenMsgWrapper, imp.usesLegacyBundledMsgWrapper
        )
      }
    } catch protoExceptionHandler
  }

  def extract(imp: IncomingMsgParam, msgRespDetail: Option[MsgRespConfig], msgThread: Option[Thread]=None):
  (TypedMsg, ThreadId, Option[DID], Option[MsgRespConfig]) = {
    val m = msgExtractor.extract(imp.msgToBeProcessed, imp.msgPackFormatReq, imp.msgType)
    val tmsg = TypedMsg(m.msg, imp.msgType)
    val thId = msgThread.flatMap(_.thid).getOrElse(m.meta.threadId)
    (tmsg, thId, m.meta.forRelationship, msgRespDetail)
  }

  protected def sendTypedMsgToProtocol(tmsg: TypedMsgLike,
                                       relationshipId: Option[RelationshipId],
                                       threadId: ThreadId,
                                       senderParticipantId: ParticipantId,
                                       msgRespConfig: Option[MsgRespConfig],
                                       msgPackFormat: Option[MsgPackFormat],
                                       msgTypeDeclarationFormat: Option[TypeFormat],
                                       usesLegacyGenMsgWrapper: Boolean=false,
                                       usesLegacyBundledMsgWrapper: Boolean=false)
                                        (implicit rmc: ReqMsgContext = ReqMsgContext()): Unit = {
    // flow diagram: ctl + proto, step 14
    val msgEnvelope = buildMsgEnvelope(tmsg, threadId, senderParticipantId)
    val pair = pinstIdForMsg_!(msgEnvelope.typedMsg, relationshipId, threadId)
    logger.debug("incoming msg to be sent to protocol: " + tmsg)
    logger.debug("incoming msg processing, msg type: " + tmsg.msgType)
    logger.debug("incoming msg processing, threadId: " + threadId)
    logger.debug("incoming msg processing, relationshipId: " + relationshipId)
    logger.debug("incoming msg processing, senderParticipantId: " + senderParticipantId)
    logger.debug("incoming msg processing, selfParticipantId: " + param.selfParticipantId)
    logger.debug("incoming msg processing, msgRespConfig: " + msgRespConfig)
    logger.debug("incoming msg processing, pair: " + pair)
    logger.debug("incoming msg processing, msgPackVersionOpt: " + msgPackFormat)
    logger.debug(s"incoming msg processing, summary : msg: ${tmsg.msgType}, threadId: $threadId")

    sendGenericRespOrPrepareForAsyncResponse(msgEnvelope.msgId.get, senderParticipantId, msgRespConfig)
    //tracing/tracking metrics related
    msgEnvelope.msgId.foreach { rmId =>
      storeAsyncReqContext(rmId, tmsg.msgType.msgName, rmc.id, rmc.clientIpAddress)
      MsgProgressTracker.recordInMsgProcessingStarted(
        trackingParam = param.trackingParam.copy(threadId = Option(threadId)),
        inMsgParam = TrackMsgParam(msgId = Option(rmId), msgName = Option(tmsg.msgType.msgName)),
        protoParam = ProtoParam(pair.id, pair.protoDef.msgFamily.name, pair.protoDef.msgFamily.version))
    }
    val tc = ThreadContextDetail(threadId,
      msgPackFormat.getOrElse(MPF_INDY_PACK),
      msgTypeDeclarationFormat.getOrElse(STANDARD_TYPE_FORMAT),
      usesLegacyGenMsgWrapper, usesLegacyBundledMsgWrapper)
    tellProtocol(pair, tc, msgEnvelope, self)
  }

  /**
   * if synchronous response is expected or a special ver key needs to be used to pack outgoing/signal message
   * then, it stores msg response context to be used later.
   *
   * if no synchronous response is expected, it sends a 'generic response' Done
   * which is handled at http layer which turns it into a HTTP 200 (ideally it should have been 201)
   * which is handled at http layer which turns it into a HTTP 200 (ideally it should have been 201)
   *
   * @param msgId
   * @param senderPartiId
   * @param msgRespConfigOpt
   */
  def sendGenericRespOrPrepareForAsyncResponse(msgId: MsgId,
                                               senderPartiId: ParticipantId,
                                               msgRespConfigOpt: Option[MsgRespConfig]): Unit = {
    // flow diagram: proto, step 11 -- send 200 OK
    msgRespConfigOpt match {
      case Some(mrc) =>
        val respWaitingActorRef = if (mrc.isSyncReq) Some(sender()) else None
        msgRespContext = msgRespContext + (msgId -> MsgRespContext(senderPartiId, mrc.packForVerKey, respWaitingActorRef))
      case None =>
        sender ! Done
    }
  }

  /**
   *
   * @param reqMsgContext reqest message context
   * @return
   */
  def routingMsgHandler(implicit reqMsgContext: ReqMsgContext): PartialFunction[Any, Unit] = {
    case amw: AgentMsgWrapper
      if amw.isMatched(MSG_FAMILY_ROUTING, MFV_1_0, MSG_TYPE_FORWARD) =>
      val fwdMsg = FwdMsgHelper.buildReqMsg(amw)
      AgentMsgRouter.getDIDForRoute(fwdMsg.`@fwd`) match {
        case Success(fwdToDID) =>
          if (param.relationshipId.contains(fwdToDID) || domainId == fwdToDID) {
            val msgId = MsgUtil.newMsgId
            // flow diagram: fwd.edge, step 9 -- store outgoing msg.
            sendToAgentActor(StoreAndSendMsgToMyDomain(
              OutgoingMsgParam(PackedMsg(fwdMsg.`@msg`), None),
              msgId, MSG_TYPE_UNKNOWN, ParticipantUtil.DID(param.selfParticipantId), None))
            sender ! Done
          }
        case Failure(e) => throw e
      }
  }

  /**
   * extracts internal message payload sent inside the given message
   * @param amw agent message wrapper
   * @return
   */
  private def extractMsgPayload(amw: AgentMsgWrapper): Option[InternalPayload] = {
    if (amw.isMatched(MFV_0_5, CREATE_MSG_TYPE_GENERAL)) {
      val msg = amw.tailAgentMsgs.head
      val createMsgReq = amw.headAgentMsg.convertTo[CreateMsgReqMsg_MFV_0_5]
      val msgDetail = msg.convertTo[GeneralCreateMsgDetail_MFV_0_5]
      Option(InternalPayload(msgDetail.`@msg`, createMsgReq.thread))
    } else if (amw.isMatched(MFV_0_6, MSG_TYPE_SEND_REMOTE_MSG)) {
      val srm = SendRemoteMsgHelper.buildReqMsg(amw)
      Option(InternalPayload(srm.`@msg`, srm.threadOpt))
    } else if (amw.isMatched(MSG_FAMILY_ROUTING, MFV_1_0, MSG_TYPE_FORWARD)) {
      val fwdMsg = FwdMsgHelper.buildReqMsg(amw)
      Option(InternalPayload(fwdMsg.`@msg`, None))
    } else None
  }

  private def internalPayloadWrapper(amw: AgentMsgWrapper): Future[Option[InternalPayloadWrapper]] =
    extractMsgPayload(amw) match {
      case Some(ip) =>
        msgExtractor.unpackAsync(PackedMsg(ip.payload))
          .map { amw =>
            Option(InternalPayloadWrapper(ip, Option(amw)))
          }.recover {
            case _: Exception =>
              Option(InternalPayloadWrapper(ip, None))
          }
      case None => Future.successful(None)
    }

  /**
   * tracks/adds resource usages (before adding resource usage, it also checks if resource usages is blocked etc)
   * and checks if message is sent by an authorized agent/key
   * @param msgType message type
   * @param senderVerKey message sender ver key
   */
  private def preMsgProcessing(msgType: MsgType, senderVerKey: Option[VerKey])(implicit reqMsgContext: ReqMsgContext): Unit = {
    val userId = param.userIdForResourceUsageTracking(senderVerKey)
    reqMsgContext.clientIpAddress.foreach { ipAddress =>
      addUserResourceUsage(ipAddress, RESOURCE_TYPE_MESSAGE,
        getResourceName(msgType.msgName), userId)
    }
    senderVerKey.foreach { svk =>
      if (!param.allowedUnauthedMsgTypes.contains(msgType)) {
        AgentMsgProcessor.checkIfMsgSentByAuthedMsgSenders(param.allAuthedKeys, svk)
      }
    }
  }

  def buildMsgEnvelope(typedMsg: TypedMsgLike, threadId: ThreadId, senderParticipantId: ParticipantId): MsgEnvelope = {
    MsgEnvelope(typedMsg.msg, typedMsg.msgType, param.selfParticipantId,
      senderParticipantId, Option(getNewMsgId), Option(threadId))
  }

  def forwardToAgentActor(msg: Any, sndr: ActorRef = sender()): Unit = {
    param.agentActorRef.tell(msg, sndr)
  }

  def sendToAgentActor(msg: Any): Unit = {
    param.agentActorRef.tell(msg, self)
  }

  def askToAgentActor(msg: Any): Future[Any] = {
    param.agentActorRef.ask(msg)
  }

  private def getResourceName(msgName: String): String = {
    msgName match {
      case CREATE_MSG_TYPE_CONN_REQ | CREATE_MSG_TYPE_CONN_REQ_ANSWER => s"${MSG_TYPE_CREATE_MSG}_$msgName"
      case x => x
    }
  }

  private def buildReqMsgContext(amw: AgentMsgWrapper, rmc: ReqMsgContext): ReqMsgContext = {
    rmc.append(buildReqContextData(amw))
    rmc
  }

  /**
   * builds request message context data for the incoming message
   * @param amw agent message wrapper
   * @return
   */
  private def buildReqContextData(amw: AgentMsgWrapper): Map[String, Any] = {
    import ReqMsgContext._

    val map1 = amw.senderVerKey.map { sk =>
      Map(LATEST_DECRYPTED_MSG_SENDER_VER_KEY -> sk)
    }.getOrElse(Map.empty)

    val map2 = Map(
      MSG_PACK_VERSION -> amw.msgPackFormat,
      MSG_TYPE_DETAIL -> amw.headAgentMsg.msgFamilyDetail)

    map1 ++ map2
  }

  /**
   * in memory state, stores information required to send response
   * to a synchronous requests
   */
  var msgRespContext: Map[MsgId, MsgRespContext] = Map.empty

  override def getPinstId(protoDef: ProtoDef): Option[PinstId] =
    param.protoInstances.flatMap(_.instances.get(protoDef.msgFamily.protoRef.toString))
  override def contextualId: Option[String] = Option(param.thisAgentAuthKey.keyId)
  override def actorSystem: ActorSystem = context.system
  override def domainId: DomainId = param.domainId
  override def agentWalletIdReq: String = param.agentWalletId
  override def stateDetailsFor: Future[PartialFunction[String, Parameter]] = Future(param.protoInitParams)

  lazy val thisAgentKeyParam: KeyParam = KeyParam(Left(param.thisAgentAuthKey.verKey))
  lazy val msgExtractor: MsgExtractor = new MsgExtractor(thisAgentKeyParam, walletAPI)(WalletAPIParam(param.agentWalletId))

  //NOTE: 2 minutes seems to be sufficient (or may be more) for any
  // one message processing (incoming + outgoing) cycle
  context.setReceiveTimeout(120.seconds)
}

/**
 * a parameter whose value depend's on individual agent actor's type/state
 */
case class StateParam(agentActorRef: ActorRef,
                      domainId: DomainId,
                      relationshipId: Option[RelationshipId],
                      thisAgentAuthKey: AuthorizedKeyLike,
                      agentWalletId: String,
                      protoInstances: Option[ProtocolRunningInstances],
                      sponsorRel: Option[SponsorRel],
                      protoInitParams: PartialFunction[String, Parameter],
                      selfParticipantId: ParticipantId,
                      senderParticipantId: Option[VerKey] => ParticipantId,
                      allowedUnauthedMsgTypes: Set[MsgType],
                      allAuthedKeys: Set[VerKey],
                      userIdForResourceUsageTracking: Option[VerKey] => Option[String],
                      trackingParam: TrackingParam)

case class ProcessUnpackedMsgV1(amw: AgentMsgWrapper,
                                rmc: ReqMsgContext = ReqMsgContext(),
                                msgThread: Option[Thread]=None) extends ActorMessage

case class ProcessUnpackedMsgV2(amw: AgentMsgWrapper,
                                internalPayloadWrapper: InternalPayloadWrapper,
                                rmc: ReqMsgContext = ReqMsgContext(),
                                unhandledCause: Throwable) extends ActorMessage


case class ProcessTypedMsg(tmsg: TypedMsgLike,
                           relationshipId: Option[RelationshipId],
                           threadId: ThreadId,
                           senderParticipantId: ParticipantId,
                           msgRespConfig: Option[MsgRespConfig],
                           msgPackFormat: Option[MsgPackFormat],
                           msgTypeDeclarationFormat: Option[TypeFormat],
                           usesLegacyGenMsgWrapper: Boolean=false,
                           usesLegacyBundledMsgWrapper: Boolean=false) extends ActorMessage

case class ProcessUntypedMsgV1(msg: Any,
                               relationshipId: Option[RelationshipId],
                               threadId: ThreadId,
                               senderParticipantId: ParticipantId) extends ActorMessage

case class ProcessUntypedMsgV2(msg: Any,
                               protoDef: ProtoDef,
                               threadId: ThreadId,
                               msgRespConfig: MsgRespConfig = MsgRespConfig(isSyncReq = false),
                               msgPackFormat: Option[MsgPackFormat]=None,
                               msgTypeDeclarationFormat: Option[TypeFormat]=None,
                               usesLegacyGenMsgWrapper: Boolean=false,
                               usesLegacyBundledMsgWrapper: Boolean=false) extends ActorMessage


case class UnhandledMsg(amw: AgentMsgWrapper, rmc: ReqMsgContext, cause: Throwable) extends ActorMessage

case class HandleAuthedRestMsg(prm: ProcessRestMsg) extends ActorMessage

case class SendPushNotif(pcms: Set[ComMethodDetail],
                         pnData: PushNotifData,
                         sponsorId: Option[String]) extends ActorMessage

case class StoreAndSendMsgToMyDomain(om: OutgoingMsgParam,
                                     msgId: MsgId,
                                     msgName: MsgName,
                                     senderDID: DID,
                                     threadOpt: Option[Thread]) extends ActorMessage

case class StoreAndSendMsgToTheirDomain(om: OutgoingMsgParam,
                                        msgId: MsgId,
                                        msgName: MsgName,
                                        senderDID: DID,
                                        threadOpt: Option[Thread]) extends ActorMessage

case class SendUnStoredMsgToMyDomain(omp: OutgoingMsgParam) extends ActorMessage

/**
 * this is used during incoming message processing to specify request/response context information
 *
 * @param isSyncReq determines if the incoming request expects a synchronous response
 * @param packForVerKey determines if the outgoing/signal messages should be packed with this ver key instead
 */
case class MsgRespConfig(isSyncReq:Boolean, packForVerKey: Option[VerKey]=None)

/**
 * used to store information related to incoming msg which will be used during outgoing/signal message processing
 * @param senderPartiId sender participant id
 * @param packForVerKey special ver key to be used to pack outgoing/signal message (so far this is only used for
 *                      'wallet backup restore' message
 * @param senderActorRef actor reference (of waiting http connection) to which the response needs to be sent
 */
case class MsgRespContext(senderPartiId: ParticipantId, packForVerKey: Option[VerKey]=None, senderActorRef:Option[ActorRef]=None)

case class SendToProtocolActor(pinstIdPair: PinstIdPair,
                               msgEnvelope: Any,
                               sndr: ActorRef) extends ActorMessage

case class InternalPayload(payload: Array[Byte], thread: Option[Thread])

/**
 *
 * @param payload internal payload
 * @param decryptedMsg decrypted internal payload (if decrypted by this agent)
 */
case class InternalPayloadWrapper(payload: InternalPayload, decryptedMsg: Option[AgentMsgWrapper])

object AgentMsgProcessor {
  def checkIfMsgSentByAuthedMsgSenders(allAuthKeys:Set[VerKey], msgSenderVerKey: VerKey): Unit = {
    if (allAuthKeys.nonEmpty && ! allAuthKeys.contains(msgSenderVerKey)) {
      throw new UnauthorisedErrorException
    }
  }
}