package com.evernym.verity.actor.agent.user

import java.time.ZonedDateTime

import akka.event.LoggingReceive
import akka.pattern.ask
import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.{DATA_NOT_FOUND, MSG_STATUS_RECEIVED}
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.SpanUtil._
import com.evernym.verity.actor.agent.msghandler.AgentMsgHandler
import com.evernym.verity.actor.agent.msghandler.incoming.{ControlMsg, SignalMsgFromDriver}
import com.evernym.verity.actor.agent.msghandler.outgoing.{MsgNotifierForUserAgentCommon, OutgoingMsgParam, SendStoredMsgToSelf}
import com.evernym.verity.actor.agent.state.base.{AgentStateInterface, AgentStateUpdateInterface}
import com.evernym.verity.actor.agent.{AgencyIdentitySet, AgentActorDetailSet, ConfigValue, Msg, MsgAndDelivery, MsgAttribs, MsgDeliveryByDest, MsgDeliveryDetail, PayloadWrapper, SetAgencyIdentity, SetAgentActorDetail, SponsorRel, Thread}
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgfamily.configs._
import com.evernym.verity.agentmsg.msgfamily.pairwise.GetMsgsReqMsg
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgPackagingUtil, AgentMsgWrapper}
import com.evernym.verity.cache.{CacheQueryResponse, GetCachedObjectParam, KeyDetail}
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.protocol.actor.UpdateMsgDeliveryStatus
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols._
import com.evernym.verity.protocol.protocols.connecting.common.{NotifyUserViaPushNotif, SendMsgToRegisteredEndpoint}
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.{Config, SendConfig}
import com.evernym.verity.push_notification.PusherUtil
import com.evernym.verity.util.TimeZoneUtil._
import com.evernym.verity.util.{ParticipantUtil, ReqMsgContext, TimeZoneUtil}
import com.evernym.verity.vault._

import scala.concurrent.Future

/**
 * common logic between 'UserAgent' and 'UserAgentPairwise' actor
 */
