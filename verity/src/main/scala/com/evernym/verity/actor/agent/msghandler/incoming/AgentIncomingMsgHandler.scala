package com.evernym.verity.actor.agent.msghandler.incoming

import com.evernym.verity.constants.Constants.{MSG_PACK_VERSION, RESOURCE_TYPE_MESSAGE}
import com.evernym.verity.Exceptions.UnauthorisedErrorException
import com.evernym.verity.actor.ProtoMsgReceivedOrderIncremented
import com.evernym.verity.actor.agent.msghandler.outgoing.OutgoingMsgParam
import com.evernym.verity.actor.agent.msghandler.{AgentMsgHandler, MsgRespConfig, MsgRespContext}
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.actor.msg_tracer.progress_tracker.{MsgParam, ProtoParam, TrackingParam}
import com.evernym.verity.actor.persistence.{AgentPersistentActor, Done}
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily.pairwise.{CreateMsgReqMsg_MFV_0_5, GeneralCreateMsgDetail_MFV_0_5, MsgThread, SendRemoteMsgHelper}
import com.evernym.verity.agentmsg.msgfamily.routing.FwdMsgHelper
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgWrapper, PackedMsg, ParseParam, UnpackParam}
import com.evernym.verity.config.AgentAuthKeyUtil
import com.evernym.verity.protocol.actor.MsgEnvelope
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine._
import com.evernym.verity.util.{Base58Util, MsgUtil, ParticipantUtil, ReqMsgContext, RestAuthContext}
import com.evernym.verity.vault.VerifySigByVerKeyParam
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.SpanUtil.runWithInternalSpan
import com.evernym.verity.actor.agent.{MsgPackVersion, TypeFormat}

import scala.concurrent.Future
import scala.util.{Failure, Success}

trait AgentIncomingMsgHandler { this: AgentMsgHandler with AgentPersistentActor =>

  def agentIncomingCommonCmdReceiver[A]: Receive = {

    //edge agent -> agency routing service -> this actor
    case ac: PackedMsgParam if isReadyToHandleIncomingMsg
                                      => handlePackedMsg(ac.packedMsg.msg, ac.reqMsgContext)

    //edge agent -> agency routing service -> this actor
    case rm: RestMsgParam             => handleRestMsg(rm)

    //edge agent -> agency routing service -> self rel actor (user agent actor) -> this actor (pairwise agent actor)
    case mfr: MsgForRelationship[_]   => handleMsgForRel(mfr)

    //pinst -> actor driver -> this actor
    case mfd: SignalMsgFromDriver     => handleSignalMsgFromDriver(mfd)
  }

  /**
   * handles incoming packed (indy or message packed)
   * @param msg packed message
   * @param reqMsgContext request message context
   * @param msgThread this is only needed to handle legacy cred and proof messages (0.6 versioned)
   */
  def handlePackedMsg(msg: Array[Byte],
                      reqMsgContext: ReqMsgContext,
                      msgThread: Option[MsgThread]=None): Unit = {
    // flow diagram: fwd + ctl + proto + legacy, step 7 -- Receive and decrypt.
    try {
      //msg progress tracking related
      MsgProgressTracker.recordMsgReceivedByAgent(getClass.getSimpleName, trackingParam)(reqMsgContext)
      logger.debug(s"[$persistenceId] incoming packed msg: " + msg)
      val amw = msgExtractor.unpack(PackedMsg(msg), unpackParam = UnpackParam(ParseParam(useInsideMsgIfPresent = true)))
      //msg progress tracking related
      MsgProgressTracker.recordMsgUnpackedByAgent(getClass.getSimpleName,
        inMsgParam = MsgParam(msgName = Option(amw.msgType.msgName)))(reqMsgContext)
      logger.debug(s"incoming unpacked (mpv: ${amw.msgPackVersion}) msg: " + amw)
      preMsgProcessing(amw.msgType, amw.senderVerKey)(reqMsgContext)
      handleAgentMsgWrapper(amw, reqMsgContext, msgThread)
    } catch protoExceptionHandler
  }

