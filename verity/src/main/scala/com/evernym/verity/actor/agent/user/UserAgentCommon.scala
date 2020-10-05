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
import com.evernym.verity.actor.agent.state.{AgentStateBase, Configs}
import com.evernym.verity.actor.agent.{AgencyIdentitySet, AgentActorDetailSet, SetAgencyIdentity, SetAgentActorDetail, UpdateRoute}
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgfamily.configs._
import com.evernym.verity.agentmsg.msgfamily.pairwise.MsgThread
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
import com.evernym.verity.util.{ParticipantUtil, ReqMsgContext}
import com.evernym.verity.vault._

import scala.concurrent.Future
import scala.util.Left

/**
 * common logic between 'UserAgent' and 'UserAgentPairwise' actor
 */
trait UserAgentCommon
  extends AgentPersistentActor
    with AgentMsgHandler
    with ShardRegionFromActorContext
    with MsgStoreAPI
    with LEGACY_connectingSignalHandler {

  this: AgentPersistentActor with MsgNotifierForUserAgentCommon =>

  type StateType <: AgentStateBase with MsgAndDeliveryState with Configs

  def msgState: MsgState = state.msgState

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
    case UpdateRoute                    => updateRoute()    //TODO: this is only till the legacy routes gets updated

    case sad: SetAgencyIdentity         => setAgencyIdentity(sad)
    case _: AgencyIdentitySet           => //nothing to od
  }

  def maxTimeToRetainSeenMsgsInMinutes: Option[Int] =
    appConfig.getConfigIntOption(AGENT_MAX_TIME_TO_RETAIN_SEEN_MSG)

  def checkPeriodicCleanupTasks(): Unit = {
    maxTimeToRetainSeenMsgsInMinutes.foreach { minutes =>
      state.msgState.performStateCleanup(minutes)
    }
  }

  override val receiveActorInitSpecificCmd: Receive = LoggingReceive.withLabel("receiveActorInitSpecificCmd") {
    case saw: SetAgentActorDetail   => setAgentActorDetail(saw)
    case sad: SetAgencyIdentity     => setAgencyIdentity(sad)
  }

  val commonEventReceiver: Receive = {
    case cu: ConfigUpdated =>
      state.addConfig(cu.name, AgentConfig(cu.value,
        getZonedDateTimeFromMillis(cu.lastUpdatedDateTimeInMillis)(UTCZoneId)))

    case cr: ConfigRemoved =>
      state.removeConfig(cr.name)
  }

  def getMsgIdsEligibleForRetries: Set[MsgId] = msgState.msgDeliveryState.map(_.getMsgsEligibleForRetry).getOrElse(Set.empty)

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

  def setAgentActorDetail(saw: SetAgentActorDetail): Unit = {
    logger.debug("'SetAgentActorDetail' received", (LOG_KEY_SRC_DID, saw.did), (LOG_KEY_PERSISTENCE_ID, persistenceId))
    setAndOpenWalletIfExists(saw.actorEntityId)
    sender ! AgentActorDetailSet(saw.did, saw.actorEntityId)
  }

  def setAgencyIdentity(saw: SetAgencyIdentity): Unit = {
    logger.debug("'SetAgencyIdentity' received", (LOG_KEY_SRC_DID, saw.did), (LOG_KEY_PERSISTENCE_ID, persistenceId))
    state.setAgencyDID(saw.did)
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
      val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackVersion, param)(agentMsgTransformer, wap)
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
      val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackVersion, param)(agentMsgTransformer, wap)
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
      val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackVersion, param)(agentMsgTransformer, wap)
      sender ! rp
    }
  }

  def sendAgentMsgToRegisteredEndpoint(srm: SendMsgToRegisteredEndpoint): Future[Option[ControlMsg]] = {
    sendMsgToRegisteredEndpoint(PayloadWrapper(srm.msg, srm.metadata), None)
    Future.successful(None)
  }

  override def storeOutgoingMsg(omp: OutgoingMsgParam, msgId:MsgId, msgName: MsgName,
                       senderDID: DID, threadOpt: Option[MsgThread]): Unit = {
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
    //TODO: activity should be handled on outgoing message to other cloud agent (Not Edge)
    trackAgentActivity("unknown", domainId, sponsorId, state.theirDid)
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

  scheduleJob(
    "CheckPeriodicCleanupTasks",
    periodicCleanupScheduledJobInitialDelay,
    periodicCleanupScheduledJobInterval,
    CheckPeriodicCleanupTasks)

  override def selfParticipantId: ParticipantId = ParticipantUtil.participantId(state.thisAgentKeyDIDReq, state.thisAgentKeyDID)

  override def userDIDForResourceUsageTracking(senderVerKey: Option[VerKey]): Option[DID] = Option(domainId)
}

case object CheckPeriodicCleanupTasks extends ActorMessageObject

case class MsgStored(msgCreatedEvent: MsgCreated, payloadStoredEvent: Option[MsgPayloadStored])

//state
case class AgentConfig(value: String, lastUpdatedDateTime: ZonedDateTime)

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