trait UserAgentCommon
  extends AgentPersistentActor
    with UserAgentCommonStateUpdateImpl
    with MsgAndDeliveryHandler
    with AgentMsgHandler
    with ShardRegionFromActorContext
    with MsgStoreAPI
    with LEGACY_connectingSignalHandler {

  this: AgentPersistentActor with MsgNotifierForUserAgentCommon =>

  type StateType <: AgentStateInterface with UserAgentCommonState

  def msgDeliveryState: Option[MsgDeliveryState] = None

  /**
   * handler for common message types supported by 'user agent' and 'user agent pairwise' actor
   * @param reqMsgContext request message context
   * @return
   */
  def agentCommonMsgHandler(implicit reqMsgContext: ReqMsgContext): PartialFunction[Any, Any] = {
    case amw: AgentMsgWrapper if amw.isMatched(MFV_0_5, MSG_TYPE_UPDATE_CONFIGS) =>
      val tmsg = TypedMsg(UpdateConfigMsgHelper.buildReqMsg(amw), amw.msgType)
      handleUpdateConfigPackedReq(tmsg)

    case amw: AgentMsgWrapper if amw.isMatched(MFV_0_5, MSG_TYPE_REMOVE_CONFIGS) =>
      handleRemoveConfigMsg(RemoveConfigMsgHelper.buildReqMsg(amw))

    case amw: AgentMsgWrapper if amw.isMatched(MFV_0_5, MSG_TYPE_GET_CONFIGS) =>
      handleGetConfigsMsg(GetConfigsMsgHelper.buildReqMsg(amw))

    case amw: AgentMsgWrapper if amw.isMatched(MFV_0_5, MSG_TYPE_GET_MSGS) => handleGetMsgs(amw)

    case amw: AgentMsgWrapper if amw.isMatched(MFV_0_6, MSG_TYPE_GET_MSGS) => handleGetMsgs(amw)
  }

  /**
   * this handles internal commands
   */
  val commonCmdReceiver: Receive = LoggingReceive.withLabel("commonCmdReceiver") {
    case umds: UpdateMsgDeliveryStatus  => updateMsgDeliveryStatus(umds)
    case gc: GetConfigs                 => sender ! AgentConfigs(getFilteredConfigs(gc.names))
    case CheckPeriodicCleanupTasks      => checkPeriodicCleanupTasks()
    case sad: SetAgencyIdentity         => setAgencyIdentity(sad)
    case _: AgencyIdentitySet           => //nothing to od
  }

  def maxTimeToRetainSeenMsgsInMinutes: Option[Int] =
    appConfig.getConfigIntOption(AGENT_MAX_TIME_TO_RETAIN_SEEN_MSG)

  def checkPeriodicCleanupTasks(): Unit = {
    maxTimeToRetainSeenMsgsInMinutes.foreach { minutes =>
      performStateCleanup(minutes)
    }
  }

  override val receiveActorInitSpecificCmd: Receive = LoggingReceive.withLabel("receiveActorInitSpecificCmd") {
    case saw: SetAgentActorDetail   => setAgentActorDetail(saw)
    case sad: SetAgencyIdentity     => setAgencyIdentity(sad)
  }

  val commonEventReceiver: Receive = {
    case cu: ConfigUpdated =>
      addConfig(cu.name, AgentConfig(cu.value,
        getZonedDateTimeFromMillis(cu.lastUpdatedDateTimeInMillis)(UTCZoneId)))

    case cr: ConfigRemoved =>
      removeConfig(cr.name)
  }

  def getMsgIdsEligibleForRetries: Set[MsgId] = msgDeliveryState.map(_.getMsgsEligibleForRetry).getOrElse(Set.empty)

  def setAgencyDIDForActor(): Future[Any] = {
    val gcop = GetCachedObjectParam(Set(KeyDetail(AGENCY_DID_KEY, required = false)), KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER_ID)
    agentActorContext.generalCache.getByParamAsync(gcop).mapTo[CacheQueryResponse].map { cqr =>
      self ! SetAgencyIdentity(cqr.getAgencyDIDReq)
    }
  }

  override def postActorRecoveryCompleted(): List[Future[Any]] = {
    List(setAgencyDIDForActor()) ++
      ownerAgentKeyDID.map { oAgentDID =>
      List(setAgentActorDetail(oAgentDID))
    }.getOrElse(List.empty)
  }

  def setSponsorDetail(s: SponsorRel): Unit = {
    logger.debug(s"set sponsor details: $s")
    writeAndApply(SponsorAssigned(s.sponsorId, s.sponseeId))
  }

  def setAgentActorDetail(saw: SetAgentActorDetail): Unit = {
    logger.debug("'SetAgentActorDetail' received", (LOG_KEY_SRC_DID, saw.did), (LOG_KEY_PERSISTENCE_ID, persistenceId))
    setAndOpenWalletIfExists(saw.actorEntityId)
    sender ! AgentActorDetailSet(saw.did, saw.actorEntityId)
  }

  def setAgencyIdentity(saw: SetAgencyIdentity): Unit = {
    logger.debug("'SetAgencyIdentity' received", (LOG_KEY_SRC_DID, saw.did), (LOG_KEY_PERSISTENCE_ID, persistenceId))
    setAgencyDID(saw.did)
    sender ! AgencyIdentitySet(saw.did)
  }

  def postUpdateConfig(tupdateConf: TypedMsg[UpdateConfigReqMsg], senderVerKey: Option[VerKey]): Unit = {}

  def notifyUser(nu: NotifyUserViaPushNotif): Unit = {
    sendPushNotif(nu.pushNotifData, updateDeliveryStatus = false, None)
  }

  def validateConfigValues(cds: Set[ConfigDetail]): Unit = {
    cds.find(_.name == PUSH_COM_METHOD).foreach { pcmConfig =>
      PusherUtil.checkIfValidPushComMethod(
        ComMethodDetail(COM_METHOD_TYPE_PUSH, pcmConfig.value),
        appConfig
      )
    }
  }

  def handleUpdateConfigPackedReq(tupdateConf: TypedMsg[UpdateConfigReqMsg])(implicit reqMsgContext: ReqMsgContext): Unit = {
    runWithInternalSpan("handleUpdateConfigPackedReq", "UserAgentCommon") {
      addUserResourceUsage(reqMsgContext.clientIpAddressReq, RESOURCE_TYPE_MESSAGE,
        MSG_TYPE_UPDATE_CONFIGS, ownerDID)
      handleUpdateConfig(tupdateConf.msg)
      postUpdateConfig(tupdateConf, reqMsgContext.latestDecryptedMsgSenderVerKey)
      val configUpdatedRespMsg = UpdateConfigMsgHelper.buildRespMsg(reqMsgContext.agentMsgContext)
      val param = AgentMsgPackagingUtil.buildPackMsgParam(encParamFromThisAgentToOwner, configUpdatedRespMsg)
      val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackFormat, param)(agentMsgTransformer, wap)
      sender ! rp
    }
  }

  def handleUpdateConfig(updateConfigMsg: UpdateConfigCommand): Future[Option[ControlMsg]] = {
    validateConfigValues(updateConfigMsg.configs)
    updateConfigMsg.configs.foreach { cd =>
      if (cd.name != null && cd.value != null && !state.isConfigExists(cd.name, cd.value)) {
        writeAndApply(ConfigUpdated(cd.name, cd.value, getMillisForCurrentUTCZonedDateTime))
      }
    }
    Future.successful(None)
  }

  def encParamFromThisAgentToOwner: EncryptParam

  def handleRemoveConfigMsg(removeConf: RemoveConfigReqMsg)(implicit reqMsgContext: ReqMsgContext): Unit = {
    runWithInternalSpan("handleRemoveConfigMsg", "UserAgentCommon") {
      addUserResourceUsage(reqMsgContext.clientIpAddressReq, RESOURCE_TYPE_MESSAGE, MSG_TYPE_REMOVE_CONFIGS, ownerDID)
      removeConf.configs.foreach { cn =>
        if (state.isConfigExists(cn))
          writeAndApply(ConfigRemoved(cn))
      }
      val configRemovedRespMsg = RemoveConfigMsgHelper.buildRespMsg(reqMsgContext.agentMsgContext)
      val param = AgentMsgPackagingUtil.buildPackMsgParam(encParamFromThisAgentToOwner, configRemovedRespMsg)
      val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackFormat, param)(agentMsgTransformer, wap)
      sender ! rp
    }
  }

  def getFilteredConfigs(names: Set[String]): Set[ConfigDetail] = {
      state.filterConfigsByNames(names).map(c => ConfigDetail(c._1, c._2.value)).toSet
  }

  def handleGetConfigsMsg(getConfs: GetConfigsReqMsg)(implicit reqMsgContext: ReqMsgContext): Unit = {
    runWithInternalSpan("handleGetConfigsMsg", "UserAgentCommon") {
      val confs = getFilteredConfigs(getConfs.configs)
      val getConfRespMsg = GetConfigsMsgHelper.buildRespMsg(confs)(reqMsgContext.agentMsgContext)
      val param = AgentMsgPackagingUtil.buildPackMsgParam(encParamFromThisAgentToOwner, getConfRespMsg)
      val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackFormat, param)(agentMsgTransformer, wap)
      sender ! rp
    }
  }

  def sendAgentMsgToRegisteredEndpoint(srm: SendMsgToRegisteredEndpoint): Future[Option[ControlMsg]] = {
    sendMsgToRegisteredEndpoint(PayloadWrapper(srm.msg, srm.metadata), None)
    Future.successful(None) // [DEVIN] WHY?? Seems like we are ignoring the real future sendMsgToRegisteredEndpoint
  }

  override def storeOutgoingMsg(omp: OutgoingMsgParam, msgId:MsgId, msgName: MsgName,
                       senderDID: DID, threadOpt: Option[Thread]): Unit = {
    logger.debug("storing outgoing msg")
    runWithInternalSpan("storeOutgoingMessage", "UserAgentCommon") {
      val payloadParam = StorePayloadParam(omp.msgToBeProcessed, omp.metadata)
      storeMsg(msgId, msgName, MSG_STATUS_RECEIVED, senderDID,
        sendMsg = true, threadOpt, Option(payloadParam))
    }
    logger.debug("packed msg stored")
  }

  override def sendStoredMsgToEdge(msgId:MsgId): Future[Any] = {
    logger.debug("about to send stored msg: " + msgId)
    self ? SendStoredMsgToSelf(msgId)
  }

  override def sendUnstoredMsgToEdge(omp: OutgoingMsgParam): Future[Any] = {
    logger.debug("about to send msg to edge: " + omp.givenMsg)
    sendMsgToRegisteredEndpoint(PayloadWrapper(omp.msgToBeProcessed, omp.metadata), None)
  }

  def agentName(configs: Set[ConfigDetail]): String = {
    configs
      .find(_.name == NAME_KEY)
      .map(_.value)
      .getOrElse(DEFAULT_INVITE_SENDER_NAME)
  }

  def agentLogoUrl(configs: Set[ConfigDetail]): String = {
    configs
      .find(_.name == LOGO_URL_KEY)
      .map(_.value)
      .getOrElse(
        agentActorContext
          .appConfig
          .getConfigStringOption(PUSH_NOTIF_DEFAULT_LOGO_URL)
          .getOrElse(DEFAULT_INVITE_SENDER_LOGO_URL))
  }

  def handleCommonSignalMsgs: PartialFunction[SignalMsgFromDriver, Future[Option[ControlMsg]]] =
    handleCoreSignalMsgs orElse handleLegacySignalMsgs

  def handleCoreSignalMsgs: PartialFunction[SignalMsgFromDriver, Future[Option[ControlMsg]]] = {
    case SignalMsgFromDriver(uc: UpdateConfigs, _, _, _)    => handleUpdateConfig(uc)
    case SignalMsgFromDriver(gc: GetConfigs, _, _, _)       =>
      val cds = getFilteredConfigs(gc.names).map(cd         => Config(cd.name, cd.value))
      Future.successful(Option(ControlMsg(SendConfig(cds))))
  }

  def handleSpecificSignalMsgs: PartialFunction[SignalMsgFromDriver, Future[Option[ControlMsg]]] = PartialFunction.empty

  override final def handleSignalMsgs: PartialFunction[SignalMsgFromDriver, Future[Option[ControlMsg]]] =
    handleCommonSignalMsgs orElse handleSpecificSignalMsgs

  lazy val periodicCleanupScheduledJobInitialDelay: Int = appConfig.getConfigIntOption(
    USER_AGENT_PERIODIC_CLEANUP_SCHEDULED_JOB_INITIAL_DELAY_IN_SECONDS).getOrElse(60)

  lazy val periodicCleanupScheduledJobInterval: Int = appConfig.getConfigIntOption(
    USER_AGENT_PERIODIC_CLEANUP_SCHEDULED_JOB_INTERVAL_IN_SECONDS).getOrElse(900)

  //Disabling this job for now, as anyhow the configuration on which
  //'CheckPeriodicCleanupTasks' msg handling logic depends is not turned on yet.
