package com.evernym.verity.actor.agent.user

import akka.event.LoggingReceive
import akka.pattern.ask
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.util2.Status.{DATA_NOT_FOUND, MSG_STATUS_RECEIVED}
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.SpanUtil._
import com.evernym.verity.actor.agent.msghandler.AgentMsgHandler
import com.evernym.verity.actor.agent.msghandler.incoming.{ControlMsg, SignalMsgParam}
import com.evernym.verity.actor.agent.msghandler.outgoing.{MsgNotifierForUserAgentCommon, NotifyMsgDetail, OutgoingMsgParam}
import com.evernym.verity.actor.agent.state.base.{AgentStateInterface, AgentStateUpdateInterface}
import com.evernym.verity.actor.agent.{AgencyIdentitySet, AgentActorDetailSet, ConfigValue, MsgAndDelivery, PayloadWrapper, SetAgencyIdentity, SetAgentActorDetail, SponsorRel, Thread}
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgfamily.configs._
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgPackagingUtil, AgentMsgWrapper}
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.protocol.container.actor.UpdateMsgDeliveryStatus
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols._
import com.evernym.verity.protocol.protocols.connecting.common.{NotifyUserViaPushNotif, SendMsgToRegisteredEndpoint}
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.Config
import com.evernym.verity.push_notification.PusherUtil
import com.evernym.verity.util.TimeZoneUtil._
import com.evernym.verity.util.{ParticipantUtil, ReqMsgContext, TimeZoneUtil}
import com.evernym.verity.vault._

