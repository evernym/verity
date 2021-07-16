package com.evernym.verity.actor.agent.msghandler.outgoing

import akka.actor.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern._
import akka.pattern.ask
import com.evernym.verity.util2.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.util2.Status._
import com.evernym.verity.actor.agent._
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.actor.agent.MsgPackFormat._
import com.evernym.verity.actor.agent.msghandler.outgoing.LegacyMsgSender.Commands.{SendBinaryMsg, SendJsonMsg}
import com.evernym.verity.actor.agent.msghandler.outgoing.LegacyMsgSender.Replies.SendMsgResp
import com.evernym.verity.actor.agent.user._
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.msg_tracer.progress_tracker.{HasMsgProgressTracker, MsgEvent}
import com.evernym.verity.actor.persistence.{AgentPersistentActor, HasActorResponseTimeout}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily.pairwise.MsgExtractor
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.config.ConfigUtil
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.metrics.CustomMetrics._
import com.evernym.verity.metrics.InternalSpan
import com.evernym.verity.protocol.engine.MsgFamily.{VALID_MESSAGE_TYPE_REG_EX_DID, VALID_MESSAGE_TYPE_REG_EX_HTTP}
import com.evernym.verity.protocol.container.actor.UpdateMsgDeliveryStatus
import com.evernym.verity.protocol.engine._
import com.evernym.verity.push_notification._
import com.evernym.verity.transports.MsgSendingSvc
import com.evernym.verity.util.MsgIdProvider
import com.evernym.verity.util.StrUtil.camelToKebab
import com.evernym.verity.vault.KeyParam
import com.evernym.verity.util2.UrlParam
import com.evernym.verity.util2.Exceptions
import com.evernym.verity.util2.Exceptions.HandledErrorException

import scala.concurrent.Future


trait MsgNotifier {
  this: AgentPersistentActor with HasMsgProgressTracker
    with HasLogger =>

  /**
   * this actor will be created for each actor (UserAgent or UserAgentPairwise) of the logical agent
   */
  private lazy val pusher: ActorRef = {
    context.actorOf(Pusher.props(appConfig), s"pusher-$persistenceId")
  }

  def sendPushNotif(pcms: Set[ComMethodDetail], pnData: PushNotifData, sponsorId: Option[String]): Future[Any] = {
    metricsWriter.runWithSpan("sendPushNotif", "MsgNotifier", InternalSpan) {
      logger.debug("push com methods: " + pcms)
      val spn = SendPushNotif(pcms, pnData.sendAsAlertPushNotif, pnData.notifData, pnData.extraData, sponsorId)
      logger.debug("pn data: " + spn)
      pusher ? spn
    }
  }
}