  /**
   * handles incoming rest messages
   * @param rmp rest message param
   */
  def handleRestMsg(rmp: RestMsgParam): Unit = {
    try {
      //msg progress tracking related
      MsgProgressTracker.recordMsgReceivedByAgent(getClass.getSimpleName,
        trackingParam,
        inMsgParam = MsgParam(msgName = Option(rmp.restMsgContext.msgType.msgName)))(rmp.restMsgContext.reqMsgContext)
      logger.debug(s"[$persistenceId] incoming rest msg: " + rmp.msg)
      veritySignature(rmp.restMsgContext.auth)
      preMsgProcessing(rmp.restMsgContext.msgType, Option(rmp.restMsgContext.auth.verKey))(rmp.restMsgContext.reqMsgContext)
      val imp = IncomingMsgParam(rmp, rmp.restMsgContext.msgType)
      val amw = imp.msgToBeProcessed
      implicit val reqMsgContext: ReqMsgContext = buildReqMsgContext(amw, rmp.restMsgContext.reqMsgContext)
      if (incomingMsgHandler(reqMsgContext).isDefinedAt(amw)) {
        incomingMsgHandler(reqMsgContext)(amw)
      } else {
        extractMsgAndSendToProtocol(imp, rmp.restMsgContext.thread)(rmp.restMsgContext.reqMsgContext)
      }
    } catch protoExceptionHandler
  }

  /**
   * tracks/adds resource usages (before adding resource usage, it also checks if resource usages is blocked etc)
   * and checks if message is sent by an authorized agent/key
   * @param msgType message type
   * @param senderVerKey message sender ver key
   */
  def preMsgProcessing(msgType: MsgType, senderVerKey: Option[VerKey])(implicit rmc: ReqMsgContext): Unit = {
    rmc.clientIpAddress.foreach { ipAddress =>
      addUserResourceUsage(ipAddress, RESOURCE_TYPE_MESSAGE,
        getResourceName(msgType.msgName), userDIDForResourceUsageTracking(senderVerKey))
    }
    senderVerKey.foreach { svk =>
      if (!allowedUnauthedMsgTypes.contains(msgType)) {
        checkIfMsgSentByAuthedMsgSenders(svk)
      }
    }
  }

  def handleSignalMsgFromDriver(mfd: SignalMsgFromDriver): Unit = {
    // flow diagram: SIG, step 5
    if (handleSignalMsgs.isDefinedAt(mfd)) {
      handleSignalMsgs(mfd).foreach { dmOpt =>
        dmOpt.foreach { dm =>
          dm.forRel match {
            case Some(rel) =>
              val tm = typedMsg(dm.msg)
              val tc = getThreadContext(mfd.pinstId)
              val msgForRel = MsgForRelationship(tm, mfd.threadId, selfParticipantId,
                Option(tc.msgPackVersion), Option(tc.msgTypeFormat), None)
              agentActorContext.agentMsgRouter.execute(InternalMsgRouteParam(rel, msgForRel))
            case None =>
              agentActorContext.protocolRegistry.find(mfd.protoRef).foreach { pd =>
                sendUntypedMsgToProtocol(dm.msg, pd.protoDef, mfd.threadId)
              }
          }
        }
      }
    } else {
      throw new RuntimeException(s"[$persistenceId] msg sent by driver not handled by agent: " + mfd.signalMsg)
    }
  }

  def handleAgentMsgWrapper(amw: AgentMsgWrapper,
                            rmc: ReqMsgContext = ReqMsgContext(),
                            msgThread: Option[MsgThread]=None): Unit = {
    //NOTE: this reqMsgContext is to pass some msg context information (like client's ip address, msg sender ver key etc)
    //which was being used by existing agent message
    implicit val reqMsgContext: ReqMsgContext = buildReqMsgContext(amw, rmc)
    if (handleDecryptedMsg(reqMsgContext).isDefinedAt(amw)) {
      // flow diagram: fwd, step 8 -- Re-examine after decrypt.
      handleDecryptedMsg(reqMsgContext)(amw)
    } else if (incomingMsgHandler(reqMsgContext).isDefinedAt(amw)) {
      // flow diagram: legacy, step 8 -- Same as FWD till here. Now, handle MsgPacked format.
      runWithInternalSpan(s"${amw.msgType}", "AgentIncomingMsgHandler") {
        incomingMsgHandler(reqMsgContext)(amw)
      }
    } else {
      // flow diagram: ctl + proto, step 8 -- Same as fwd till here. Now, begin protocol handling.
      extractMsgAndSendToProtocol(IncomingMsgParam(amw, amw.headAgentMsgDetail.msgType), msgThread)
    }
  }

