package com.evernym.verity.actor.agent.user

import akka.actor.ActorRef
import akka.event.LoggingReceive
import akka.pattern.ask
import com.evernym.verity.Exceptions.{BadRequestErrorException, HandledErrorException, InternalServerErrorException}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status._
import com.evernym.verity.actor
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.{AgentActivityTracker, AgentActorContext, MsgPackVersion}
import com.evernym.verity.actor.agent.relationship._
import com.evernym.verity.actor.agent.agency.{SetupAgentEndpoint, SetupAgentEndpoint_V_0_7, SetupCreateKeyEndpoint, SetupEndpoint}
import com.evernym.verity.actor.agent.msghandler.incoming.{ControlMsg, SignalMsgFromDriver}
import com.evernym.verity.actor.agent.msghandler.outgoing.{MsgNotifierForUserAgent, PayloadMetadata, ProcessSendSignalMsg, SendSignalMsg}
import com.evernym.verity.actor.agent.msgrouter.{InternalMsgRouteParam, PackedMsgRouteParam}
import com.evernym.verity.actor.agent.relationship.{EndpointType, RelationshipUtil, SelfRelationship}
import com.evernym.verity.actor.agent.state._
import com.evernym.verity.actor.persistence.Done
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgfamily.configs._
import com.evernym.verity.agentmsg.msgfamily.pairwise._
import com.evernym.verity.agentmsg.msgfamily.routing.{FwdMsgHelper, FwdReqMsg}
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgPackagingUtil, AgentMsgWrapper, PackedMsg}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.ledger.TransactionAuthorAgreement
import com.evernym.verity.libindy.IndyLedgerPoolConnManager
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.legacy.services.CreateKeyEndpointDetail
import com.evernym.verity.protocol.protocols.connecting.common.{ConnReqReceived, SendMsgToRegisteredEndpoint}
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.PublicIdentifierCreated
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Ctl
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.CreatePairwiseKey
import com.evernym.verity.protocol.protocols.walletBackup.WalletBackupMsgFamily.{ProvideRecoveryDetails, RecoveryKeyRegistered}
import com.evernym.verity.protocol.protocols.{MsgAndDeliveryState, PayloadWrapper}
import com.evernym.verity.push_notification.PusherUtil
import com.evernym.verity.util.Util._
import com.evernym.verity.util._
import com.evernym.verity.vault._
import com.evernym.verity.UrlDetail
import com.evernym.verity.actor.agent.MsgPackVersion.{MPV_INDY_PACK, MPV_MSG_PACK, MPV_PLAIN}
import com.evernym.verity.actor.agent.relationship.Tags.{CLOUD_AGENT_KEY, EDGE_AGENT_KEY, RECIP_KEY, RECOVERY_KEY}

import scala.concurrent.Future
import scala.util.{Failure, Left, Success}

/**
 Represents user's agent
 */