//  scheduleJob(
//    "CheckPeriodicCleanupTasks",
//    periodicCleanupScheduledJobInitialDelay,
//    periodicCleanupScheduledJobInterval,
//    CheckPeriodicCleanupTasks)

  override def selfParticipantId: ParticipantId = ParticipantUtil.participantId(state.thisAgentKeyDIDReq, state.thisAgentKeyDID)

  override def userDIDForResourceUsageTracking(senderVerKey: Option[VerKey]): Option[DID] = Option(domainId)

  def getMsgs(gmr: GetMsgsReqMsg): List[MsgDetail] = {
    val msgAndDelivery = msgAndDeliveryReq
    val msgIds = gmr.uids.getOrElse(List.empty).map(_.trim).toSet
    val statusCodes = gmr.statusCodes.getOrElse(List.empty).map(_.trim).toSet
    val filteredMsgs = {
      if (msgIds.isEmpty && statusCodes.size == 1 && statusCodes.head == MSG_STATUS_RECEIVED.statusCode) {
        unseenMsgIds.map(mId => mId -> getMsgOpt(mId))
          .filter(_._2.nonEmpty)
          .map(r => r._1 -> r._2.get)
          .toMap
      } else {
        val uidFilteredMsgs = if (msgIds.nonEmpty) {
          msgIds.map(mId => mId -> getMsgOpt(mId))
            .filter(_._2.nonEmpty)
            .map(r => r._1 -> r._2.get)
            .toMap
        } else msgAndDelivery.msgs
        if (statusCodes.nonEmpty) uidFilteredMsgs.filter(m => statusCodes.contains(m._2.statusCode))
        else uidFilteredMsgs
      }
    }
    filteredMsgs.map { case (uid, msg) =>
      val payloadWrapper = if (gmr.excludePayload.contains(YES)) None else msgAndDelivery.msgPayloads.get(uid)
      val payload = payloadWrapper.map(_.msg)
      MsgDetail(uid, msg.`type`, msg.senderDID, msg.statusCode, msg.refMsgId, msg.thread, payload, Set.empty)
    }.toList
  }

  def msgAndDeliveryReq: MsgAndDelivery
}