  def handleDecryptedMsg(implicit reqMsgContext: ReqMsgContext): PartialFunction[Any, Unit] = {
    case amw: AgentMsgWrapper
      if amw.isMatched(MFV_0_5, CREATE_MSG_TYPE_GENERAL) &&
        shallBeHandledByThisAgent(amw) =>
      val createMsgReq = amw.headAgentMsg.convertTo[CreateMsgReqMsg_MFV_0_5]
      extractMsgPayload(amw).foreach { payload =>
        handlePackedMsg(payload, reqMsgContext, createMsgReq.thread)
      }

    case amw: AgentMsgWrapper
      if amw.isMatched(MFV_0_6, MSG_TYPE_SEND_REMOTE_MSG) &&
        shallBeHandledByThisAgent(amw) =>
      val srm = SendRemoteMsgHelper.buildReqMsg(amw)
      extractMsgPayload(amw).foreach { payload =>
        handlePackedMsg(payload, reqMsgContext, srm.threadOpt)
      }

    /**
     * this agent received a forward message, it doesn't know the internal payload type
     * so it checks if the forward is targeted for it's edge agent
     * (as of now that is the only use case we want to support, later on it can be expanded)
     * and in that case it stores and optionally sends to the edge agent as well (if com methods are registered)
     */
    case amw: AgentMsgWrapper
      if amw.isMatched(MSG_FAMILY_ROUTING, MFV_1_0, MSG_TYPE_FORWARD) =>
      val fwdMsg = FwdMsgHelper.buildReqMsg(amw)
      if (shallBeHandledByThisAgent(amw)) {
        handlePackedMsg(fwdMsg.`@msg`, reqMsgContext)
      } else {
        AgentMsgRouter.getDIDForRoute(fwdMsg.`@fwd`) match {
          case Success(fwdToDID) =>
            if (relationshipId.contains(fwdToDID) || domainId == fwdToDID) {
              val msgId = MsgUtil.newMsgId
              // flow diagram: fwd.edge, step 9 -- store outgoing msg.
              storeOutgoingMsg(OutgoingMsgParam(PackedMsg(fwdMsg.`@msg`), None),
                msgId, "unknown", ParticipantUtil.DID(selfParticipantId), None)
              sendStoredMsgToEdge(msgId)
              sender ! Done
            }
          case Failure(e) => throw e
        }
      }
  }

  def extractMsgAndSendToProtocol(aimp: IncomingMsgParam, msgThread: Option[MsgThread]=None)
                                 (implicit rmc: ReqMsgContext = ReqMsgContext()): Unit = {
    import scala.language.existentials
    // flow diagram: ctl + proto, step 9 -- add context to actor if sender expects sync response.

    // THIS BELOW LINE IS STOPGAP WORKAROUND to support connect.me using vcx version 0.8.70229609
    // THIS IS A STOPGAP AND SHOULD NOT BE EXPANDED
    val imp = STOP_GAP_MsgTypeMapper.changedMsgParam(aimp)

    val (msgToBeSent: TypedMsg[_], threadId: ThreadId, forRelationship, respDetail: Option[MsgRespConfig]) =
      (imp.msgType.familyName, imp.msgType.familyVersion, imp.msgType.msgName) match {

        //this is special case where connecting protocols (0.5 & 0.6)
        // still uses 'amw' as inputs and expects synchronous response
        case (MSG_FAMILY_CONNECTING, MFV_0_5 | MFV_0_6, msgName)         =>
          val msgRespConf = msgName match {
            case MSG_TYPE_CONNECTING_GET_STATUS => if (imp.isSync(default = false)) Option(MsgRespConfig(isSyncReq = true)) else None
            case _                              => if (imp.isSync(default = true)) Option(MsgRespConfig(isSyncReq = true)) else None
          }
          val (_, _, _, rd) = extract(imp, msgRespConf)
            (TypedMsg(imp.msgToBeProcessed, imp.msgType), DEFAULT_THREAD_ID, None, rd)

        case (MSG_FAMILY_AGENT_PROVISIONING, MFV_0_7, "create-edge-agent") =>
            extract(imp, Option(MsgRespConfig(isSyncReq = true, imp.senderVerKey)))

        //this is special case where agent provisioning protocols (0.5 & 0.6)
        // uses native messages but expects synchronous response
        case (MSG_FAMILY_AGENT_PROVISIONING, _, _) => extract(imp, Option(MsgRespConfig(isSyncReq = true)))

        case (MSG_FAMILY_DEAD_DROP, MFV_0_1_0, MSG_TYPE_DEAD_DROP_RETRIEVE)
                                                   => extract(imp, Option(MsgRespConfig(isSyncReq = true, imp.senderVerKey)))

        case (MSG_FAMILY_WALLET_BACKUP, MFV_0_1_0, MSG_TYPE_WALLET_BACKUP_RESTORE)
                                                   => extract(imp, Option(MsgRespConfig(isSyncReq = true, imp.senderVerKey)))


        case (_, _, _)                             => extract(imp, if (imp.isSync(false)) Option(MsgRespConfig(isSyncReq = true)) else None, msgThread)

        case _                                     => throw new UnsupportedMessageType(imp, List.empty)
      }

    val senderPartiId = senderParticipantId(imp.senderVerKey)

    if (forRelationship.isDefined && forRelationship != relationshipId) {
      forRelationship.foreach { relId =>
        val msgForRel = MsgForRelationship(msgToBeSent, threadId, senderPartiId,
          imp.msgPackVersion, imp.msgFormat, respDetail, Option(rmc))
        logger.debug(s"msg for relationship sent by $persistenceId: " + msgForRel)
        // flow diagram: ctl.pairwise + proto.pairwise, step 10 -- Handle msg for specific connection.
        agentActorContext.agentMsgRouter.forward(InternalMsgRouteParam(relId, msgForRel), sender())
      }
    } else {
      // flow diagram: ctl.self, step 10 -- Handle msg for self relationship.
      sendTypedMsgToProtocol(msgToBeSent, relationshipId, threadId, senderPartiId,
        respDetail, imp.msgPackVersion,
        imp.msgFormat, imp.usesLegacyGenMsgWrapper, imp.usesLegacyBundledMsgWrapper
      )
    }
  }