import java.time.ZonedDateTime
import com.evernym.verity.actor.agent.user.msgstore.{FailedMsgTracker, MsgStateAPIProvider, MsgStore}
import com.evernym.verity.msgoutbox.rel_resolver.GetRelParam
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver.Commands.RelParamResp
import com.evernym.verity.actor.resourceusagethrottling.RESOURCE_TYPE_MESSAGE
import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageUtil
import com.evernym.verity.agentmsg.msgfamily.pairwise.{GetMsgsReqMsg, UpdateMsgStatusReqMsg}
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.Ctl.SendConfig

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

  def failedMsgTracker: Option[FailedMsgTracker] = None
  override lazy val msgStore: MsgStore = new MsgStore(appConfig, this, failedMsgTracker)
  /**
   * handler for common message types supported by 'user agent' and 'user agent pairwise' actor
   * @param reqMsgContext request message context
   * @return
   */
  def agentCommonMsgHandler(implicit reqMsgContext: ReqMsgContext): PartialFunction[Any, Any] = {
    case amw: AgentMsgWrapper if amw.isMatched(MFV_0_5, MSG_TYPE_UPDATE_CONFIGS) =>
      val msg = UpdateConfigMsgHelper.buildReqMsg(amw)
      handleUpdateConfigPackedReq(msg)

    case amw: AgentMsgWrapper if amw.isMatched(MFV_0_5, MSG_TYPE_REMOVE_CONFIGS) =>
      handleRemoveConfigMsg(RemoveConfigMsgHelper.buildReqMsg(amw))

    case amw: AgentMsgWrapper if amw.isMatched(MFV_0_5, MSG_TYPE_GET_CONFIGS) =>
      handleGetConfigsMsg(GetConfigsMsgHelper.buildReqMsg(amw))

    case amw: AgentMsgWrapper
      if amw.isMatched(MFV_0_5, MSG_TYPE_GET_MSGS) ||
        amw.isMatched(MFV_0_6, MSG_TYPE_GET_MSGS) => handleGetMsgs(amw)

    case amw: AgentMsgWrapper
      if amw.isMatched(MFV_0_5, MSG_TYPE_UPDATE_MSG_STATUS) ||
        amw.isMatched(MFV_0_6, MSG_TYPE_UPDATE_MSG_STATUS) =>
      handleUpdateMsgStatus(amw)
  }

  /**
   * this handles internal commands
   */
  val commonCmdReceiver: Receive = LoggingReceive.withLabel("commonCmdReceiver") {
    case umds: UpdateMsgDeliveryStatus  => updateMsgDeliveryStatus(umds)
    case gc: GetConfigs                 => sender ! AgentConfigs(getFilteredConfigs(gc.names))
    case sad: SetAgencyIdentity         => setAgencyIdentity(sad)
    case _: AgencyIdentitySet           => //nothing to od

    //internal messages exchanged between actors
    case gmr: GetMsgsReqMsg               => handleGetMsgsInternal(gmr)
    case ums: UpdateMsgStatusReqMsg       => handleUpdateMsgStatusInternal(ums)
    //case GetRelParam                      => sender ! RelParamResp(selfRelDID, state.relationship)
  }

  override val receiveAgentSpecificInitCmd: Receive = LoggingReceive.withLabel("receiveActorInitSpecificCmd") {
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

  def getMsgIdsEligibleForRetries: Set[MsgId] = failedMsgTracker.map(_.getMsgsEligibleForRetry).getOrElse(Set.empty)

  def setAgencyDIDForActor(): Future[Any] = {
    agencyDidPairFutByCache().flatMap { adp =>
      self ? SetAgencyIdentity(adp)
    }
  }

  override def preAgentStateFix(): Future[Any] = {
    msgStore.updateMsgStateMetrics()
    val fut1 = Seq(setAgencyDIDForActor())
    val fut2 = ownerAgentKeyDIDPair.map { oadp =>
      setAgentActorDetail(oadp)
    }
    val listOfFut = fut1 ++ fut2
    Future.sequence(listOfFut)
  }

  def setSponsorDetail(s: SponsorRel): Unit = {
    logger.debug(s"set sponsor details: $s")
    writeAndApply(SponsorAssigned(s.sponsorId, s.sponseeId))
  }

  def setAgentActorDetail(saw: SetAgentActorDetail): Unit = {
    logger.debug("'SetAgentActorDetail' received",
      (LOG_KEY_SRC_DID, saw.didPair.DID), (LOG_KEY_PERSISTENCE_ID, persistenceId))
    updateAgentWalletId(saw.actorEntityId)
    sender ! AgentActorDetailSet(saw.didPair, saw.actorEntityId)
  }

  def setAgencyIdentity(saw: SetAgencyIdentity): Unit = {
    logger.debug("'SetAgencyIdentity' received",
      (LOG_KEY_SRC_DID, saw.didPair.DID), (LOG_KEY_PERSISTENCE_ID, persistenceId))
    setAgencyDIDPair(saw.didPair)
    sender ! AgencyIdentitySet(saw.didPair)
  }

  def postUpdateConfig(updateConf: UpdateConfigReqMsg, senderVerKey: Option[VerKey]): Unit = {}

  def notifyUser(nu: NotifyUserViaPushNotif): Unit = sendPushNotif(nu.pushNotifData, None)

  def validateConfigValues(cds: Set[ConfigDetail]): Unit = {
    cds.find(_.name == PUSH_COM_METHOD).foreach { pcmConfig =>
      PusherUtil.checkIfValidPushComMethod(
        ComMethodDetail(COM_METHOD_TYPE_PUSH, pcmConfig.value, hasAuthEnabled = false),
        appConfig
      )
    }
  }

  def handleUpdateConfigPackedReq(updateConf: UpdateConfigReqMsg)(implicit reqMsgContext: ReqMsgContext): Unit = {
    runWithInternalSpan("handleUpdateConfigPackedReq", "UserAgentCommon") {
      val userId = userIdForResourceUsageTracking(reqMsgContext.latestMsgSenderVerKey)
      val resourceName = reqMsgContext.msgFamilyDetail.map(ResourceUsageUtil.getMessageResourceName)
        .getOrElse(MSG_TYPE_UPDATE_CONFIGS)
      addUserResourceUsage(RESOURCE_TYPE_MESSAGE, resourceName, reqMsgContext.clientIpAddressReq, userId)
      handleUpdateConfig(updateConf)
      postUpdateConfig(updateConf, reqMsgContext.latestMsgSenderVerKey)
      val configUpdatedRespMsg = UpdateConfigMsgHelper.buildRespMsg(reqMsgContext.agentMsgContext)
      val param = AgentMsgPackagingUtil.buildPackMsgParam(encParamFromThisAgentToOwner, configUpdatedRespMsg, reqMsgContext.wrapInBundledMsg)
      val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackFormatReq, param)(agentMsgTransformer, wap)
      sendRespMsg("ConfigUpdated", rp, sender)
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
      val userId = userIdForResourceUsageTracking(reqMsgContext.latestMsgSenderVerKey)
      val resourceName = ResourceUsageUtil.getMessageResourceName(removeConf.msgFamilyDetail)
      addUserResourceUsage(RESOURCE_TYPE_MESSAGE, resourceName, reqMsgContext.clientIpAddressReq, userId)
      removeConf.configs.foreach { cn =>
        if (state.isConfigExists(cn))
          writeAndApply(ConfigRemoved(cn))
      }
      val configRemovedRespMsg = RemoveConfigMsgHelper.buildRespMsg(reqMsgContext.agentMsgContext)
      val param = AgentMsgPackagingUtil.buildPackMsgParam(encParamFromThisAgentToOwner, configRemovedRespMsg, reqMsgContext.wrapInBundledMsg)
      val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackFormatReq, param)(agentMsgTransformer, wap)
      sendRespMsg("ConfigRemoved", rp, sender)
    }
  }

  def getFilteredConfigs(names: Set[String]): Set[ConfigDetail] = {
      state.filterConfigsByNames(names).map(c => ConfigDetail(c._1, c._2.value)).toSet
  }

  def handleGetConfigsMsg(getConfs: GetConfigsReqMsg)(implicit reqMsgContext: ReqMsgContext): Unit = {
    runWithInternalSpan("handleGetConfigsMsg", "UserAgentCommon") {
      val confs = getFilteredConfigs(getConfs.configs)
      val getConfRespMsg = GetConfigsMsgHelper.buildRespMsg(confs)(reqMsgContext.agentMsgContext)
      val param = AgentMsgPackagingUtil.buildPackMsgParam(encParamFromThisAgentToOwner, getConfRespMsg, reqMsgContext.wrapInBundledMsg)
      val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackFormatReq, param)(agentMsgTransformer, wap)
      sendRespMsg("Configs", rp, sender)
    }
  }

  def sendAgentMsgToRegisteredEndpoint(srm: SendMsgToRegisteredEndpoint): Future[Option[ControlMsg]] = {
    sendMsgToRegisteredEndpoint(NotifyMsgDetail(srm.msgId, "unknown", Option(PayloadWrapper(srm.msg, srm.metadata))), None)
      .map(_ => None)
  }

  override def storeOutgoingMsg(omp: OutgoingMsgParam, msgId:MsgId, msgName: MsgName,
                                senderDID: DID, threadOpt: Option[Thread]): Unit = {
    logger.debug("storing outgoing msg")
    val payloadParam = StorePayloadParam(omp.msgToBeProcessed, omp.metadata)
    storeMsg(msgId, msgName, senderDID, MSG_STATUS_RECEIVED.statusCode,
      sendMsg = true, threadOpt, None, Option(payloadParam))
    logger.debug("packed msg stored")
  }

  override def sendMsgToMyDomain(omp: OutgoingMsgParam, msgId: MsgId, msgName: String): Unit = {
    logger.debug("about to send un stored msg to my domain (edge): " + omp.givenMsg)
    notifyUserForNewMsg(
      NotifyMsgDetail(
        msgId,
        msgName,
        Option(PayloadWrapper(omp.msgToBeProcessed, omp.metadata)))
    )
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
          .getStringOption(PUSH_NOTIF_DEFAULT_LOGO_URL)
          .getOrElse(DEFAULT_INVITE_SENDER_LOGO_URL))
  }

  def handleCommonSignalMsgs: PartialFunction[SignalMsgParam, Future[Option[ControlMsg]]] =
    handleCoreSignalMsgs orElse handleLegacySignalMsgs

  def handleCoreSignalMsgs: PartialFunction[SignalMsgParam, Future[Option[ControlMsg]]] = {
    case SignalMsgParam(uc: UpdateConfigs, _)    => handleUpdateConfig(uc)
    case SignalMsgParam(gc: GetConfigs, _)       =>
      val cds = getFilteredConfigs(gc.names).map(cd => Config(cd.name, cd.value))
      Future.successful(Option(ControlMsg(SendConfig(cds))))
  }

  def handleSpecificSignalMsgs: PartialFunction[SignalMsgParam, Future[Option[ControlMsg]]] = PartialFunction.empty

  override final def handleSignalMsgs: PartialFunction[SignalMsgParam, Future[Option[ControlMsg]]] =
    handleCommonSignalMsgs orElse handleSpecificSignalMsgs

  override def selfParticipantId: ParticipantId = ParticipantUtil.participantId(state.thisAgentKeyDIDReq, state.thisAgentKeyDID)



  def msgAndDeliveryReq: MsgAndDelivery
}