case object CheckPeriodicCleanupTasks extends ActorMessageObject

case class MsgStored(msgCreatedEvent: MsgCreated, payloadStoredEvent: Option[MsgPayloadStored])

//state
case class AgentConfig(value: String, lastUpdatedDateTime: ZonedDateTime) {
  def toConfigValue: ConfigValue = ConfigValue(value, getMillisFromZonedDateTime(lastUpdatedDateTime))
}

//cmd
case class UpdateConfig(name: String, value: String) extends ActorMessageClass
case class RemoveConfig(name: String) extends ActorMessageClass
case class GetConfigs(names: Set[String]) extends ActorMessageClass

//response
case class AgentConfigs(configs: Set[ConfigDetail]) extends ActorMessageClass {

  def getConfValueOpt(name: String): Option[String] = {
    configs.find(_.name == name).map(_.value)
  }

  def getConfValueReq(name: String): String = {
    getConfValueOpt(name).getOrElse(
      throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("required config not yet set: " + name))
    )
  }
}

case object PairwiseConnSet extends ActorMessageObject

trait UserAgentCommonState {

  def configs: Map[String, ConfigValue]

  def agentConfigs: Map[String, AgentConfig] =
    configs.map(e => e._1 -> AgentConfig(e._2.value,
      TimeZoneUtil.getZonedDateTimeFromMillis(e._2.lastUpdatedTimeInMillis)(TimeZoneUtil.UTCZoneId)))