  protected def sendTypedMsgToProtocol[A](tmsg: TypedMsgLike[A],
                                          relationshipId: Option[RelationshipId],
                                          threadId: ThreadId,
                                          senderParticipantId: ParticipantId,
                                          msgRespConfig: Option[MsgRespConfig],
                                          msgPackVersion: Option[MsgPackVersion],
                                          msgTypeFormat: Option[TypeFormat],
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
    logger.debug("incoming msg processing, selfParticipantId: " + selfParticipantId)
    logger.debug("incoming msg processing, msgRespConfig: " + msgRespConfig)
    logger.debug("incoming msg processing, pair: " + pair)
    logger.debug("incoming msg processing, msgPackVersionOpt: " + msgPackVersion)
    logger.debug(s"[$persistenceId] incoming msg processing, summary : msg: ${tmsg.msgType}, threadId: $threadId")
    msgPackVersion.zip(msgTypeFormat).foreach { case (mpv, mtf) =>
      logger.debug("incoming msg processing, updating thread context")
      updateThreadContext(pair.id, threadId, mpv, mtf, usesLegacyGenMsgWrapper,
        usesLegacyBundledMsgWrapper)
      if (pair.protoDef.msgFamily.isProtocolMsg(tmsg.msg)) {
        writeAndApply(ProtoMsgReceivedOrderIncremented(pair.id, senderParticipantId))
      }
    }
    sendGenericRespOrPrepareForAsyncResponse(msgEnvelope.msgId.get, senderParticipantId, msgRespConfig)

    //tracing/tracking metrics related
    msgEnvelope.msgId.foreach { rmId =>
      storeAsyncReqContext(rmId, tmsg.msgType.msgName, rmc.id, rmc.clientIpAddress)
      MsgProgressTracker.recordInMsgProcessingStarted(
        trackingParam = trackingParam.copy(threadId = Option(threadId)),
        inMsgParam = MsgParam(msgId = Option(rmId), msgName = Option(tmsg.msgType.msgName)),
        protoParam = ProtoParam(pair.id, pair.protoDef.msgFamily.name, pair.protoDef.msgFamily.version))
    }

    tellProtocol(pair.id, pair.protoDef, msgEnvelope, self)
  }