case class MsgStoredEvents(msgCreatedEvent: MsgCreated, payloadStoredEvent: Option[MsgPayloadStored]) {
  def allEvents: List[Any] = List(msgCreatedEvent) ++ payloadStoredEvent
}

//state
case class AgentConfig(value: String, lastUpdatedDateTime: ZonedDateTime) {
  def toConfigValue: ConfigValue = ConfigValue(value, getMillisFromZonedDateTime(lastUpdatedDateTime))
}

//cmd
case class UpdateConfig(name: String, value: String) extends ActorMessage
case class RemoveConfig(name: String) extends ActorMessage
case class GetConfigs(names: Set[String]) extends ActorMessage

//response
case class AgentConfigs(configs: Set[ConfigDetail]) extends ActorMessage {

  def getConfValueOpt(name: String): Option[String] = {
    configs.find(_.name == name).map(_.value)
  }

  def getConfValueReq(name: String): String = {
    getConfValueOpt(name).getOrElse(
      throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("required config not yet set: " + name))
    )
  }
}

case object PairwiseConnSet extends ActorMessage

trait UserAgentCommonState { this: State =>

  def configs: Map[String, ConfigValue]

  def agentConfigs: Map[String, AgentConfig] =
    configs.map(e => e._1 -> AgentConfig(e._2.value,
      TimeZoneUtil.getZonedDateTimeFromMillis(e._2.lastUpdatedTimeInMillis)(TimeZoneUtil.UTCZoneId)))

  def isConfigExists(name: String): Boolean = configs.contains(name)
  def isConfigExists(name: String, value: String): Boolean = configs.exists(c => c._1 == name && c._2.value == value)
  def filterConfigsByNames(names: Set[String]): Map[String, AgentConfig] = agentConfigs.filter(c => names.contains(c._1))

  def msgAndDelivery: Option[MsgAndDelivery]

  override def summary(): Option[String] = msgAndDelivery.map { mad =>
    mad.msgs.values.groupBy(_.getType).map(r => s"${r._1}:${r._2.size}").mkString(", ")
  }
}

/**
 * this is common functionality between 'UserAgent' and 'UserAgentPairwise' actor
 * to update agent's state
 */
trait UserAgentCommonStateUpdateImpl extends AgentStateUpdateInterface with MsgStateAPIProvider {
  def addConfig(name: String, value: AgentConfig): Unit
  def removeConfig(name: String): Unit

  def updateMsgAndDelivery(msgAndDelivery: MsgAndDelivery): Unit
  def msgAndDelivery: Option[MsgAndDelivery]

}