class UserAgent(val agentActorContext: AgentActorContext)
  extends UserAgentCommon
    with HasPublicIdentity
    with AgentActivityTracker
    with MsgNotifierForUserAgent {

  type StateType = State
  val state = new State

  /**
   * actor persistent state object
   */
  class State
    extends AgentStateBase
      with PublicIdentity
      with RelationshipAgents
      with MsgAndDeliveryState
      with OptSponsorId
      with Configs {
    override def initialRel: Relationship = SelfRelationship.empty
  }

  override final def receiveAgentCmd: Receive = commonCmdReceiver orElse cmdReceiver

  override def incomingMsgHandler(implicit reqMsgContext: ReqMsgContext): PartialFunction[Any, Any] =
    agentCommonMsgHandler orElse agentMsgHandler

  /**
   * handles only those messages supported by this actor (user agent actor only)
   * @param reqMsgContext request message context
   * @return
   */
  def agentMsgHandler(implicit reqMsgContext: ReqMsgContext): PartialFunction[Any, Any] = {

    case amw: AgentMsgWrapper
      if amw.isMatched(MFV_0_5, MSG_TYPE_FWD) ||
        amw.isMatched(MFV_1_0, MSG_TYPE_FWD) =>
      // Extract the 'forward' message and sends it along to the UserAgentPairwise.
      handleFwdMsg(FwdMsgHelper.buildReqMsg(amw))

    case amw: AgentMsgWrapper
      if amw.isMatched(MFV_0_5, MSG_TYPE_UPDATE_COM_METHOD) ||
        amw.isMatched(MFV_0_6, MSG_TYPE_UPDATE_COM_METHOD) ||
        amw.isMatched(MFV_1_0, MSG_TYPE_UPDATE_COM_METHOD) =>
      handleUpdateComMethodMsg(UpdateComMethodMsgHelper.buildReqMsg(amw))

    case amw: AgentMsgWrapper
      if amw.isMatched(MFV_0_5, MSG_TYPE_CREATE_KEY) =>
      handleCreateKeyMsg(CreateKeyMsgHelper.buildReqMsg(amw))

    case amw: AgentMsgWrapper
      if amw.isMatched(MFV_0_5, MSG_TYPE_GET_MSGS_BY_CONNS) ||
        amw.isMatched(MFV_0_6, MSG_TYPE_GET_MSGS_BY_CONNS) =>
      handleGetMsgsByConns(GetMsgsByConnsMsgHelper.buildReqMsg(amw))

    case amw: AgentMsgWrapper
      if amw.isMatched(MFV_0_5, MSG_TYPE_UPDATE_MSG_STATUS_BY_CONNS) ||
        amw.isMatched(MFV_0_6, MSG_TYPE_UPDATE_MSG_STATUS_BY_CONNS) =>
      handleUpdateMsgStatusByConns(UpdateMsgStatusByConnsMsgHelper.buildReqMsg(amw))
  }

  /**
   * internal command handlers
   */
  val cmdReceiver: Receive = LoggingReceive.withLabel("cmdReceiver") {
    case _: SetupEndpoint if state.myDid.isDefined =>
      throw new BadRequestErrorException(AGENT_ALREADY_CREATED.statusCode)
    case sae: SetupEndpoint                      => handleInit(sae)
    case GetAllComMethods                        => sendAllComMethods()
    case GetPushComMethods                       => sendPushComMethods()
    case GetHttpComMethods                       => sendHttpComMethods()
    case GetFwdComMethods                        => sendFwdComMethods()
    case dcm: DeleteComMethod                    => handleDeleteComMethod(dcm)
    case ads: AgentDetailSet                     => handleAgentDetailSet(ads)
  }

  override def handleSpecificSignalMsgs: PartialFunction[SignalMsgFromDriver, Future[Option[ControlMsg]]] = {
    // Here, "Driver" means the same thing that the community calls a "Controller".
    // TODO: align with community terminology.
    case SignalMsgFromDriver(_: ConnReqReceived, _, _, _)                      => Future.successful(None)
    case SignalMsgFromDriver(sm: SendMsgToRegisteredEndpoint, _, _, _)         => sendAgentMsgToRegisteredEndpoint(sm)
    case SignalMsgFromDriver(prd: ProvideRecoveryDetails, _, _, _)             => registerRecoveryKey(prd.params.recoveryVk)
    case SignalMsgFromDriver(_: CreatePairwiseKey, _, _, _)                    => createNewPairwiseEndpoint()
    case SignalMsgFromDriver(pic: PublicIdentifierCreated, _, _, _)            => storePublicIdentity(pic.identifier.did, pic.identifier.verKey)
  }

  override final def receiveAgentEvent: Receive = commonEventReceiver orElse eventReceiver orElse msgState.msgEventReceiver

  val eventReceiver: Receive = {
    case ods: OwnerDIDSet                  => handleOwnerDIDSet(ods.ownerDID)
    case akc: AgentKeyCreated              => handleAgentKeyCreated(akc.forDID)
    case rka: RequesterKeyAdded            => handleAuthKeyAdded(rka)
    case rka: RecoveryKeyAdded             => handleRecoveryKeyAdded(rka.verKey)
    case cmu: ComMethodUpdated             => handleUpdateAuthKeyAndEndpoint(cmu)
    case cmd: ComMethodDeleted             => handleRemoveComMethod(cmd)
    case sa: SponsorAssigned               => state.setSponsorId(sa.id); state.setSponseeId(sa.sponsee)
    case pis: PublicIdentityStored         => state.setPublicIdentity(DidPair(pis.DID, pis.verKey))

      //this is received for each new pairwise connection/actor that gets created
    case ads: AgentDetailSet               => state.addRelationshipAgent(AgentDetail(ads.forDID, ads.agentKeyDID))
  }

  def handleRemoveComMethod(cmd: ComMethodDeleted): Unit = {
    state.removeEndpointById(cmd.id)
  }

  def handleUpdateAuthKeyAndEndpoint(cmu: ComMethodUpdated): Unit = {
    val existingEdgeAuthKeys = state.myDidDoc_!.authorizedKeys_!.filterByTags(EDGE_AGENT_KEY)
    val newAuthKeys = cmu.packaging.map(_.recipientKeys).getOrElse(Seq.empty).toSet
    val authKeyIds = newAuthKeys.map { verKey =>

      //for now, using 'verKey' as the keyId, if required, it can be changed
      state.mergeAuthKeyToMyDidDoc(verKey, verKey, Set(RECIP_KEY))
      state.myDidDoc_!.authorizedKeys_!.findByVerKey(verKey).get.keyId    //TODO: fix .get
    }
    val packagingContext = cmu.packaging.map(p => PackagingContext(p.pkgType))
    val endpoint: EndpointADTUntyped = cmu.typ match {
      case EndpointType.PUSH        => PushEndpoint(cmu.id, cmu.value)
      case EndpointType.HTTP        => HttpEndpoint(cmu.id, cmu.value, packagingContext)
      case EndpointType.FWD_PUSH    => ForwardPushEndpoint(cmu.id, cmu.value, packagingContext)
      case EndpointType.SPR_PUSH    => SponsorPushEndpoint(cmu.id, cmu.value, packagingContext)
    }
    state.addOrUpdateEndpointToMyDidDoc(endpoint, authKeyIds ++ existingEdgeAuthKeys.map(_.keyId))
  }

  def handleOwnerDIDSet(did: DID): Unit = {
    val myDidDoc = state.prepareMyDidDoc(did, did, Set(EDGE_AGENT_KEY), checkThisAgentKeyId = false)
    state.setRelationship(SelfRelationship(myDidDoc))
  }

  def handleAgentKeyCreated(forDID: DID): Unit = {
    state.setThisAgentKeyId(forDID)
    if (forDID != state.myDid_!) {
      state.addNewAuthKeyToMyDidDoc(forDID, getVerKeyReqViaCache(forDID), Set(CLOUD_AGENT_KEY))
    }
    //this is only to handle a legacy code issue
    pendingEdgeAuthKeyToBeAdded.foreach(pak => handleAuthKeyAdded(pak.rka))
  }

  def handleAuthKeyAdded(rka: RequesterKeyAdded): Unit = {
    //here 'requester key' means edge agent key

    //in legacy code, the events (from 'handleInit' function)
    // were persisted in different order and hence it was creating issue
    // in initializing/populating the relationship object out of it.
    // there is a chance that this event ('RequesterKeyAdded') may come before the DID doc is setup
    // and in that case we just need to apply it after the DID doc is setup
    if (state.relationship.isEmpty) {
      pendingEdgeAuthKeyToBeAdded = Option(PendingAuthKey(rka))
    } else if (! pendingEdgeAuthKeyToBeAdded.exists(_.applied)) {
      state.addNewAuthKeyToMyDidDoc(state.myDid_!, rka.verKey, Set(EDGE_AGENT_KEY))
      pendingEdgeAuthKeyToBeAdded = pendingEdgeAuthKeyToBeAdded.map(_.copy(applied = true))
    } else {
      //if flow comes to this block, it means, this is a 'recovery key'
      // (which belonged to wallet recovery feature) being added
      // this is due to the fact that the same "event" type/class were used for both
      // purposes (recovery key and edge key)
      handleRecoveryKeyAdded(rka.verKey)
    }
  }

  def handleRecoveryKeyAdded(verKey: VerKey): Unit = {
    state.addNewAuthKeyToMyDidDoc("recovery-key", verKey, Set(RECOVERY_KEY))
  }

  def taa: Option[TransactionAuthorAgreement] = {
    agentActorContext.poolConnManager match {
      case m: IndyLedgerPoolConnManager => m.currentTAA
      case _ => None
    }
  }

  def storePublicIdentity(DID: DID, verKey: VerKey): Future[Option[ControlMsg]] = {
    if (state.publicIdentity.isEmpty) {
      writeAndApply(PublicIdentityStored(DID, verKey))
    }
    Future.successful(None)
  }

  // The recovery key mentioned here is the one used during wallet backup.
  def registerRecoveryKey(recoveryKey: VerKey): Future[Option[ControlMsg]] = {
    writeAndApply(RecoveryKeyAdded(recoveryKey))
    Future.successful(Option(ControlMsg(RecoveryKeyRegistered())))
  }

  def handleAgentDetailSet(ads: AgentDetailSet): Unit = {
    if (state.relationshipAgentsContains(AgentDetail(ads.forDID, ads.agentKeyDID))) {
      sender ! Done
    } else {
      writeApplyAndSendItBack(ads)
    }
  }

  def handleDeleteComMethod(dcm: DeleteComMethod): Unit = {
    state.myDidDoc_!.endpoints_!.filterByValues(dcm.value).foreach { ep =>
      state.removeEndpointById(ep.id)
      writeAndApply(ComMethodDeleted(ep.id, ep.value, dcm.reason))
      logger.debug(s"com method deleted (userDID=<${state.myDid}>, id=${ep.id}, " +
        s"value=${ep.value}, reason=${dcm.reason})", (LOG_KEY_SRC_DID, state.myDid))
    }
  }

  def getComMethods(types: Seq[Int] = Seq.empty): CommunicationMethods =
    state.comMethodsByTypes(types, state.sponsorId)

  def sendComMethodsByType(filterComMethodTypes: Seq[Int]): Unit = {
    logger.debug("about to send com methods...")
    val filteredComMethods = getComMethods(filterComMethodTypes)
    sender ! filteredComMethods
    logger.debug("com methods sent: " + filteredComMethods)
  }

  def sendAllComMethods(): Unit = sendComMethodsByType(Seq.empty)

  def sendPushComMethods(): Unit = sendComMethodsByType(Seq(COM_METHOD_TYPE_PUSH))

  def sendHttpComMethods(): Unit = sendComMethodsByType(Seq(COM_METHOD_TYPE_HTTP_ENDPOINT))

  def sendFwdComMethods(): Unit = sendComMethodsByType(Seq(COM_METHOD_TYPE_FWD_PUSH))

  def handleInitPairwiseConnResp(agentDID: DID, futResp: Future[Any], sndr: ActorRef)
                                (implicit reqMsgContext: ReqMsgContext): Unit = {
    futResp map {
      case PairwiseConnSet => handlePairwiseConnSet(PairwiseConnSetExt(agentDID, reqMsgContext), sndr)
      case x => sndr ! x
    }
  }

  def handlePairwiseConnSet(pd: PairwiseConnSetExt, sndr: ActorRef): Unit = {
    implicit val reqMsgContext: ReqMsgContext = pd.reqMsgContext
    val pairwiseDIDVerKey = getVerKeyReqViaCache(pd.agentDID)
    val keyCreatedRespMsg = CreateKeyMsgHelper.buildRespMsg(pd.agentDID, pairwiseDIDVerKey)(reqMsgContext.agentMsgContext)
    val param = AgentMsgPackagingUtil.buildPackMsgParam(encParamFromThisAgentToOwner, keyCreatedRespMsg)
    val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackVersion, param)(agentMsgTransformer, wap)
    sendRespMsg(rp, sndr)
  }

  def handleCreateKeyMsg(createKeyReqMsg: CreateKeyReqMsg)(implicit reqMsgContext: ReqMsgContext): Unit = {
    addUserResourceUsage(reqMsgContext.clientIpAddressReq, RESOURCE_TYPE_MESSAGE, MSG_TYPE_CREATE_KEY, state.myDid)
    checkIfKeyNotCreated(createKeyReqMsg.forDID)
    val (futResp, agentDID) = createNewPairwiseEndpointBase(createKeyReqMsg.forDID, Option(createKeyReqMsg.forDIDVerKey), isEdgeAgent = false)
    val sndr = sender()
    handleInitPairwiseConnResp(agentDID, futResp, sndr)
  }

  def createNewPairwiseEndpointBase(forDID: DID, verKeyOpt: Option[VerKey]=None, isEdgeAgent: Boolean): (Future[Any], DID) = {
    val forVerKey = verKeyOpt.getOrElse(getVerKeyReqViaCache(forDID))

    val endpointDID = if (! isEdgeAgent) {
      val pairwiseKeyResult = agentActorContext.walletAPI.createNewKey(CreateNewKeyParam())
      agentActorContext.walletAPI.storeTheirKey(StoreTheirKeyParam(forDID, forVerKey), ignoreIfAlreadyExists = true)
      writeAndApply(AgentDetailSet(forDID, pairwiseKeyResult.did))
      pairwiseKeyResult.did
    } else forDID
    val ipc = buildSetupCreateKeyEndpoint(forDID, endpointDID)
    val resp = userAgentPairwiseRegion ? ForIdentifier(getNewActorId, ipc)
    (resp, endpointDID)
  }

  def createNewPairwiseEndpoint(): Future[Option[ControlMsg]] = {
    val nkc = walletDetail.walletAPI.createNewKey()
    val (respFut, _) = createNewPairwiseEndpointBase(nkc.did, Option(nkc.verKey), isEdgeAgent = true)
    respFut.map { _ =>
      Option(ControlMsg(Ctl.KeyCreated(nkc.did, nkc.verKey)))
    }
  }

  def buildSetupCreateKeyEndpoint(forDID: DID, newAgentPairwiseVerKeyDID: DID): SetupCreateKeyEndpoint = {
    SetupCreateKeyEndpoint(newAgentPairwiseVerKeyDID, forDID,
      state.myDid_!, state.thisAgentKeyDID, agentWalletSeed)
  }

  def handleFwdMsg(fwdMsg: FwdReqMsg)(implicit reqMsgContext: ReqMsgContext): Unit = {
    val efm = PackedMsgRouteParam(fwdMsg.`@fwd`, PackedMsg(fwdMsg.`@msg`), reqMsgContext)
    agentActorContext.agentMsgRouter.forward(efm, sender)
  }

  def buildAndSendComMethodUpdatedRespMsg(comMethod: ComMethod)(implicit reqMsgContext: ReqMsgContext): Unit = {
    val comMethodUpdatedRespMsg = UpdateComMethodMsgHelper.buildRespMsg(comMethod.id)(reqMsgContext.agentMsgContext)
    reqMsgContext.msgPackVersion match {
      case MPV_PLAIN =>
        sender ! comMethodUpdatedRespMsg.head

        // to test if http endpoint is working send response also on it.
        if (comMethod.`type` == COM_METHOD_TYPE_HTTP_ENDPOINT) {
          comMethodUpdatedRespMsg.head match {
            case resp: ComMethodUpdatedRespMsg_MFV_0_6 =>
              val jsonMsg = AgentMsgPackagingUtil.buildAgentMsgJson(comMethodUpdatedRespMsg, MPV_PLAIN, wrapInBundledMsgs = false)
              sendMsgToRegisteredEndpoint(
                PayloadWrapper(
                  jsonMsg.getBytes,
                  Option(PayloadMetadata(resp.`@type`, MPV_PLAIN))),
                None
              )
            case _ =>
          }
        }
      case MPV_INDY_PACK | MPV_MSG_PACK =>
        val param = AgentMsgPackagingUtil.buildPackMsgParam (encParamFromThisAgentToOwner, comMethodUpdatedRespMsg)
        val rp = AgentMsgPackagingUtil.buildAgentMsg (reqMsgContext.msgPackVersion, param) (agentMsgTransformer, wap)
        sendRespMsg(rp)
    }
  }

  def isOnlyOneComMethodAllowed(comType: Int): Boolean =
    Set(COM_METHOD_TYPE_PUSH, COM_METHOD_TYPE_FWD_PUSH).contains(comType)

  def processValidatedUpdateComMethodMsg(comMethod: ComMethod)(implicit reqMsgContext: ReqMsgContext): Unit = {
    val authKeyIds = state.myDidDoc_!.endpoints_!.endpointsToAuthKeys.getOrElse(comMethod.id, KeyIds())
    val verKeys = state.myDidDoc_!.authorizedKeys_!.safeAuthorizedKeys
      .filterByKeyIds(authKeyIds)
      .map(_.verKey).toSet
    val existingEndpointOpt = state.myDidDoc_!.endpoints_!.findById(comMethod.id)
    val isComMethodExistsWithSameValue = existingEndpointOpt.exists{ eep =>
      eep.`type` == comMethod.`type` && eep.value == comMethod.value && {
        (eep.packagingContext, comMethod.packaging) match {
          case (Some(ecmp), Some(newp)) =>
            ecmp.packVersion.isEqual(newp.pkgType) && newp.recipientKeys.exists(_.exists(verKeys.contains))
          case (None, None) => true
          case _ => false
        }
      }
    }
    if (! isComMethodExistsWithSameValue) {
      logger.debug(s"comMethods: ${state.myDidDoc_!.endpoints}")
      state.myDidDoc_!.endpoints_!.filterByTypes(comMethod.`type`)
        .filter (_ => isOnlyOneComMethodAllowed(comMethod.`type`)).foreach { ep =>
	      writeAndApply(ComMethodDeleted(ep.id, ep.value, "new com method will be updated (as of now only one device supported at a time)"))
      }
      writeAndApply(ComMethodUpdated(
        comMethod.id,
        comMethod.`type`,
        comMethod.value,
        comMethod.packaging.map{ pkg =>
          actor.ComMethodPackaging(
            pkg.pkgType,
            pkg.recipientKeys.getOrElse(Set.empty).toSeq
          )
        }))
      logger.debug(
        s"update com method => updated (userDID=<${state.myDid}>, id=${comMethod.id}, " +
          s"old=$existingEndpointOpt): new: $comMethod", (LOG_KEY_SRC_DID, state.myDid))
    } else {
      logger.debug(
        s"update com method => update not needed (userDID=<${state.myDid}>, id=${comMethod.id}, " +
          s"old=$existingEndpointOpt): new: $comMethod",  (LOG_KEY_SRC_DID, state.myDid))
    }
  }

  def handleUpdateComMethodMsg(ucm: UpdateComMethodReqMsg)(implicit reqMsgContext: ReqMsgContext): Unit = {
    addUserResourceUsage(reqMsgContext.clientIpAddressReq, RESOURCE_TYPE_MESSAGE, MSG_TYPE_UPDATE_COM_METHOD, state.myDid)
    val comMethod = ucm.comMethod.`type` match {
      case COM_METHOD_TYPE_PUSH           =>
        PusherUtil.checkIfValidPushComMethod(
          ComMethodDetail(
            COM_METHOD_TYPE_PUSH,
            ucm.comMethod.value),
          appConfig)
        ucm.comMethod
      case COM_METHOD_TYPE_HTTP_ENDPOINT  => UrlDetail(ucm.comMethod.value); ucm.comMethod
      case COM_METHOD_TYPE_FWD_PUSH       =>
        if (state.sponsorId.isEmpty){
          throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option("no sponsor registered - cannot register fwd method"))
        }
        else {
          ComMethod(ucm.comMethod.id, ucm.comMethod.`type`, ucm.comMethod.value, None)
        }
      case COM_METHOD_TYPE_SPR_PUSH       =>
        if (state.sponsorId.isEmpty){
          throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option("no sponsor registered - cannot register sponsor push method"))
        }
        else {
          ComMethod(ucm.comMethod.id, ucm.comMethod.`type`, ucm.comMethod.value, None)
        }
    }
    processValidatedUpdateComMethodMsg(comMethod)
    buildAndSendComMethodUpdatedRespMsg(comMethod)
  }

  def validatePairwiseFromDIDs(givenPairwiseFromDIDs: List[DID]): Unit = {
    if (givenPairwiseFromDIDs.nonEmpty) {
      val unmatched = state.relationshipAgentsForDidsSubtractedFrom(givenPairwiseFromDIDs)
      if (unmatched.nonEmpty) {
        throw new BadRequestErrorException(INVALID_VALUE.statusCode,
          Option(s"no pairwise connection found with these DIDs: ${unmatched.mkString(", ")}"))
      }
    }
  }

  def buildUpdateMsgStatusReq(updateMsgStatusByConnsReq: UpdateMsgStatusByConnsReqMsg, agentVerKey: VerKey)
                             (implicit reqMsgContext: ReqMsgContext): Future[List[(String, Any)]] = {
    Future.traverse(updateMsgStatusByConnsReq.uidsByConns) { uc =>
      val pc = state.relationshipAgentsFindByForDid(uc.pairwiseDID)
      val updateMsgStatusReq =
        reqMsgContext.msgPackVersion match {
          case MPV_MSG_PACK =>
            DefaultMsgCodec.toJson(
              UpdateMsgStatusReqMsg_MFV_0_5(
                TypeDetail(MSG_TYPE_UPDATE_MSG_STATUS, MTV_1_0, None),
                updateMsgStatusByConnsReq.statusCode, uc.uids
              )
            )
          case MPV_INDY_PACK =>
            DefaultMsgCodec.toJson(
              UpdateMsgStatusReqMsg_MFV_0_6(
                MSG_TYPE_DETAIL_UPDATE_MSG_STATUS,
                updateMsgStatusByConnsReq.statusCode,
                uc.uids
              )
            )
          case x => throw new RuntimeException("unsupported msg pack version: " + x)
        }
      val authEncParam = EncryptParam(
        Set(KeyInfo(Left(getVerKeyReqViaCache(pc.agentKeyDID)))),
        Option(KeyInfo(Left(agentVerKey)))
      )
      val packedMsg = agentActorContext.agentMsgTransformer.pack(reqMsgContext.msgPackVersion, updateMsgStatusReq, authEncParam)
      val rmi = reqMsgContext.copy()
      rmi.data = reqMsgContext.data.filter(kv => Set(CLIENT_IP_ADDRESS).contains(kv._1))
      val fut = agentActorContext.agentMsgRouter.execute(
        PackedMsgRouteParam(pc.agentKeyDID, packedMsg, rmi))
      fut.map(f => (pc.forDID, f))
    }
  }

  def buildSuccessfullyUpdatedMsgStatusResp(success: List[(String, Any)], agentVerKey: VerKey):  Map[String, List[String]] = {
    success.map { case (fromDID, respMsg) =>
      val unpackedAgentMsg = agentActorContext.agentMsgTransformer.unpack(respMsg.asInstanceOf[PackedMsg].msg, KeyInfo(Left(agentVerKey)))
      val msgIds = unpackedAgentMsg.msgPackVersion match {
        case MPV_MSG_PACK   => unpackedAgentMsg.headAgentMsg.convertTo[MsgStatusUpdatedRespMsg_MFV_0_5].uids
        case MPV_INDY_PACK  => unpackedAgentMsg.headAgentMsg.convertTo[MsgStatusUpdatedRespMsg_MFV_0_6].uids
        case x              => throw new RuntimeException("unsupported msg pack version: " + x)
      }
      fromDID -> msgIds
    }.filter(_._2.nonEmpty).toMap
  }

  def buildFailedUpdateMsgStatusResp(failed: List[(String, Any)]): Map[String, HandledErrorException] = {
    failed.map { case (fromDID, respMsg) =>
      respMsg match {
        case br: BadRequestErrorException => fromDID -> br
        case he: HandledErrorException => fromDID -> he
        case _ => fromDID -> new InternalServerErrorException(UNHANDLED.statusCode, Option("unhandled error"))
      }
    }.toMap
  }

  def parseBuildAndSendResp(respMsgs: List[(String, Any)], agentVerKey: VerKey, sndr: ActorRef)
                           (implicit reqMsgContext: ReqMsgContext): Unit = {
    val (success, others) = respMsgs.partition { case (_, r) =>
      r.isInstanceOf[PackedMsg]
    }
    val successResult = buildSuccessfullyUpdatedMsgStatusResp(success, agentVerKey)
    val errorResult = buildFailedUpdateMsgStatusResp(others)

    val msgStatusUpdatedByConnsRespMsg =
      UpdateMsgStatusByConnsMsgHelper.buildRespMsg(successResult, errorResult)(reqMsgContext.agentMsgContext)
    val wrapInBundledMsg = reqMsgContext.msgPackVersion match {
      case MPV_MSG_PACK => true
      case _ => false
    }
    val param = AgentMsgPackagingUtil.buildPackMsgParam(encParamFromThisAgentToOwner, msgStatusUpdatedByConnsRespMsg, wrapInBundledMsg)
    val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackVersion, param)(agentMsgTransformer, wap)
    sendRespMsg(rp, sndr)
  }

  def handleUpdateMsgStatusFutResp(futResp: Future[List[(String, Any)]], agentVerKey: VerKey, sndr: ActorRef)
                                  (implicit reqMsgContext: ReqMsgContext): Unit = {
    futResp.onComplete {
      case Success(respMsgs) =>
        try {
          parseBuildAndSendResp(respMsgs, agentVerKey, sndr)
        } catch {
          case e: Exception =>
            handleException(e, sndr)
        }
      case Failure(e) =>
        handleException(e, sndr)
    }
  }

  def handleUpdateMsgStatusByConns(updateMsgStatusByConnsReq: UpdateMsgStatusByConnsReqMsg)
                                  (implicit reqMsgContext: ReqMsgContext): Unit = {
    validatePairwiseFromDIDs(updateMsgStatusByConnsReq.uidsByConns.map(_.pairwiseDID))
    val sndr = sender()
    val agentVerKey = state.thisAgentVerKeyReq
    val reqFut = buildUpdateMsgStatusReq(updateMsgStatusByConnsReq, agentVerKey)
    handleUpdateMsgStatusFutResp(reqFut, agentVerKey, sndr)
  }


  def prepareAndSendGetMsgsReqMsgToPairwiseActor(getMsgsByConnsReq: GetMsgsByConnsReqMsg,
                                                 filteredPairwiseConns: List[AgentDetail])
                                                (implicit reqMsgContext: ReqMsgContext): Future[List[(String, PackedMsg)]] = {

    //TODO: decide if sending GET_MSGS of both version is OK or not?
    val getMsg = DefaultMsgCodec.toJson(
      GetMsgsReqMsg_MFV_0_5(TypeDetail(MSG_TYPE_GET_MSGS, MTV_1_0, None),
        getMsgsByConnsReq.excludePayload, getMsgsByConnsReq.uids, getMsgsByConnsReq.statusCodes)
    )

    val agentVerKey = state.thisAgentVerKeyReq
    Future.traverse(filteredPairwiseConns) { pc =>
      val encParam = EncryptParam(
        Set(KeyInfo(Left(getVerKeyReqViaCache(pc.agentKeyDID)))),
        Option(KeyInfo(Left(agentVerKey)))
      )
      val packedMsg = agentActorContext.agentMsgTransformer.pack(MPV_MSG_PACK, getMsg, encParam)
      val rmi = reqMsgContext.copy()
      rmi.data = reqMsgContext.data.filter(kv => Set(CLIENT_IP_ADDRESS, MSG_PACK_VERSION).contains(kv._1))
      agentActorContext.agentMsgRouter.execute(
        PackedMsgRouteParam(pc.agentKeyDID, packedMsg, rmi)).mapTo[PackedMsg].map (r => (pc.forDID, r))
    }
  }

  def handleGetMsgsRespMsgFromPairwiseActor(respFut: Future[List[(String, PackedMsg)]], sndr: ActorRef)
                                           (implicit reqMsgContext: ReqMsgContext): Unit = {
    respFut.onComplete {
      case Success(respMsgs) =>
        try {
          val agentVerKey = state.thisAgentVerKeyReq
          val result = respMsgs.map { case (fromDID, respMsg) =>
            val amw = agentActorContext.agentMsgTransformer.unpack(respMsg.msg, KeyInfo(Left(agentVerKey)))
            val msgs = reqMsgContext.msgPackVersion match {
              case MPV_MSG_PACK | MPV_INDY_PACK => amw.headAgentMsg.convertTo[GetMsgsRespMsg_MFV_0_5].msgs
              case x => throw new BadRequestErrorException(BAD_REQUEST.statusCode, Option("msg pack version not supported: " + x))
            }
            fromDID -> msgs
          }.toMap
          val getMsgsByConnsRespMsg = GetMsgsByConnsMsgHelper.buildRespMsg(result)(reqMsgContext.agentMsgContext)
          val param = AgentMsgPackagingUtil.buildPackMsgParam(encParamFromThisAgentToOwner,
            getMsgsByConnsRespMsg, reqMsgContext.agentMsgContext.msgPackVersion == MPV_MSG_PACK)
          val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackVersion, param)(agentMsgTransformer, wap)
          sendRespMsg(rp, sndr)
        } catch {
          case e: Exception =>
            handleException(e, sndr)
        }
      case Failure(e) =>
        handleException(e, sndr)
    }
  }

  def handleGetMsgsByConns(getMsgsByConnsReq: GetMsgsByConnsReqMsg)(implicit reqMsgContext: ReqMsgContext): Unit = {
    val sndr = sender()
    val givenPairwiseDIDs = getMsgsByConnsReq.pairwiseDIDs.getOrElse(List.empty)
    validatePairwiseFromDIDs(givenPairwiseDIDs)
    val filteredPairwiseConns = if (givenPairwiseDIDs.nonEmpty) {
      state.relationshipAgents.filter(pc => givenPairwiseDIDs.contains(pc.forDID))
    } else state.relationshipAgents
    val reqFut = prepareAndSendGetMsgsReqMsgToPairwiseActor(getMsgsByConnsReq, filteredPairwiseConns)
    handleGetMsgsRespMsgFromPairwiseActor(reqFut, sndr)
  }

  def handleInit(se: SetupEndpoint): Unit = {
    val evt = OwnerDIDSet(se.ownerDID)
    writeAndApply(evt)
    writeAndApply(AgentKeyCreated(se.agentKeyDID))
    val setRouteFut = setRoute(se.ownerDID, Option(se.agentKeyDID))
    val sndr = sender()
    val resp = se match {
      case s: SetupAgentEndpoint_V_0_7  =>
        s.sponsorRel.foreach(sr => writeAndApply(SponsorAssigned(sr.sponsorId, sr.sponseeId)))
        logger.debug(s"User Agent initialized with V0.7")
        writeAndApply(RequesterKeyAdded(s.requesterVerKey))
        AgentProvisioningDone(s.ownerDID, getVerKeyReqViaCache(s.agentKeyDID), s.threadId)
      case _: SetupAgentEndpoint        =>
        logger.debug(s"User Agent initialized (old protocol)")
        Done
    }

    logger.info(s"new user agent created - domainId: ${se.ownerDID}, sponsorId: ${state.sponsorId}, sponseeId: ${state.sponseeId}")
    trackNewAgent(state.sponsorId)

    setRouteFut map {
      case _: RouteSet  => sndr ! resp
      case _            => throw new RuntimeException("route not set for user agent")
    }
  }

  def authedMsgSenderVerKeys: Set[VerKey] = {
    (state.relationship.myDidDocAuthKeysByTag(EDGE_AGENT_KEY).flatMap(_.verKeyOpt)  ++
      state.relationship.myDidDocAuthKeysByTag(RECOVERY_KEY).flatMap(_.verKeyOpt)).toSet
  }

  def checkIfKeyNotCreated(forDID: DID): Unit = {
    if (state.relationshipAgents.exists(_.forDID == forDID)) {
      throw new BadRequestErrorException(KEY_ALREADY_CREATED.statusCode)
    }
  }

  def stateDetailsFor: Future[String ?=> Parameter]  = {
    val agentActorEntityId = getNewActorId
    val createKeyEndpointSetupDetailJson = DefaultMsgCodec.toJson(
      CreateKeyEndpointDetail(
        userAgentPairwiseRegionName,
        state.myDid_!,
        state.thisAgentKeyDID,
        agentWalletSeed)
    )
    val filteredConfs = getFilteredConfigs(Set(NAME_KEY, LOGO_URL_KEY))

    def paramMap(agencyVerKey: VerKey): String ?=> Parameter = {
      case SELF_ID                                  => Parameter(SELF_ID, ParticipantUtil.participantId(state.thisAgentKeyDIDReq, state.thisAgentKeyDID))
      case OTHER_ID                                 => Parameter(OTHER_ID, ParticipantUtil.participantId(state.thisAgentKeyDIDReq, state.myDid))
      case NAME                                     => Parameter(NAME, agentName(filteredConfs))
      case LOGO_URL                                 => Parameter(LOGO_URL, agentLogoUrl(filteredConfs))
      case AGENCY_DID                               => Parameter(AGENCY_DID, agencyDIDReq)
      case AGENCY_DID_VER_KEY                       => Parameter(AGENCY_DID_VER_KEY, agencyVerKey)
      case THIS_AGENT_WALLET_SEED                   => Parameter(THIS_AGENT_WALLET_SEED, agentWalletSeedReq)
      case NEW_AGENT_WALLET_SEED                    => Parameter(NEW_AGENT_WALLET_SEED, agentActorEntityId)
      case CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON    => Parameter(CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON, createKeyEndpointSetupDetailJson)
      case MY_SELF_REL_DID                          => Parameter(MY_SELF_REL_DID, state.myDid_!)

      //if issuer identity initialized, we should use issuer DID as public DID (as this should be an edge agent (VAS))
      //if issuer identity is NOT initialized, we should use ownerDID as public DID (as this should be a cloud agent (EAS))
      //this is legacy way of how public DID is being handled
      //'ownerDIDReq' is basically a self relationship id
      // (which may be wrong to be used as public DID, but thats how it is being used so far)
      // we should do some long term backward/forward compatible fix may be
      case MY_PUBLIC_DID                            => Parameter(MY_PUBLIC_DID, state.publicIdentity.map(_.DID).getOrElse(state.myDid_!))
      case MY_ISSUER_DID                            => Parameter(MY_ISSUER_DID, state.publicIdentity.map(_.DID).getOrElse("")) // FIXME what to do if publicIdentity is not setup
    }

    getAgencyVerKeyFut map paramMap

  }

  override def senderParticipantId(senderVerKey: Option[VerKey]): ParticipantId = {
    val edgeAgentVerKeys = allAuthedKeys
    if (senderVerKey.exists(svk => edgeAgentVerKeys.contains(svk))) {
      ParticipantUtil.participantId(state.thisAgentKeyDIDReq, state.myDid)
    } else {
      throw new RuntimeException("unsupported use case")
    }
  }

  def encParamFromThisAgentToOwner: EncryptParam = {
    EncryptParam(
      Set(KeyInfo(Left(getVerKeyReqViaCache(state.myDid_!)))),
      Option(KeyInfo(Left(state.thisAgentVerKeyReq)))
    )
  }

  /**
   * This function is used in cases when protocol can receive its messages from both 'user agent' and 'user agent pairwise' actor.
   *
   * Connecting protocol is initiated from 'user agent' actor, but "accept connection" message is received from 'user agent pairwise' actor.
   * If we did not sync the thread context, response after accept could not differentiate if
   * it should respond in REST_PLAN or MSG_PACK or INDY_PACK etc.
   *
   */
  override def handleSendSignalMsg[A](ssm: SendSignalMsg[A]): Unit = {
    ssm.msg match {
      case msg: ConnReqRespMsg_MFV_0_6 =>
        val threadContextDetail = state.threadContextDetail(ssm.pinstId)
        val cmd = InternalMsgRouteParam(msg.inviteDetail.senderDetail.DID, StoreThreadContext(ssm.pinstId, threadContextDetail))
        val fut = agentActorContext.agentMsgRouter.execute(cmd)
        fut.map { _ =>
          self ! ProcessSendSignalMsg(ssm)
        }
      case _ => processSendSignalMsg(ssm)
    }
  }

  setAndOpenWalletIfExists(entityId)

  /**
   * this function gets executed post successful actor recovery (meaning all events are applied to state)
   * the purpose of this function is to update any 'LegacyAuthorizedKey' to 'AuthorizedKey'
   */
  override def postSuccessfulActorRecovery(): Unit = {
    if (state.relationship.nonEmpty) {
      val updatedMyDidDoc = RelationshipUtil.updatedDidDocWithMigratedAuthKeys(state.myDidDoc)
      val updatedRel = state.relationship.copy(myDidDoc = updatedMyDidDoc)
      state.updateRelationship(updatedRel)
    }
  }

  /**
   * this is in-memory state only
   */
  var pendingEdgeAuthKeyToBeAdded: Option[PendingAuthKey] = None

  def ownerDID: Option[DID] = state.myDid
  def ownerAgentKeyDID: Option[DID] = state.thisAgentKeyDID

  /**
   * there are different types of actors (agency agent, agency pairwise, user agent and user agent pairwise)
   * when we store the persistence detail, we store these unique id for each of them
   * which then used during routing to know which type of region actor to be used to route the message
   *
   * @return
   */
  override def actorTypeId: Int = ACTOR_TYPE_USER_AGENT_ACTOR
}