trait MsgNotifierForStoredMsgs
  extends MsgNotifier
    with PushNotifMsgBuilder
    with HasActorResponseTimeout {

  this: AgentPersistentActor with MsgAndDeliveryHandler with HasMsgProgressTracker with HasLogger =>

  def agentMsgRouter: AgentMsgRouter
  def msgSendingSvc: MsgSendingSvc
  def defaultSelfRecipKeys: Set[KeyParam]

  def selfRelDID : DID

  /**
   * agent key DID belonging to the agent created for the domain DID (self Rel DID)
   * @return
   */
  def ownerAgentKeyDIDReq: DID

  // used for packing the message.
  def msgExtractor: MsgExtractor

  type PushDetails=Map[AttrName, AttrValue]

  sealed trait MsgNotifierMessages
  case class NoPushMethodWarning(agentDID: DID) extends MsgNotifierMessages

  private val generalNewMsgBodyTemplateOpt: Option[String] =
    appConfig.getStringOption(PUSH_NOTIF_GENERAL_NEW_MSG_BODY_TEMPLATE)

  private def getAllComMethods: Future[CommunicationMethods] =
    agentMsgRouter.execute(InternalMsgRouteParam(ownerAgentKeyDIDReq, GetAllComMethods)).mapTo[CommunicationMethods]

  private def withComMethods(providedComMethod: Option[CommunicationMethods]): Future[CommunicationMethods] = {
    providedComMethod match {
      case Some(cm) => Future.successful(cm)
      case _        => getAllComMethods
    }
  }

  def notifyUserForNewMsg(notifMsgDtl: NotifyMsgDetail): Future[Any] = {
    //NOTE: as of now, assumption is that there would be only
    // one com method registered (either http endpoint or push notification)
    logger.debug("about to notify user for newly received message: " + notifMsgDtl.uid + s"(${notifMsgDtl.msgType})")
    getAllComMethods.map { allComMethods =>
      if (allComMethods.comMethods.nonEmpty) {
        val fut1 = sendMsgToRegisteredEndpoint(notifMsgDtl, Option(allComMethods))
        val fut2 = sendMsgToRegisteredPushNotif(notifMsgDtl, Option(allComMethods))
        val fut3 = fwdMsgToSponsor(notifMsgDtl, Option(allComMethods))
        val allFut = Seq(fut1, fut2,fut3)
        Future.sequence(allFut)
      } else {
        recordOutMsgDeliveryEvent(notifMsgDtl.uid,
          MsgEvent.withIdAndDetail(notifMsgDtl.uid, s"SUCCESSFUL [no registered com method found]"))
        Future.successful("no registered com methods found")
      }
    }
  }

  def notifyUserForFailedMsgDelivery(notifMsgDtl: NotifyMsgDetail): Unit = {
    try {
      notifyForErrorResponseFromRemoteAgent(notifMsgDtl)
    } catch {
      case e: Exception =>
        logger.warn("error response received from remote agent", (LOG_KEY_UID, notifMsgDtl.uid),
          (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
    }
  }

  def notifyUserForSuccessfulMsgDelivery(notifMsgDtl: NotifyMsgDetail): Unit = {
    try {
      notifyForSuccessResponseFromRemoteAgent(notifMsgDtl)
    } catch {
      case e: Exception =>
        logger.warn(s"error response received from remote agent",
          (LOG_KEY_UID, notifMsgDtl.uid),
          (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
    }
  }

  private def getPushNotifTextTemplate(msgType: String): String = {

    val formattedMsgType = {
      msgType match {
        case VALID_MESSAGE_TYPE_REG_EX_DID(_, _, _, _, _)  => "general"
        case VALID_MESSAGE_TYPE_REG_EX_HTTP(_, _, _, _, _) => "general"
        case mt if mt.toUpperCase == mt                => "general"
        case _ => camelToKebab(msgType)
      }
    }
    val msgTypeBasedTemplateConfigName = s"$formattedMsgType-new-msg-body-template"
    val msgTypeBasedTemplate = appConfig.getStringOption(msgTypeBasedTemplateConfigName)

    msgTypeBasedTemplate match {
      case Some(t: String) => t
      case _ =>
        msgType match {
          case CREATE_MSG_TYPE_TOKEN_XFER_OFFER => "#{senderName} wants to send you Sovrin tokens"
          case CREATE_MSG_TYPE_TOKEN_XFER_REQ => "#{senderName} is requesting Sovrin tokens"
          case CREATE_MSG_TYPE_TOKEN_XFERRED => "#{senderName} sent you Sovrin tokens"
          case _ => generalNewMsgBodyTemplateOpt.getOrElse("#{senderName} sent you #{msgType}")
        }
    }

  }

  protected def sendMsgToRegisteredEndpoint(notifDetail: NotifyMsgDetail,
                                            allComMethods: Option[CommunicationMethods]): Future[Any] = {
    notifDetail.payloadWrapper.flatMap(_.metadata).map(_.msgPackFormat) match {
      case None | Some(MPF_INDY_PACK|MPF_MSG_PACK) =>
        sendMsgToRegisteredEndpointLegacy(notifDetail, allComMethods)
      case Some(MPF_PLAIN)  =>
        sendMsgToRegisteredEndpointNew(notifDetail, allComMethods)
      case Some(Unrecognized(_)) =>
        throw new RuntimeException("unsupported msgPackFormat: Unrecognized can't be used here")
    }
  }

  protected def sendMsgToRegisteredEndpointLegacy(notifDetail: NotifyMsgDetail,
                                                  allComMethods: Option[CommunicationMethods]): Future[Any] = {
    notifDetail.payloadWrapper.map { pw =>
      withComMethods(allComMethods).map { comMethods =>
        val httpComMethods = comMethods.filterByType(COM_METHOD_TYPE_HTTP_ENDPOINT)
        logger.debug("received registered http endpoints: " + httpComMethods)
        httpComMethods.foreach { hcm =>
          logger.debug(s"about to send message to endpoint: " + hcm)
          val fut = pw.metadata.map(_.msgPackFormat) match {
            case None | Some(MPF_INDY_PACK | MPF_MSG_PACK) =>
              sendBinaryMsg(pw.msg, hcm.value, withAuthHeader = hcm.hasAuthEnabled)
            case Some(MPF_PLAIN) =>
              sendJsonMsg(new String(pw.msg), hcm.value, withAuthHeader = hcm.hasAuthEnabled)
            case Some(Unrecognized(_)) =>
              throw new RuntimeException("unsupported msgPackFormat: Unrecognized can't be used here")
          }
          recordDeliveryState(notifDetail.uid, notifDetail.msgType, s"registered endpoint legacy: ${hcm.value}", fut)
          logger.debug("message sent to endpoint (legacy): " + hcm)
        }
      }
    }.getOrElse(Future.successful(Done))
  }

  protected def sendMsgToRegisteredEndpointNew(notifDetail: NotifyMsgDetail,
                                               allComMethods: Option[CommunicationMethods]): Future[Any] = {
    notifDetail.payloadWrapper.map { pw =>
      withComMethods(allComMethods).map { comMethods =>
        val httpComMethods = comMethods.filterByType(COM_METHOD_TYPE_HTTP_ENDPOINT)
        logger.debug("received registered http endpoints: " + httpComMethods)
        httpComMethods.foreach { hcm =>
          logger.debug(s"about to send message to endpoint: " + hcm)
          val pkgType = hcm.packaging.map(_.pkgType).getOrElse(MPF_INDY_PACK)
          val fut = pkgType match {
            case MPF_PLAIN =>
              sendJsonMsg(new String(pw.msg), hcm.value, hcm.hasAuthEnabled)
            case MPF_INDY_PACK | MPF_MSG_PACK =>
              val endpointRecipKeys = hcm.packaging.map(_.recipientKeys.map(verKey => KeyParam(Left(verKey))))
              // if endpoint recipKeys are not configured or empty, use default (legacy compatibility).
              val recipKeys = endpointRecipKeys match {
                case Some(keys) if keys.nonEmpty => keys
                case _ => defaultSelfRecipKeys
              }
              msgExtractor.packAsync(pkgType, new String(pw.msg), recipKeys).flatMap { packedMsg =>
                sendBinaryMsg(packedMsg.msg, hcm.value, hcm.hasAuthEnabled)
              }
            case Unrecognized(_) => throw new RuntimeException("unsupported msgPackFormat: Unrecognized can't be used here")
          }
          recordDeliveryState(notifDetail.uid, notifDetail.msgType, s"registered endpoint new: ${hcm.value}", fut)
          logger.debug("message sent to endpoint: " + hcm)
        }
      }
    }.getOrElse(Future.successful(Done))
  }

  private def sendMsgToRegisteredPushNotif(notifMsgDtl: NotifyMsgDetail, allComMethods: Option[CommunicationMethods]): Future[Any] = {
    try {
      msgStore.getMsgOpt(notifMsgDtl.uid).map { msg =>
        val mds = msgStore.getMsgDetails(notifMsgDtl.uid)
        val title = mds.get(TITLE).map(v => Map(TITLE -> v)).getOrElse(Map.empty)
        val detail = mds.get(DETAIL).map(v => Map(DETAIL -> v)).getOrElse(Map.empty)
        val name = mds.get(NAME_KEY).map(v => Map(NAME_KEY -> v)).getOrElse(Map.empty)
        val logoUrl = mds.get(LOGO_URL_KEY).map(v => Map(LOGO_URL_KEY -> v)).getOrElse(Map.empty)
        val extraData = title ++ detail ++ name ++ logoUrl

        val msgBodyTemplateToUse = getPushNotifTextTemplate(msg.getType)
        val newExtraData = extraData ++ Map(PUSH_NOTIF_BODY_TEMPLATE -> msgBodyTemplateToUse)

        logger.debug("new messages notification: " + notifMsgDtl)
        getCommonPushNotifData(notifMsgDtl, newExtraData) match {
          case Some(pnd) => sendPushNotif(pnd, allComMethods)
          case None => Future.successful("push notification not enabled")
        }
      }.getOrElse(Future.successful(Done))
    } catch {
      case e: Exception =>
        logger.error("could not send push notification", (LOG_KEY_UID, notifMsgDtl.uid),
          (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
        Future.failed(e)
    }
  }

  private def notifyForErrorResponseFromRemoteAgent(notifMsgDtl: NotifyMsgDetail): Unit = {
    logger.debug("error response received from remote agent: " + notifMsgDtl, (LOG_KEY_ERR_MSG, notifMsgDtl))
    buildPushNotifDataForFailedMsgDelivery(notifMsgDtl).foreach { pnd =>
      sendPushNotif(pnd, None)
    }
  }

  private def notifyForSuccessResponseFromRemoteAgent(notifMsgDtl: NotifyMsgDetail): Unit = {
    logger.debug("successful response received from remote agent: " + notifMsgDtl)
    buildPushNotifDataForSuccessfulMsgDelivery(notifMsgDtl).foreach { pnd =>
      sendPushNotif(pnd, None)
    }
  }

  private def handleErrorIfFailed(pnds: Any): Unit = {
    pnds match {
      case pnr: PushNotifResponse =>
        if (pnr.statusCode == MSG_DELIVERY_STATUS_FAILED.statusCode) {
          logger.error(s"push notification failed (userDID: $selfRelDID): $pnr")
          val invalidTokenErrorCodes =
            agentActorContext.appConfig.getStringSetOption(PUSH_NOTIF_INVALID_TOKEN_ERROR_CODES).
              getOrElse(errorsForWhichComMethodShouldBeDeleted)
          if (pnr.statusDetail.exists(invalidTokenErrorCodes.contains)) {
            agentActorContext.agentMsgRouter.execute(InternalMsgRouteParam(ownerAgentKeyDIDReq,
              DeleteComMethod(pnr.comMethodValue, pnr.statusDetail.getOrElse("n/a"))))
          }
        }
      case x =>
        logger.error(s"push notification failed (userDID: $selfRelDID): $x")
    }
  }

  def getSponsorEndpoint(id: Option[String]): Option[String] = {
    id match {
      case Some(i) =>
        ConfigUtil.findSponsorConfigWithId(i, appConfig) match {
          case Some(sponsorDetails) =>
            logger.info(s"sponsor found: $sponsorDetails")
            if (sponsorDetails.active) Some(sponsorDetails.endpoint)
            else {
              logger.error(s"sponsor inactive - cannot forward message $id")
              None
            }
          case None =>
            logger.error(s"not able to find sponsor details in configuration for id: $id")
            None
        }
      case None =>
        logger.error(s"Sponsor not registered - cannot forward message")
        None
    }
  }

  protected def fwdMsgToSponsor(notifMsgDtl: NotifyMsgDetail, allComMethods: Option[CommunicationMethods]): Future[Any] = {
    logger.debug("about to get com methods to forward notification")
    withComMethods(allComMethods).map { comMethods =>
      val cms = comMethods.filterByType(COM_METHOD_TYPE_FWD_PUSH)
      logger.debug(s"fwdComMethods: $cms")
      cms.map(_.value).foreach { sponseeDetails =>
        getSponsorEndpoint(comMethods.sponsorId).foreach( url => {
          logger.debug(s"received sponsor's registered http endpoint: $url and sponsee's communication details $cms")

          val mds = msgStore.getMsgDetails(notifMsgDtl.uid)
          val name = mds.getOrElse(NAME_KEY, "")
          // metadata is deprecated, we should keep type in legacy state for backward compatibility.
          val fwdMeta = FwdMetaData(Some(notifMsgDtl.deprecatedPushMsgType), Some(name))
          val fwdMsg = FwdMsg(notifMsgDtl.uid, notifMsgDtl.msgType, sponseeDetails, msgRecipientDID, fwdMeta)

          val fut = msgSendingSvc.sendJsonMsg(new String(DefaultMsgCodec.toJson(fwdMsg)))(UrlParam(url))
          recordDeliveryState(notifMsgDtl.uid, notifMsgDtl.msgType, s"forward message to sponsor: $url", fut)
          logger.debug("message sent to endpoint: " + url)
        })
      }
    }
  }

  def sendPushNotif(pnData: PushNotifData, allComMethods: Option[CommunicationMethods]): Future[Any] = {
    metricsWriter.runWithSpan("sendPushNotif", "MsgNotifierForStoredMsgs", InternalSpan) {
      logger.debug("about to get push com methods to send push notification")
      withComMethods(allComMethods).map { comMethods =>
        val cms = comMethods.filterByTypes(Seq(COM_METHOD_TYPE_PUSH, COM_METHOD_TYPE_SPR_PUSH))
        if (cms.nonEmpty) {
          self ! UpdateMsgDeliveryStatus(pnData.uid, selfRelDID, MSG_DELIVERY_STATUS_PENDING.statusCode, None)
          logger.debug("received push com methods: " + cms)
          val pushStart = System.currentTimeMillis()
          val fut = sendPushNotif(cms, pnData, comMethods.sponsorId).map { r =>
            val duration = System.currentTimeMillis() - pushStart
            metricsWriter.gaugeIncrement(AS_SERVICE_FIREBASE_DURATION, duration)
            handleErrorIfFailed(r)
            r match {
              case pnds: PushNotifResponse =>
                val umds = UpdateMsgDeliveryStatus(pnData.uid, selfRelDID, pnds.statusCode, pnds.statusDetail)
                updatePushNotificationDeliveryStatus(umds)
                if (pnds.statusCode == MSG_DELIVERY_STATUS_SENT.statusCode) {
                  metricsWriter.gaugeIncrement(AS_SERVICE_FIREBASE_SUCCEED_COUNT)
                  Right(pnds)
                } else {
                  metricsWriter.gaugeIncrement(AS_SERVICE_FIREBASE_FAILED_COUNT)
                  Left(pnds)
                }
              case x =>
                val umds = UpdateMsgDeliveryStatus(pnData.uid, selfRelDID, MSG_DELIVERY_STATUS_FAILED.statusCode, Option(x.toString))
                updatePushNotificationDeliveryStatus(umds)
                metricsWriter.gaugeIncrement(AS_SERVICE_FIREBASE_FAILED_COUNT)
                Left(x)
            }
          }
          recordDeliveryState(pnData.uid, pnData.msgType, s"push notification", fut)
        }
      }.recover {
        case e: Exception =>
          logger.error("could not send push notification", (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
         throw e
      }
    }
  }

  def recordDeliveryState(msgId: MsgId, msgType: String, msg: String, fut: Future[Any]): Future[Any] = {
    fut.map {
      case Left(e) =>
        recordOutMsgDeliveryEvent(msgId, MsgEvent.withTypeAndDetail(
          msgType, s"FAILED [outgoing message to registered com method ($msg) (error: ${e.toString})]"))
      case _ =>
        recordOutMsgDeliveryEvent(msgId, MsgEvent.withTypeAndDetail(
          msgType, s"SENT [outgoing message to registered com method ($msg)]"))
    }.recover {
      case e: Throwable =>
        recordOutMsgDeliveryEvent(msgId, MsgEvent.withTypeAndDetail(
          msgType, s"FAILED [outgoing message to registered com method ($msg) (error: ${e.getMessage})]"))
    }
  }

  import akka.actor.typed.scaladsl.adapter._
  implicit val typedSystem: ActorSystem[_] = agentActorContext.system.toTyped

  private def sendBinaryMsg(msg: Array[Byte], toUrl: String, withAuthHeader: Boolean)
  : Future[Either[HandledErrorException, Any]] = {
    newLegacyMsgSender.ask(ref => SendBinaryMsg(msg, toUrl, withAuthHeader, withRefreshedToken = false, ref))
      .mapTo[SendMsgResp]
      .map(_.resp)
  }

  private def sendJsonMsg(msg: String, toUrl: String, withAuthHeader: Boolean)
  : Future[Either[HandledErrorException, Any]] = {
    newLegacyMsgSender.ask(ref => SendJsonMsg(msg, toUrl, withAuthHeader, withRefreshedToken = false, ref))
      .mapTo[SendMsgResp]
      .map(_.resp)
  }

  private def newLegacyMsgSender: akka.actor.typed.ActorRef[LegacyMsgSender.Cmd] =
    context.spawnAnonymous(LegacyMsgSender(selfRelDID, agentMsgRouter, msgSendingSvc))

  private def updatePushNotificationDeliveryStatus(umds: UpdateMsgDeliveryStatus): Unit =
    self ! umds

}

/**
 * contains common code for 'MsgNotifierForUserAgent' and 'MsgNotifierForUserAgentPairwise' traits
 */
trait MsgNotifierForUserAgentCommon
  extends MsgNotifierForStoredMsgs {
  this: AgentPersistentActor
    with PushNotifMsgBuilder
    with MsgAndDeliveryHandler
    with HasMsgProgressTracker
    with SendOutgoingMsg
    with HasLogger =>

  def agentMsgRouter: AgentMsgRouter = agentActorContext.agentMsgRouter
  def msgSendingSvc: MsgSendingSvc = agentActorContext.msgSendingSvc

  override def sendStoredMsgToSelf(msgId:MsgId): Future[Any] = {
    logger.debug("about to send stored msg to self: " + msgId)
    val msg = msgStore.getMsgReq(msgId)
    val payloadWrapper = msgStore.getMsgPayload(msgId)
    notifyUserForNewMsg(NotifyMsgDetail(msgId, msg.getType, payloadWrapper))
  }
}

trait MsgNotifierForUserAgentPairwise extends MsgNotifierForUserAgentCommon {

  this: UserAgentPairwise with PushNotifMsgBuilder =>

  override def selfRelDID: DID = state.mySelfRelDID.getOrElse(
    throw new RuntimeException("owner's edge agent DID not yet set"))

  /**
   * this should be main agent actor's (UserAgent) agent DID
   * @return
   */
  override def ownerAgentKeyDIDReq: DID = state.ownerAgentDidPair.map(_.DID).getOrElse(
    throw new RuntimeException("owner's cloud agent DID not yet set"))

  override def msgRecipientDID: DID = state.myDid_!
}

trait MsgNotifierForUserAgent extends MsgNotifierForUserAgentCommon {

  this: UserAgent with PushNotifMsgBuilder =>

  override def selfRelDID: DID = state.myDid_!

  /**
   * this should be main agent actor's (UserAgent) agent DID
   * @return
   */
  override def ownerAgentKeyDIDReq: DID = state.thisAgentKeyDIDReq

  override def msgRecipientDID: DID = state.myDid_!
}

case class FwdMetaData(msgType: Option[String], msgSenderName: Option[String])
case class FwdMsg(msgId: String, msgType: String, sponseeDetails: String, relationshipDid: DID, metaData: FwdMetaData)

object NotifyMsgDetail {
  def withTrackingId(msgType: String, payloadWrapper: Option[PayloadWrapper]): NotifyMsgDetail =
    NotifyMsgDetail("TrackingId-" + MsgIdProvider.getNewMsgId, msgType, payloadWrapper)
}

case class NotifyMsgDetail(uid: MsgId, msgType: String, payloadWrapper: Option[PayloadWrapper] = None) {
  // this is used for legacy reasons, for compatibility with old versions of mobile application.
  // it will be removed after some time
  def deprecatedPushMsgType: String = if (msgType.contains('/')) MSG_TYPE_UNKNOWN else msgType

  def msgTypeWithoutFamilyQualifier: String = {
    /*
    msgType could be in following formats:
      did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/offer-credential -> issue-credential/1.0/offer-credential
      https://didcomm.org/issue-credential/1.0/offer-credential -> issue-credential/1.0/offer-credential
      proofReq -> proofReq
     */
    val segments = msgType.split('/')
    segments.slice(segments.length - 3, segments.length).mkString("/")
  }
}