  /**
   * if synchronous response is expected or a special ver key needs to be used to pack outgoing/signal message
   * then, it stores msg response context to be used later.
   *
   * if no synchronous response is expected, it sends a 'generic response' Done
   * which is handled at http layer which turns it into a HTTP 200 (ideally it should have been 201)
   *
   * @param msgId
   * @param senderPartiId
   * @param msgRespConfigOpt
   */
  def sendGenericRespOrPrepareForAsyncResponse(msgId: MsgId, senderPartiId: ParticipantId, msgRespConfigOpt: Option[MsgRespConfig]): Unit = {
    // flow diagram: proto, step 11 -- send 200 OK
    msgRespConfigOpt match {
      case Some(mrc) =>
        val respWaitingActorRef = if (mrc.isSyncReq) Some(sender()) else None
        msgRespContext = msgRespContext + (msgId -> MsgRespContext(senderPartiId, mrc.packForVerKey, respWaitingActorRef))
      case None =>
        sender ! Done
    }
  }

  protected def checkIfMsgSentByAuthedMsgSenders(msgSenderVerKey: VerKey): Unit = {
    val authedVerKeys = allAuthedKeys
    if (authedVerKeys.nonEmpty && ! authedVerKeys.contains(msgSenderVerKey)) {
      throw new UnauthorisedErrorException
    }
  }

  protected def veritySignature(senderAuth: RestAuthContext): Unit = {
    Base58Util.decode(senderAuth.signature) match {
      case Success(signature) =>
        val toVerify = VerifySigByVerKeyParam(senderAuth.verKey, senderAuth.verKey.getBytes, signature)
        if (!walletDetail.walletAPI.verifySigWithVerKey(toVerify).verified)
          throw new UnauthorisedErrorException
      case Failure(_) => throw new UnauthorisedErrorException
    }
  }

  //dhh What does an "untyped message" mean?
  //this overloaded method would be only used to send untyped msgs to protocol
  protected def sendUntypedMsgToProtocol[A <: MsgBase](msg: A,
                                                       protoDef: ProtoDef,
                                                       threadId: ThreadId = DEFAULT_THREAD_ID,
                                                       msgRespConfig: MsgRespConfig = MsgRespConfig(isSyncReq = false),
                                                       msgPackVersion: Option[MsgPackVersion]=None,
                                                       msgTypeFormat: Option[TypeFormat]=None,
                                                       usesLegacyGenMsgWrapper: Boolean=false,
                                                       usesLegacyBundledMsgWrapper: Boolean=false): Unit = {
    val typedMsg = protoDef.msgFamily.typedMsg(msg)
    sendTypedMsgToProtocol(
      typedMsg,
      relationshipId,
      threadId,
      selfParticipantId,
      Option(msgRespConfig),
      msgPackVersion,
      msgTypeFormat,
      usesLegacyGenMsgWrapper,
      usesLegacyBundledMsgWrapper
    )
  }