  def isConfigExists(name: String): Boolean = configs.contains(name)
  def isConfigExists(name: String, value: String): Boolean = configs.exists(c => c._1 == name && c._2.value == value)
  def filterConfigsByNames(names: Set[String]): Map[String, AgentConfig] = agentConfigs.filter(c => names.contains(c._1))
}

/**
 * this is common functionality between 'UserAgent' and 'UserAgentPairwise' actor
 * to update agent's state
 */
trait UserAgentCommonStateUpdateImpl extends AgentStateUpdateInterface {
  def addConfig(name: String, value: AgentConfig): Unit
  def removeConfig(name: String): Unit

  def updateMsgAndDelivery(msgAndDelivery: MsgAndDelivery): Unit
  def msgAndDelivery: Option[MsgAndDelivery]
  def msgAndDeliveryReq: MsgAndDelivery = msgAndDelivery.getOrElse(MsgAndDelivery())

  def addToMsgs(msgId: MsgId, msg: Msg): Unit = {
    val msgAndDelivery = msgAndDeliveryReq
    updateMsgAndDelivery(msgAndDelivery.copy(msgs = msgAndDelivery.msgs ++ Map(msgId -> msg)))
  }

  def addToMsgDetails(msgId: MsgId, attribs: Map[String, String]): Unit = {
    val msgAndDelivery = msgAndDeliveryReq
    val newMsgAttribs = msgAndDelivery.msgDetails.getOrElse(msgId, MsgAttribs()).attribs ++ attribs
    updateMsgAndDelivery(
      msgAndDelivery.copy(msgDetails = msgAndDelivery.msgDetails ++ Map(msgId -> MsgAttribs(newMsgAttribs)))
    )
  }

