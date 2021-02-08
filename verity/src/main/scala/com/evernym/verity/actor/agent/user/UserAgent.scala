package com.evernym.verity.actor.agent.user

import akka.actor.ActorRef
import akka.event.LoggingReceive
import akka.pattern.ask
import com.evernym.verity.Exceptions.{BadRequestErrorException, HandledErrorException, InternalServerErrorException}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status._
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_MSG_PACK, MPF_PLAIN, Unrecognized}
import com.evernym.verity.actor.agent._
import com.evernym.verity.actor.agent.msghandler.incoming.{ControlMsg, SignalMsgParam}
import com.evernym.verity.actor.agent.msghandler.outgoing.{MsgNotifierForUserAgent, NotifyMsgDetail}
import com.evernym.verity.actor.agent.msgrouter.PackedMsgRouteParam
import com.evernym.verity.actor.agent.relationship.Tags.{CLOUD_AGENT_KEY, EDGE_AGENT_KEY, RECIP_KEY, RECOVERY_KEY}
import com.evernym.verity.actor.agent.relationship.{EndpointType, PackagingContext, RelationshipUtil, SelfRelationship, _}
import com.evernym.verity.actor.agent.state.base.AgentStateImplBase
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.msg_tracer.progress_tracker.{ChildEvent, MsgEvent}
import com.evernym.verity.actor.wallet._
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgfamily.configs._
import com.evernym.verity.agentmsg.msgfamily.pairwise._
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgPackagingUtil, AgentMsgWrapper}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.ledger.TransactionAuthorAgreement
import com.evernym.verity.libindy.ledger.IndyLedgerPoolConnManager
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.legacy.services.CreateKeyEndpointDetail
import com.evernym.verity.protocol.protocols.connecting.common.{ConnReqReceived, SendMsgToRegisteredEndpoint}
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.PublicIdentifierCreated
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Ctl
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.CreatePairwiseKey
import com.evernym.verity.protocol.protocols.walletBackup.WalletBackupMsgFamily.{ProvideRecoveryDetails, RecoveryKeyRegistered}
import com.evernym.verity.push_notification.PusherUtil
import com.evernym.verity.util.Util._
import com.evernym.verity.util._
import com.evernym.verity.vault._
import com.evernym.verity.{ActorErrorResp, UrlParam, actor}

import scala.concurrent.Future
import scala.util.{Failure, Left, Success}

/**
 Represents user's agent
 */