  def extract(imp: IncomingMsgParam, msgRespDetail: Option[MsgRespConfig], msgThread: Option[MsgThread]=None):
  (TypedMsg[_], ThreadId, Option[DID], Option[MsgRespConfig]) = {
    val m = msgExtractor.extract(imp.msgToBeProcessed, imp.msgPackVersionReq, imp.msgType)
    val tmsg = TypedMsg(m.msg, imp.msgType)
    (tmsg, msgThread.flatMap(_.thid).getOrElse(m.meta.threadId), m.meta.forRelationship, msgRespDetail)
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

  def buildMsgEnvelope[A](typedMsg: TypedMsgLike[A], threadId: ThreadId, senderParticipantId: ParticipantId): MsgEnvelope[A] = {
    MsgEnvelope(typedMsg.msg, typedMsg.msgType, selfParticipantId,
      senderParticipantId, Option(getNewMsgId), Option(threadId))
  }

  /**
   * extracts message payload sent inside the given message
   * @param amw agent message wrapper
   * @return
   */
  def extractMsgPayload(amw: AgentMsgWrapper): Option[Array[Byte]] = {
    if (amw.isMatched(MFV_0_5, CREATE_MSG_TYPE_GENERAL)) {
      amw.tailAgentMsgs.headOption.map { msg =>
        val md = msg.convertTo[GeneralCreateMsgDetail_MFV_0_5]
        md.`@msg`
      }
    } else if (amw.isMatched(MFV_0_6, MSG_TYPE_SEND_REMOTE_MSG)) {
      val srm = SendRemoteMsgHelper.buildReqMsg(amw)
      Option(srm.`@msg`)
    } else if (amw.isMatched(MSG_FAMILY_ROUTING, MFV_1_0, MSG_TYPE_FORWARD)) {
      val fwdMsg = FwdMsgHelper.buildReqMsg(amw)
      Option(fwdMsg.`@msg`)
    } else None
  }

  /**
   * determines if received message (agent message wrapper) is decryptable by this agent or not
   * if this agent can decrypt it, that means, this agent should handle it
   * else, it should try to send/forward it to its edge agent
   * @param amw agent message wrapper
   * @return
   */
  def shallBeHandledByThisAgent(amw: AgentMsgWrapper): Boolean = {
    def isDecryptable(msg: Array[Byte]): Boolean = {
      try {
        msgExtractor.unpack(PackedMsg(msg))
        true
      } catch {
        case _: Exception =>
          false
      }
    }

    extractMsgPayload(amw).exists { msg =>
      isDecryptable(msg)
    }
  }

  /**
   * handles message for relationship handled by this actor (pairwise actor)
   * which is sent from user/agency agent actor.
   * @param mfr message for relationship
   */
  def handleMsgForRel(mfr: MsgForRelationship[_]): Unit = {
    // flow diagram: ctl + proto, step 13
    logger.debug(s"msg for relationship received in $persistenceId: " + mfr)
    mfr.reqMsgContext.foreach { rmc: ReqMsgContext =>
      MsgProgressTracker.recordMsgReceivedByAgent(getClass.getSimpleName,
        trackingParam = trackingParam,
        inMsgParam = MsgParam(msgName = Option(mfr.msgToBeSent.msgType.msgName)))(rmc)
    }
    sendTypedMsgToProtocol(mfr.msgToBeSent, relationshipId, mfr.threadId, mfr.senderParticipantId,
      mfr.msgRespConfig, mfr.msgPackVersion, mfr.msgTypeFormat)(mfr.reqMsgContext.getOrElse(ReqMsgContext()))
  }

  /**
   * builds request message context data for the incoming message
   * @param amw agent message wrapper
   * @return
   */
  def buildReqContextData(amw: AgentMsgWrapper): Map[String, Any] = {
    import ReqMsgContext._

    val map1 = amw.senderVerKey.map { sk =>
      Map(LATEST_DECRYPTED_MSG_SENDER_VER_KEY -> sk)
    }.getOrElse(Map.empty)

    val map2 = Map(
      MSG_PACK_VERSION -> amw.msgPackVersion,
      MSG_TYPE_DETAIL -> amw.headAgentMsg.msgFamilyDetail)

    map1 ++ map2
  }

  lazy val trackingParam = TrackingParam(domainTrackingId, relTrackingId)

  /**
   * all/some agent actors (agency agent, agency agent pairwise, user agent and user agent pairwise)
   * do have legacy message handler logic written in those actors itself (non protocol message handlers).
   * this function will help in deciding if the incoming message is the legacy one which is handled
   * by those actors locally or they should be checked against installed/registered protocols to see if they handle it.
   * @param reqMsgContext request message context
   * @return
   */
  def incomingMsgHandler(implicit reqMsgContext: ReqMsgContext): PartialFunction[Any, Any] = Map.empty

  /**
   * handles signal messages sent from driver
   * and returns optional control message which would be then sent back
   * to the protocol instance which sent the signal
   * @return
   */
  def handleSignalMsgs: PartialFunction[SignalMsgFromDriver, Future[Option[ControlMsg]]] = PartialFunction.empty


  //TODO: there is opportunity to tight below authorization related code
  // (there seems to be more variables than it may needed)

  /**
   * list of authorized msg sender ver keys (need to be implemented by individual agent actors)
   * @return
   */
  def authedMsgSenderVerKeys: Set[VerKey]

  /**
   * list of message types which are allowed to be processed if sent by un authorized sender
   * this is required when inviter's cloud agent (eas) receives
   * invitation answer message (accepted, rejected, redirected etc) from unknown
   * (because till that moment, connection is not yet established) sender (cas cloud agent)
   * @return
   */
  def allowedUnauthedMsgTypes: Set[MsgType] = Set.empty

  /**
   * reads configured authorized key for domainId (self rel id) belonging to this agent
   * @return
   */
  def configuredAuthedKeys: Set[VerKey] = {
    AgentAuthKeyUtil.keysForSelfRelDID(agentActorContext.appConfig, domainId)
  }

  /**
   * combination of configured and other added authed keys
   */
  def allAuthedKeys: Set[VerKey] = configuredAuthedKeys ++ authedMsgSenderVerKeys
}