case class PairwiseConnSetExt(agentDID: DID, reqMsgContext: ReqMsgContext)
case class PendingAuthKey(rka: RequesterKeyAdded, applied: Boolean = false)

//cmd
case object GetAllComMethods extends ActorMessageObject
case object GetPushComMethods extends ActorMessageObject
case object GetHttpComMethods extends ActorMessageObject
case object GetFwdComMethods extends ActorMessageObject
case class DeleteComMethod(value: String, reason: String) extends ActorMessageClass

//response msgs
case class Initialized(pairwiseDID: DID, pairwiseDIDVerKey: VerKey) extends ActorMessageClass
case class ComMethodsPackaging(pkgType: MsgPackVersion = MPV_INDY_PACK, recipientKeys: Set[VerKey]) extends ActorMessageClass
case class ComMethodDetail(`type`: Int, value: String, packaging: Option[ComMethodsPackaging]=None) extends ActorMessageClass
case class AgentProvisioningDone(selfDID: DID, agentVerKey: VerKey, threadId: ThreadId) extends ActorMessageClass

case class CommunicationMethods(comMethods: Set[ComMethodDetail], sponsorId: Option[String]=None) extends ActorMessageClass {

  def filterByType(comMethodType: Int): Set[ComMethodDetail] = {
    filterByTypes(Seq(comMethodType))
  }

  def filterByTypes(comMethodTypes: Seq[Int]): Set[ComMethodDetail] = {
    comMethods.filter{ m =>
      comMethodTypes.contains(m.`type`)
    }
  }
}