  def addToMsgPayloads(msgId: MsgId, payloadWrapper: PayloadWrapper): Unit = {
    val msgAndDelivery = msgAndDeliveryReq
    updateMsgAndDelivery(
      msgAndDelivery.copy(msgPayloads = msgAndDelivery.msgPayloads ++ Map(msgId -> payloadWrapper))
    )
  }

  def addToDeliveryStatus(msgId: MsgId, deliveryDetails: Map[String, MsgDeliveryDetail]): Unit = {
    val msgAndDelivery = msgAndDeliveryReq
    val newMsgDeliveryByDest =
      msgAndDelivery.msgDeliveryStatus.getOrElse(msgId, MsgDeliveryByDest()).msgDeliveryStatus ++ deliveryDetails
    updateMsgAndDelivery(
      msgAndDelivery.copy(msgDeliveryStatus =
        msgAndDelivery.msgDeliveryStatus ++ Map(msgId -> MsgDeliveryByDest(newMsgDeliveryByDest)))
    )
  }

  def removeFromMsgs(msgIds: Set[MsgId]): Unit = {
    val msgAndDelivery = msgAndDeliveryReq
    updateMsgAndDelivery(msgAndDelivery.copy(msgs = msgAndDelivery.msgs -- msgIds))
  }

  def removeFromMsgDetails(msgIds: Set[MsgId]): Unit = {
    val msgAndDelivery = msgAndDeliveryReq
    updateMsgAndDelivery(msgAndDelivery.copy(msgDetails = msgAndDelivery.msgDetails -- msgIds))
  }

  def removeFromMsgPayloads(msgIds: Set[MsgId]): Unit = {
    val msgAndDelivery = msgAndDeliveryReq
    updateMsgAndDelivery(msgAndDelivery.copy(msgPayloads = msgAndDelivery.msgPayloads -- msgIds))
  }

  def removeFromMsgDeliveryStatus(msgIds: Set[MsgId]): Unit = {
    val msgAndDelivery = msgAndDeliveryReq
    updateMsgAndDelivery(msgAndDelivery.copy(msgDeliveryStatus = msgAndDelivery.msgDeliveryStatus -- msgIds))
  }

  def getMsgOpt(msgId: MsgId): Option[Msg] = {
    msgAndDeliveryReq.msgs.get(msgId)
  }

  def getMsgDetails(msgId: MsgId): Map[String, String] = {
    msgAndDeliveryReq.msgDetails.getOrElse(msgId, MsgAttribs()).attribs
  }

  def getMsgPayload(msgId: MsgId): Option[PayloadWrapper] = msgAndDeliveryReq.msgPayloads.get(msgId)

  def getMsgDeliveryStatus(msgId: MsgId): Map[String, MsgDeliveryDetail] = {
    msgAndDeliveryReq.msgDeliveryStatus.getOrElse(msgId, MsgDeliveryByDest()).msgDeliveryStatus
  }
}