class UserAgent(val agentActorContext: AgentActorContext)
  extends UserAgentCommon
    with UserAgentStateUpdateImpl
    with HasAgentActivity
    with MsgNotifierForUserAgent
    with AgentSnapshotter[UserAgentState] {

  type StateType = UserAgentState
  var state = new UserAgentState

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
    case GetFwdComMethods                        => sendFwdComMethods()
    case GetPushComMethods                       => sendPushComMethods()
    case GetHttpComMethods                       => sendHttpComMethods()
    case dcm: DeleteComMethod                    => handleDeleteComMethod(dcm)
    case ads: AgentDetailSet                     => handleAgentDetailSet(ads)
    case GetSponsorRel                           => sendSponsorDetails()
    case hck: HandleCreateKeyWithThisAgentKey    =>
      handleCreateKeyWithThisAgentKey(hck.thisAgentKey, hck.createKeyReqMsg)(hck.reqMsgContext)
  }

  override def handleSpecificSignalMsgs: PartialFunction[SignalMsgParam, Future[Option[ControlMsg]]] = {
    // Here, "Driver" means the same thing that the community calls a "Controller".
    // TODO: align with community terminology.
    case SignalMsgParam(_: ConnReqReceived, _)                 => Future.successful(None)
    case SignalMsgParam(sm: SendMsgToRegisteredEndpoint, _)    => sendAgentMsgToRegisteredEndpoint(sm)
    case SignalMsgParam(prd: ProvideRecoveryDetails, _)        => registerRecoveryKey(prd.params.recoveryVk)
    case SignalMsgParam(_: CreatePairwiseKey, _)               => createNewPairwiseEndpoint()
    case SignalMsgParam(pic: PublicIdentifierCreated, _)       => storePublicIdentity(pic.identifier.did, pic.identifier.verKey)
  }

  override final def receiveAgentEvent: Receive = commonEventReceiver orElse eventReceiver orElse msgEventReceiver

  val eventReceiver: Receive = {
    case ods: OwnerDIDSet                  => handleOwnerDIDSet(ods.ownerDID)
    case akc: AgentKeyCreated              => handleAgentKeyCreated(akc.forDID)
    case rka: RequesterKeyAdded            => handleAuthKeyAdded(rka)
    case rka: RecoveryKeyAdded             => handleRecoveryKeyAdded(rka.verKey)
    case cmu: ComMethodUpdated             => handleUpdateAuthKeyAndEndpoint(cmu)
    case cmd: ComMethodDeleted             => handleRemoveComMethod(cmd)
    case sa: SponsorAssigned               => setSponsorRel(SponsorRel(sa.id, sa.sponsee))
    case pis: PublicIdentityStored         => state = state.withPublicIdentity(DidPair(pis.DID, pis.verKey))

      //this is received for each new pairwise connection/actor that gets created
    case ads: AgentDetailSet               => addRelationshipAgent(AgentDetail(ads.forDID, ads.agentKeyDID))
  }

  def handleRemoveComMethod(cmd: ComMethodDeleted): Unit = {
    state = state.copy(relationship = state.relWithEndpointRemoved(cmd.id))
  }

  def handleUpdateAuthKeyAndEndpoint(cmu: ComMethodUpdated): Unit = {
    val existingEdgeAuthKeys = state.myDidDoc_!.authorizedKeys_!.filterByTags(EDGE_AGENT_KEY)
    val newAuthKeys = cmu.packaging.map(_.recipientKeys).getOrElse(Seq.empty).toSet
    val authKeyIds = newAuthKeys.flatMap { verKey =>

      //for now, using 'verKey' as the keyId, if required, it can be changed
      state = state.copy(relationship = state.relWithAuthKeyMergedToMyDidDoc(verKey, verKey, Set(RECIP_KEY)))
      state.myDidDoc_!.authorizedKeys_!.findByVerKey(verKey).map(_.keyId)
    }
    val allAuthKeyIds = (authKeyIds ++ existingEdgeAuthKeys.map(_.keyId)).toSeq
    val packagingContext = cmu.packaging.map(p => PackagingContext(p.pkgType))
    val endpoint: EndpointADTUntyped = cmu.typ match {
      case EndpointType.PUSH        => PushEndpoint(cmu.id, cmu.value)
      case EndpointType.SPR_PUSH    => SponsorPushEndpoint(cmu.id, cmu.value)
      case EndpointType.HTTP        => HttpEndpoint(cmu.id, cmu.value, allAuthKeyIds, packagingContext)
      case EndpointType.FWD_PUSH    => ForwardPushEndpoint(cmu.id, cmu.value, allAuthKeyIds, packagingContext)
    }
    state = state.copy(relationship = state.relWithEndpointAddedOrUpdatedInMyDidDoc(endpoint))
  }

  def handleOwnerDIDSet(did: DID): Unit = {
    val myDidDoc = RelationshipUtil.prepareMyDidDoc(did, did, Set(EDGE_AGENT_KEY))
    state = state.withRelationship(SelfRelationship(myDidDoc))
  }

  def handleAgentKeyCreated(forDID: DID): Unit = {
    state = state.withThisAgentKeyId(forDID)
    if (forDID != state.myDid_!) {
      state = state.copy(relationship = state.relWithNewAuthKeyAddedInMyDidDoc(
        forDID, getVerKeyReqViaCache(forDID), Set(CLOUD_AGENT_KEY)))
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
      state = state.copy(relationship = state.relWithNewAuthKeyAddedInMyDidDoc(
        state.myDid_!, rka.verKey, Set(EDGE_AGENT_KEY)))
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
    state = state.copy(relationship = state.relWithNewAuthKeyAddedInMyDidDoc(
      "recovery-key", verKey, Set(RECOVERY_KEY)))
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

  def sendSponsorDetails(): Unit =
    sender() ! sponsorRel.getOrElse(SponsorRel.empty)

  override def sponsorRel: Option[SponsorRel] = Option(state.sponsorRel.getOrElse(SponsorRel.empty))

  def handleAgentDetailSet(ads: AgentDetailSet): Unit = {
    if (state.relationshipAgentsContains(ads.forDID)) {
      sender ! Done
    } else {
      writeApplyAndSendItBack(ads)
    }
  }

  def handleDeleteComMethod(dcm: DeleteComMethod): Unit = {
    state.myDidDoc_!.endpoints_!.filterByValues(dcm.value).foreach { ep =>
      state = state.copy(relationship = state.relWithEndpointRemoved(ep.id))
      writeAndApply(ComMethodDeleted(ep.id, ep.value, dcm.reason))
      logger.debug(s"com method deleted (userDID=<${state.myDid}>, id=${ep.id}, " +
        s"value=${ep.value}, reason=${dcm.reason})", (LOG_KEY_SRC_DID, state.myDid))
    }
  }

  def getComMethods(types: Seq[Int] = Seq.empty): CommunicationMethods =
    comMethodsByTypes(types, state.sponsorRel.map(_.sponsorId))

  def sendComMethodsByType(filterComMethodTypes: Seq[Int]): Unit = {
    logger.debug("about to send com methods...")
    val filteredComMethods = getComMethods(filterComMethodTypes)
    sender ! filteredComMethods
    logger.debug("com methods sent: " + filteredComMethods)
  }

  def comMethodsByTypes(types: Seq[Int], withSponsorId: Option[String]): CommunicationMethods = {
    val endpoints = if (types.nonEmpty) state.myDidDoc_!.endpoints_!.filterByTypes(types: _*)
      else state.myDidDoc_!.endpoints_!.endpoints
    val comMethods = endpoints.map { ep =>
      val verKeys = state.myDidDoc_!.authorizedKeys_!.safeAuthorizedKeys
        .filterByKeyIds(ep.authKeyIds)
        .map(_.verKey).toSet
      val cmp = ep.packagingContext.map(pc => ComMethodsPackaging(pc.packFormat, verKeys))
      ComMethodDetail(ep.`type`, ep.value, cmp)
    }
    CommunicationMethods(comMethods.toSet, withSponsorId)
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
    val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackFormat, param)(agentMsgTransformer, wap)
    sendRespMsg("CreateNewPairwiseKeyResp", rp, sndr)
  }

  def handleCreateKeyMsg(createKeyReqMsg: CreateKeyReqMsg)(implicit reqMsgContext: ReqMsgContext): Unit = {
    addUserResourceUsage(reqMsgContext.clientIpAddressReq, RESOURCE_TYPE_MESSAGE, MSG_TYPE_CREATE_KEY, state.myDid)
    checkIfKeyNotCreated(createKeyReqMsg.forDID)
    val sndr = sender()
    walletAPI.executeAsync[NewKeyCreated](CreateNewKey()).map { thisAgentKey =>
      self.tell(HandleCreateKeyWithThisAgentKey(thisAgentKey, createKeyReqMsg, reqMsgContext), sndr)
    }
  }

  def handleCreateKeyWithThisAgentKey(thisAgentKey: NewKeyCreated,
                                      createKeyReqMsg: CreateKeyReqMsg)
                                     (implicit reqMsgContext: ReqMsgContext): Unit = {
    writeAndApply(AgentDetailSet(createKeyReqMsg.forDID, thisAgentKey.did))
    val futResp = createNewPairwiseEndpointBase(
      thisAgentKey, createKeyReqMsg.forDID, Option(createKeyReqMsg.forDIDVerKey))
    val sndr = sender()
    handleInitPairwiseConnResp(thisAgentKey.did, futResp, sndr)
  }

  def createNewPairwiseEndpoint(): Future[Option[ControlMsg]] = {
    walletAPI.executeAsync[NewKeyCreated](CreateNewKey()).flatMap { requesterKey =>
      val respFut = createNewPairwiseEndpointBase(requesterKey, requesterKey.did, Option(requesterKey.verKey))
      respFut.map(_ => Option(ControlMsg(Ctl.KeyCreated(requesterKey.did, requesterKey.verKey))))
    }
  }

  def createNewPairwiseEndpointBase(thisAgentKey: NewKeyCreated, requesterDID: DID, requesterVerKeyOpt: Option[VerKey]=None)
  : Future[Any] = {
    val requesterVerKeyFut = requesterVerKeyOpt match {
      case Some(vk) => Future.successful(vk)
      case None     => walletAPI.executeAsync[VerKey](GetVerKey(requesterDID))
    }
    val endpointDIDFut = requesterVerKeyFut.flatMap { requesterVerKey =>
      if (requesterDID != thisAgentKey.did) {
        walletAPI.executeAsync[TheirKeyStored](
          StoreTheirKey(requesterDID, requesterVerKey, ignoreIfAlreadyExists = true)).map { _ =>
          thisAgentKey.did
        }
      } else Future.successful(requesterDID)
    }

    endpointDIDFut.flatMap { endpointDID =>
      val cke = buildSetupCreateKeyEndpoint(requesterDID, endpointDID)
      userAgentPairwiseRegion ? ForIdentifier(getNewActorId, cke)
    }
  }

  def buildSetupCreateKeyEndpoint(forDID: DID, newAgentPairwiseVerKeyDID: DID): SetupCreateKeyEndpoint = {
    SetupCreateKeyEndpoint(newAgentPairwiseVerKeyDID, forDID,
      state.myDid_!, state.thisAgentKeyDID, agentWalletId, None,
      state.publicIdentity.orElse(state.configs.get(PUBLIC_DID).map(c => DidPair(c.value, "")))
    )
  }

  def buildAndSendComMethodUpdatedRespMsg(comMethod: ComMethod)(implicit reqMsgContext: ReqMsgContext): Unit = {
    val comMethodUpdatedRespMsg = UpdateComMethodMsgHelper.buildRespMsg(comMethod.id)(reqMsgContext.agentMsgContext)
    reqMsgContext.msgPackFormat match {
      case MPF_PLAIN =>
        sender ! comMethodUpdatedRespMsg.head

        // to test if http endpoint is working send response also on it.
        if (comMethod.`type` == COM_METHOD_TYPE_HTTP_ENDPOINT) {
          comMethodUpdatedRespMsg.head match {
            case resp: ComMethodUpdatedRespMsg_MFV_0_6 =>
              val jsonMsg = AgentMsgPackagingUtil.buildAgentMsgJson(comMethodUpdatedRespMsg, wrapInBundledMsgs = false)
              sendMsgToRegisteredEndpoint(
                NotifyMsgDetail.withTrackingId("ComMethodUpdated"),
                PayloadWrapper(
                  jsonMsg.getBytes,
                  Option(PayloadMetadata(resp.`@type`, MPF_PLAIN))),
                None
              )
            case _ =>
          }
        }
      case MPF_INDY_PACK | MPF_MSG_PACK =>
        val param = AgentMsgPackagingUtil.buildPackMsgParam (encParamFromThisAgentToOwner, comMethodUpdatedRespMsg)
        val rp = AgentMsgPackagingUtil.buildAgentMsg (reqMsgContext.msgPackFormat, param) (agentMsgTransformer, wap)
        sendRespMsg("ComMethodUpdatedResp", rp)
      case Unrecognized(_) =>
        throw new RuntimeException("unsupported msgPackFormat: Unrecognized can't be used here")
    }
  }

  def isOnlyOneComMethodAllowed(comType: Int): Boolean =
    Set(COM_METHOD_TYPE_PUSH, COM_METHOD_TYPE_FWD_PUSH).contains(comType)

  def processValidatedUpdateComMethodMsg(comMethod: ComMethod)(implicit reqMsgContext: ReqMsgContext): Unit = {
    val authKeyIds = state.myDidDoc_!.endpoints.map(_.authKeyIdsForEndpoint(comMethod.id)).getOrElse(Set.empty)
    val verKeys = state.myDidDoc_!.authorizedKeys_!.safeAuthorizedKeys
      .filterByKeyIds(authKeyIds)
      .map(_.verKey).toSet
    val existingEndpointOpt = state.myDidDoc_!.endpoints_!.findById(comMethod.id)
    val isComMethodExistsWithSameValue = existingEndpointOpt.exists{ eep =>
      eep.`type` == comMethod.`type` && eep.value == comMethod.value && {
        (eep.packagingContext, comMethod.packaging) match {
          case (Some(ecmp), Some(newp)) =>
            ecmp.packFormat.isEqual(newp.pkgType) && newp.recipientKeys.exists(_.exists(verKeys.contains))
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
      logger.info(s"update com method updated - id=${comMethod.id} - type: ${comMethod.id} - " +
        s"value: ${comMethod.value}")
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
    val comMethod = validatedComMethod(ucm)
    processValidatedUpdateComMethodMsg(comMethod)
    buildAndSendComMethodUpdatedRespMsg(comMethod)
  }

  def validatedComMethod(ucm: UpdateComMethodReqMsg)(implicit reqMsgContext: ReqMsgContext): ComMethod = {
    try {
      ucm.comMethod.`type` match {
        case COM_METHOD_TYPE_PUSH =>
          PusherUtil.checkIfValidPushComMethod(
            ComMethodDetail(COM_METHOD_TYPE_PUSH, ucm.comMethod.value), appConfig)
          ucm.comMethod
        case COM_METHOD_TYPE_HTTP_ENDPOINT => UrlParam(ucm.comMethod.value); ucm.comMethod
        case COM_METHOD_TYPE_FWD_PUSH =>
          if (state.sponsorRel.isEmpty) {
            throw new BadRequestErrorException(INVALID_VALUE.statusCode,
              Option("no sponsor registered - cannot register fwd method"))
          } else {
            ComMethod(ucm.comMethod.id, ucm.comMethod.`type`, ucm.comMethod.value, None)
          }
        case COM_METHOD_TYPE_SPR_PUSH =>
          if (state.sponsorRel.isEmpty) {
            throw new BadRequestErrorException(INVALID_VALUE.statusCode,
              Option("no sponsor registered - cannot register sponsor push method"))
          } else {
            ComMethod(ucm.comMethod.id, ucm.comMethod.`type`, ucm.comMethod.value, None)
          }
      }
    } catch {
      case e: RuntimeException =>
        recordInMsgChildEvent(reqMsgContext.id,
          s"${MsgEvent.DEFAULT_TRACKING_MSG_ID}-$actorTypeId",
          ChildEvent("validation-error", "error while validating com method: " + e.getMessage))
        throw e
    }
  }

  def validatePairwiseFromDIDs(givenPairwiseFromDIDs: List[DID]): Unit = {
    val unmatched = givenPairwiseFromDIDs.filter(pd => ! state.relationshipAgents.contains(pd))
    if (unmatched.nonEmpty) {
      throw new BadRequestErrorException(INVALID_VALUE.statusCode,
        Option(s"no pairwise connection found with these DIDs: ${unmatched.mkString(", ")}"))
    }
  }

  def buildUpdateMsgStatusReq(updateMsgStatusByConnsReq: UpdateMsgStatusByConnsReqMsg, agentVerKey: VerKey)
                             (implicit reqMsgContext: ReqMsgContext): Future[List[(String, Any)]] = {
    val pairwiseTargetKeys = updateMsgStatusByConnsReq.uidsByConns.map { uc =>
      val pc = state.relationshipAgentByForDid(uc.pairwiseDID)
        ( uc,
          pc,
          EncryptParam(
            Set(KeyParam(Left(getVerKeyReqViaCache(pc.agentKeyDID)))),
              Option(KeyParam(Left(agentVerKey)))
          )
        )
    }
    Future.traverse(pairwiseTargetKeys) { case (pmu, ad, encParam) =>
      val updateMsgStatusReq =
        reqMsgContext.msgPackFormat match {
          case MPF_MSG_PACK =>
            DefaultMsgCodec.toJson(
              UpdateMsgStatusReqMsg_MFV_0_5(
                TypeDetail(MSG_TYPE_UPDATE_MSG_STATUS, MTV_1_0, None),
                updateMsgStatusByConnsReq.statusCode, pmu.uids
              )
            )
          case MPF_INDY_PACK =>
            DefaultMsgCodec.toJson(
              UpdateMsgStatusReqMsg_MFV_0_6(
                MSG_TYPE_DETAIL_UPDATE_MSG_STATUS,
                updateMsgStatusByConnsReq.statusCode,
                pmu.uids
              )
            )
          case x => throw new RuntimeException("unsupported msg pack format: " + x)
        }
      val rmi = reqMsgContext.copy()
      rmi.data = reqMsgContext.data.filter(kv => Set(CLIENT_IP_ADDRESS).contains(kv._1))
      agentActorContext.agentMsgTransformer.packAsync(reqMsgContext.msgPackFormat, updateMsgStatusReq, encParam).flatMap { packedMsg =>
        val fut = agentActorContext.agentMsgRouter.execute(
          PackedMsgRouteParam(ad.agentKeyDID, packedMsg, rmi))
        fut.map(f => (ad.forDID, f))
      }
    }
  }

  def buildSuccessfullyUpdatedMsgStatusResp(success: List[(String, Any)], agentVerKey: VerKey):
    Future[Map[String, List[String]]] = {
    val result = success.map { case (fromDID, pairwiseRespMsg) =>
      pairwiseRespMsg match {
        case pm: PackedMsg =>
          agentActorContext.agentMsgTransformer.unpackAsync(pm.msg, KeyParam(Left(agentVerKey))).map { unpackedMsg =>
            val msgIds = unpackedMsg.msgPackFormat match {
              case MPF_MSG_PACK => unpackedMsg.headAgentMsg.convertTo[MsgStatusUpdatedRespMsg_MFV_0_5].uids
              case MPF_INDY_PACK => unpackedMsg.headAgentMsg.convertTo[MsgStatusUpdatedRespMsg_MFV_0_6].uids
              case x => throw new RuntimeException("unsupported msg pack format: " + x)
            }
            fromDID -> msgIds
          }
        case other =>
          Future.failed(new RuntimeException("unexpected error: " + other.toString))
      }
    }
    Future.sequence(result).map { pairwiseResult =>
      pairwiseResult.filter(_._2.nonEmpty).toMap
    }
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

  def parseBuildAndSendResp(respMsgType: String,
                            respMsgs: List[(String, Any)],
                            agentVerKey: VerKey,
                            sndr: ActorRef)
                           (implicit reqMsgContext: ReqMsgContext): Unit = {
    val (success, others) = respMsgs.partition { case (_, r) => r.isInstanceOf[PackedMsg] }
    val errorResult = buildFailedUpdateMsgStatusResp(others)
    buildSuccessfullyUpdatedMsgStatusResp(success, agentVerKey).map { successResult =>
      val msgStatusUpdatedByConnsRespMsg =
        UpdateMsgStatusByConnsMsgHelper.buildRespMsg(successResult, errorResult)(reqMsgContext.agentMsgContext)
      val wrapInBundledMsg = reqMsgContext.msgPackFormat match {
        case MPF_MSG_PACK => true
        case _            => false
      }
      val param = AgentMsgPackagingUtil.buildPackMsgParam(encParamFromThisAgentToOwner, msgStatusUpdatedByConnsRespMsg, wrapInBundledMsg)
      val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackFormat, param)(agentMsgTransformer, wap)
      sendRespMsg(respMsgType, rp, sndr)
    }
  }

  def handleUpdateMsgStatusByConnsFutResp(futResp: Future[List[(String, Any)]], agentVerKey: VerKey, sndr: ActorRef)
                                         (implicit reqMsgContext: ReqMsgContext): Unit = {
    futResp.onComplete {
      case Success(respMsgs) =>
        try {
          parseBuildAndSendResp("MsgStatusUpdatedByConnsResp", respMsgs, agentVerKey, sndr)
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
    handleUpdateMsgStatusByConnsFutResp(reqFut, agentVerKey, sndr)
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
    val pairwiseTargetKeys = filteredPairwiseConns.map { ad =>
      (ad,
        EncryptParam(
          Set(KeyParam(Left(getVerKeyReqViaCache(ad.agentKeyDID)))),
          Option(KeyParam(Left(agentVerKey))))
      )
    }

    val rmi = reqMsgContext.copy()
    rmi.data = reqMsgContext.data.filter(kv => Set(CLIENT_IP_ADDRESS, MSG_PACK_VERSION).contains(kv._1))

    val result = Future.traverse(pairwiseTargetKeys) { case (pc, encParam) =>
      agentActorContext.agentMsgTransformer.packAsync(MPF_MSG_PACK, getMsg, encParam).flatMap { packedMsg =>
        agentActorContext.agentMsgRouter.execute(PackedMsgRouteParam(pc.agentKeyDID, packedMsg, rmi))
          .map {
            case pm: PackedMsg => Option(pc.forDID, pm)
            case aer: ActorErrorResp =>
              logger.error(s"error occurred while getting messages from connection " +
                s"(connection did hash code ${pc.forDID.hashCode}): " + aer)
              None
          }
      }
    }
    result.map(_.flatten)
  }

  def handleGetMsgsRespMsgFromPairwiseActor(respFut: Future[List[(String, PackedMsg)]], sndr: ActorRef)
                                           (implicit reqMsgContext: ReqMsgContext): Unit = {
    respFut.onComplete {
      case Success(respMsgs) =>
        val agentVerKey = state.thisAgentVerKeyReq
        val pairwiseResults = respMsgs.map { case (fromDID, respMsg) =>
          agentActorContext.agentMsgTransformer.unpackAsync(respMsg.msg, KeyParam(Left(agentVerKey))).map { amw =>
            val msgs = reqMsgContext.msgPackFormat match {
              case MPF_MSG_PACK | MPF_INDY_PACK => amw.headAgentMsg.convertTo[GetMsgsRespMsg_MFV_0_5].msgs
              case x => throw new BadRequestErrorException(BAD_REQUEST.statusCode, Option("msg pack format not supported: " + x))
            }
            val msgType = respMsg.metadata.map(_.msgTypeStr).getOrElse("")
            AgentActivityTracker.track(msgType, domainId, Some(fromDID))
            fromDID -> msgs
          }
        }
        Future.sequence(pairwiseResults).map { pairwiseResult =>
          val result = pairwiseResult.toMap
          val getMsgsByConnsRespMsg = GetMsgsByConnsMsgHelper.buildRespMsg(result)(reqMsgContext.agentMsgContext)
          val param = AgentMsgPackagingUtil.buildPackMsgParam(encParamFromThisAgentToOwner,
            getMsgsByConnsRespMsg, reqMsgContext.agentMsgContext.msgPackFormat == MPF_MSG_PACK)
          val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackFormat, param)(agentMsgTransformer, wap)
          sendRespMsg("GetMsgsByConnsResp", rp, sndr)
        }.recover {
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
      givenPairwiseDIDs.map(pd => state.relationshipAgentByForDid(pd))
    } else state.relationshipAgentDetails
    val reqFut = prepareAndSendGetMsgsReqMsgToPairwiseActor(getMsgsByConnsReq, filteredPairwiseConns)
    handleGetMsgsRespMsgFromPairwiseActor(reqFut, sndr)
  }

  def handleInit(se: SetupEndpoint): Unit = {
    val evt = OwnerDIDSet(se.ownerDID)
    writeAndApply(evt)
    writeAndApply(AgentKeyCreated(se.agentKeyDID))
    val setRouteFut = setRoute(se.ownerDID, Option(se.agentKeyDID))
    var sponsorRel: Option[SponsorRel] = None
    val sndr = sender()
    val resp = se match {
      case s: SetupAgentEndpoint_V_0_7  =>
        sponsorRel = s.sponsorRel
        sponsorRel.foreach(setSponsorDetail)
        logger.debug(s"User Agent initialized with V0.7")
        writeAndApply(RequesterKeyAdded(s.requesterVerKey))
        AgentProvisioningDone(s.ownerDID, getVerKeyReqViaCache(s.agentKeyDID), s.threadId)
      case _: SetupAgentEndpoint        =>
        logger.debug(s"User Agent initialized (old protocol)")
        Done
    }

    logger.info(s"new user agent created - domainId: ${se.ownerDID}, sponsorRel: $sponsorRel")
    AgentActivityTracker.newAgent(sponsorRel)

    setRouteFut map {
      case _: RouteSet  => sndr ! resp
      case _            => throw new RuntimeException("route not set for user agent")
    }
  }

  def authedMsgSenderVerKeys: Set[VerKey] = (
    state.relationship.map(_.myDidDocAuthKeysByTag(EDGE_AGENT_KEY)).getOrElse(Set.empty) ++
      state.relationship.map(_.myDidDocAuthKeysByTag(RECOVERY_KEY)).getOrElse(Set.empty)
    ).flatMap(_.verKeyOpt).toSet

  def checkIfKeyNotCreated(forDID: DID): Unit = {
    if (state.relationshipAgents.contains(forDID)) {
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
        agentWalletId)
    )
    val filteredConfs = getFilteredConfigs(Set(NAME_KEY, LOGO_URL_KEY))

    def paramMap(agencyVerKey: VerKey): String ?=> Parameter = {
      case SELF_ID                                  => Parameter(SELF_ID, ParticipantUtil.participantId(state.thisAgentKeyDIDReq, state.thisAgentKeyDID))
      case OTHER_ID                                 => Parameter(OTHER_ID, ParticipantUtil.participantId(state.thisAgentKeyDIDReq, state.myDid))
      case NAME                                     => Parameter(NAME, agentName(filteredConfs))
      case LOGO_URL                                 => Parameter(LOGO_URL, agentLogoUrl(filteredConfs))
      case AGENCY_DID                               => Parameter(AGENCY_DID, agencyDIDReq)
      case AGENCY_DID_VER_KEY                       => Parameter(AGENCY_DID_VER_KEY, agencyVerKey)
      case THIS_AGENT_WALLET_ID                     => Parameter(THIS_AGENT_WALLET_ID, agentWalletIdReq)
      case NEW_AGENT_WALLET_ID                      => Parameter(NEW_AGENT_WALLET_ID, agentActorEntityId)
      case CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON    => Parameter(CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON, createKeyEndpointSetupDetailJson)
      case MY_SELF_REL_DID                          => Parameter(MY_SELF_REL_DID, state.myDid_!)
      case MY_PUBLIC_DID                            => Parameter(MY_PUBLIC_DID, publicIdentityDID)
      case MY_ISSUER_DID                            => Parameter(MY_ISSUER_DID, state.publicIdentity.map(_.DID).getOrElse("")) // FIXME what to do if publicIdentity is not setup
      case DEFAULT_ENDORSER_DID                     => Parameter(DEFAULT_ENDORSER_DID, defaultEndorserDid)
    }

    agencyVerKeyFut map paramMap

  }

  override def senderParticipantId(senderVerKey: Option[VerKey]): ParticipantId = {
    val edgeAgentVerKeys = allAuthedKeys
    if (senderVerKey.exists(svk => edgeAgentVerKeys.contains(svk))) {
      ParticipantUtil.participantId(state.thisAgentKeyDIDReq, state.myDid)
    } else {
      throw new RuntimeException("unsupported use case")
    }
  }

  def publicIdentityDID: DID =
    if (!useLegacyPublicIdentityBehaviour)
      state.publicIdentity.map(_.DID).orElse(state.configs.get(PUBLIC_DID).map(_.value)).getOrElse("")
    else
      state.publicIdentity.map(_.DID).getOrElse(state.myDid_!)

  def encParamFromThisAgentToOwner: EncryptParam = {
    EncryptParam(
      Set(KeyParam(Left(getVerKeyReqViaCache(state.myDid_!)))),
      Option(KeyParam(Left(state.thisAgentVerKeyReq)))
    )
  }

  setAndOpenWalletIfExists(entityId)

  /**
   * this function gets executed post successful actor recovery (meaning all events are applied to state)
   * the purpose of this function is to update any 'LegacyAuthorizedKey' to 'AuthorizedKey'
   */
  override def postSuccessfulActorRecovery(): Unit = {
    super.postSuccessfulActorRecovery()
    if (state.relationship.nonEmpty) {
      state = state
        .relationship
        .map { r =>
          val updatedMyDidDoc = RelationshipUtil.updatedDidDocWithMigratedAuthKeys(state.myDidDoc)
          state.withRelationship(r.update(_.myDidDoc.setIfDefined(updatedMyDidDoc)))
        }
        .getOrElse(state)
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
case object GetAllComMethods extends ActorMessage
case object GetPushComMethods extends ActorMessage
case object GetHttpComMethods extends ActorMessage
case object GetFwdComMethods extends ActorMessage
case object GetSponsorRel extends ActorMessage
case class DeleteComMethod(value: String, reason: String) extends ActorMessage

//response msgs
case class Initialized(pairwiseDID: DID, pairwiseDIDVerKey: VerKey) extends ActorMessage
case class ComMethodsPackaging(pkgType: MsgPackFormat = MPF_INDY_PACK, recipientKeys: Set[VerKey]) extends ActorMessage
case class ComMethodDetail(`type`: Int, value: String, packaging: Option[ComMethodsPackaging]=None) extends ActorMessage
case class AgentProvisioningDone(selfDID: DID, agentVerKey: VerKey, threadId: ThreadId) extends ActorMessage

case class CommunicationMethods(comMethods: Set[ComMethodDetail], sponsorId: Option[String]=None) extends ActorMessage {

  def filterByType(comMethodType: Int): Set[ComMethodDetail] = {
    filterByTypes(Seq(comMethodType))
  }

  def filterByTypes(comMethodTypes: Seq[Int]): Set[ComMethodDetail] = {
    comMethods.filter{ m =>
      comMethodTypes.contains(m.`type`)
    }
  }
}


trait UserAgentStateImpl
  extends AgentStateImplBase
    with UserAgentCommonState { this: UserAgentState =>

  def domainId: DomainId = relationshipReq.myDid_!
  def relationshipAgentsContains(forDID: DID): Boolean =
    relationshipAgents.contains(forDID)
  def relationshipAgentDetails: List[AgentDetail] =
    relationshipAgents.map(r => AgentDetail(r._1, r._2)).toList
  def relationshipAgentByForDid(did: DID): AgentDetail = {
    AgentDetail(did,
      relationshipAgents.getOrElse(did,
        throw new RuntimeException("relationship agent doesn't exists for DID: " + did)
      )
    )
  }
}

trait UserAgentStateUpdateImpl
  extends UserAgentCommonStateUpdateImpl { this : UserAgent =>

  def msgAndDelivery: Option[MsgAndDelivery] = state.msgAndDelivery

  override def setAgentWalletId(walletId: String): Unit = {
    state = state.withAgentWalletId(walletId)
  }

  override def setAgencyDID(did: DID): Unit = {
    state = state.withAgencyDID(did)
  }

  def setSponsorRel(rel: SponsorRel): Unit = {
    state = state.withSponsorRel(rel)
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

  def addRelationshipAgent(ad: AgentDetail): Unit = {
    state = state.withRelationshipAgents(state.relationshipAgents + (ad.forDID -> ad.agentKeyDID))
  }

  def addConfig(name: String, ac: AgentConfig): Unit = {
    state = state.withConfigs(state.configs ++ Map(name -> ac.toConfigValue))
  }

  def removeConfig(name: String): Unit = {
    state = state.withConfigs(state.configs.filterNot(_._1 == name))
  }

  def updateMsgAndDelivery(msgAndDelivery: MsgAndDelivery): Unit = {
    state = state.withMsgAndDelivery(msgAndDelivery)
  }

}

case class HandleCreateKeyWithThisAgentKey(thisAgentKey: NewKeyCreated, createKeyReqMsg: CreateKeyReqMsg, reqMsgContext: ReqMsgContext) extends ActorMessage
