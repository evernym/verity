package com.evernym.verity.actor.agent.msghandler.outgoing

import akka.actor.ActorRef
import akka.pattern.ask
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status._
import com.evernym.verity.actor.agent.SpanUtil._
import com.evernym.verity.actor.agent._
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.actor.agent.MsgPackFormat._
import com.evernym.verity.actor.agent.user._
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily.pairwise.MsgExtractor
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.config.ConfigUtil
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.http.common.MsgSendingSvc
import com.evernym.verity.logging.ThrottledLogger
import com.evernym.verity.metrics.CustomMetrics._
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.protocol.actor.UpdateMsgDeliveryStatus
import com.evernym.verity.protocol.engine.MsgFamily.VALID_MESSAGE_TYPE_REG_EX
import com.evernym.verity.protocol.engine._
import com.evernym.verity.push_notification._
import com.evernym.verity.util.StrUtil.camelToKebab
import com.evernym.verity.vault.KeyParam
import com.evernym.verity.{Exceptions, UrlParam}

import scala.concurrent.Future


trait MsgNotifier {
  this: AgentPersistentActor
    with HasLogger =>

  /**
   * this actor will be created for each actor (UserAgent or UserAgentPairwise) of the logical agent
   */
  private val pusher: ActorRef = {
    context.actorOf(Pusher.props(appConfig), s"pusher-$persistenceId")
  }

  def sendPushNotif(pcms: Set[ComMethodDetail], pnData: PushNotifData, sponsorId: Option[String]): Future[Any] = {
    runWithInternalSpan("sendPushNotif", "MsgNotifier") {
      logger.debug("push com methods: " + pcms)
      val spn = SendPushNotif(pcms, pnData.sendAsAlertPushNotif, pnData.notifData, pnData.extraData, sponsorId)
      logger.debug("pn data: " + spn)
      pusher ? spn
    }
  }
}

trait MsgNotifierForStoredMsgs
  extends MsgNotifier
    with PushNotifMsgBuilder {

  this: AgentPersistentActor with MsgAndDeliveryHandler with HasLogger =>

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

  private val throttledLogger = new ThrottledLogger[MsgNotifierMessages](logger)

  private val generalNewMsgBodyTemplateOpt: Option[String] =
    appConfig.getConfigStringOption(PUSH_NOTIF_GENERAL_NEW_MSG_BODY_TEMPLATE)

  def getAllComMethods: Future[CommunicationMethods] =
    agentMsgRouter.execute(InternalMsgRouteParam(ownerAgentKeyDIDReq, GetAllComMethods)).mapTo[CommunicationMethods]

  def withComMethods(providedComMethod: Option[CommunicationMethods]): Future[CommunicationMethods] = {
    providedComMethod match {
      case Some(cm) => Future.successful(cm)
      case _        => getAllComMethods
    }
  }

  def notifyUserForNewMsg(notifMsgDtl: NotifyMsgDetail, updateDeliveryStatus: Boolean): Future[Any] = {
    //NOTE: as of now, assumption is that there would be only
    // one com method registered (either http endpoint or push notification)
    logger.debug("about to notify user for newly received message: " + notifMsgDtl.uid + s"(${notifMsgDtl.msgType})")
    getAllComMethods.map { allComMethods =>
      val fut1 = getMsgPayload(notifMsgDtl.uid).map(pw => sendMsgToRegisteredEndpoint(pw, Option(allComMethods)))
      val fut2 = Option(sendMsgToRegisteredPushNotif(notifMsgDtl, updateDeliveryStatus, Option(allComMethods)))
      val fut3 = Option(fwdMsgToSponsor(notifMsgDtl, Option(allComMethods)))
      val allFut = (fut1 ++ fut2 ++ fut3).toSet
      Future.sequence(allFut)
    }
  }

  def notifyUserForFailedMsgDelivery(notifMsgDtl: NotifyMsgDetail, updateDeliveryStatus: Boolean): Unit = {
    try {
      notifyForErrorResponseFromRemoteAgent(notifMsgDtl, updateDeliveryStatus)
    } catch {
      case e: Exception =>
        logger.warn("error response received from remote agent", (LOG_KEY_UID, notifMsgDtl.uid),
          (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
    }
  }

  def notifyUserForSuccessfulMsgDelivery(notifMsgDtl: NotifyMsgDetail, updateDeliveryStatus: Boolean): Unit = {
    try {
      notifyForSuccessResponseFromRemoteAgent(notifMsgDtl, updateDeliveryStatus)
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
        case VALID_MESSAGE_TYPE_REG_EX(_, _, _, _, _)  => "general"
        case mt if mt.toUpperCase == mt                => "general"
        case _ => camelToKebab(msgType)
      }
    }
    val msgTypeBasedTemplateConfigName = s"$formattedMsgType-new-msg-body-template"
    val msgTypeBasedTemplate = appConfig.getConfigStringOption(msgTypeBasedTemplateConfigName)

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

  protected def sendMsgToRegisteredEndpoint(pw: PayloadWrapper, allComMethods: Option[CommunicationMethods]): Future[Any] = {
    pw.metadata.map(_.msgPackFormat) match {
      case None | Some(MPF_INDY_PACK|MPF_MSG_PACK) =>
        sendMsgToRegisteredEndpointLegacy(pw, allComMethods)
      case Some(MPF_PLAIN)  =>
        sendMsgToRegisteredEndpointNew(pw, allComMethods)
      case Some(Unrecognized(_)) =>
        throw new RuntimeException("unsupported msgPackFormat: Unrecognized can't be used here")
    }
  }

  protected def sendMsgToRegisteredEndpointLegacy(pw: PayloadWrapper, allComMethods: Option[CommunicationMethods]): Future[Any] = {
    runWithInternalSpan("sendMsgToRegisteredEndpointLegacy", "MsgNotifierForStoredMsgs") {
      withComMethods(allComMethods).map { comMethods =>
        val httpComMethods = comMethods.filterByType(COM_METHOD_TYPE_HTTP_ENDPOINT)
        logger.debug("received registered http endpoints: " + httpComMethods)
        httpComMethods.foreach { hcm =>
         logger.debug(s"about to send message to endpoint: " + hcm)
          pw.metadata.map(_.msgPackFormat) match {
            case None | Some(MPF_INDY_PACK|MPF_MSG_PACK) =>
              msgSendingSvc.sendBinaryMsg(pw.msg)(UrlParam(hcm.value))
            case Some(MPF_PLAIN)  =>
              msgSendingSvc.sendJsonMsg(new String(pw.msg))(UrlParam(hcm.value))
            case Some(Unrecognized(_)) => throw new RuntimeException("unsupported msgPackFormat: Unrecognized can't be used here")
          }
          logger.debug("message sent to endpoint (legacy): " + hcm)
        }
      }
    }
  }

  protected def sendMsgToRegisteredEndpointNew(pw: PayloadWrapper, allComMethods: Option[CommunicationMethods]): Future[Any] = {
    runWithInternalSpan("sendMsgToRegisteredEndpointNew", "MsgNotifierForStoredMsgs") {
      withComMethods(allComMethods).map { comMethods =>
        val httpComMethods = comMethods.filterByType(COM_METHOD_TYPE_HTTP_ENDPOINT)
        logger.debug("received registered http endpoints: " + httpComMethods)
        httpComMethods.foreach { hcm =>
          logger.debug(s"about to send message to endpoint: " + hcm)
          val pkgType = hcm.packaging.map(_.pkgType).getOrElse(MPF_INDY_PACK)
          pkgType match {
            case MPF_PLAIN =>
              msgSendingSvc.sendJsonMsg(new String(pw.msg))(UrlParam(hcm.value))
            case MPF_INDY_PACK | MPF_MSG_PACK =>
              val endpointRecipKeys = hcm.packaging.map(_.recipientKeys.map(verKey => KeyParam(Left(verKey))))
              // if endpoint recipKeys are not configured or empty, use default (legacy compatibility).
              val recipKeys = endpointRecipKeys match {
                case Some(keys) if keys.nonEmpty => keys
                case _ => defaultSelfRecipKeys
              }
              msgExtractor.packAsync(pkgType, new String(pw.msg), recipKeys).map { packedMsg =>
                msgSendingSvc.sendBinaryMsg(packedMsg.msg)(UrlParam(hcm.value))
              }
            case Unrecognized(_) => throw new RuntimeException("unsupported msgPackFormat: Unrecognized can't be used here")
          }
          logger.debug("message sent to endpoint: " + hcm)
        }
      }
    }
  }

  private def sendMsgToRegisteredPushNotif(notifMsgDtl: NotifyMsgDetail, updateDeliveryStatus: Boolean, allComMethods: Option[CommunicationMethods]): Future[Any] = {
    try {
      runWithInternalSpan("sendMsgDetailToRegisteredPushNotif", "MsgNotifierForStoredMsgs") {
        val msg = getMsgReq(notifMsgDtl.uid)
        val mds = getMsgDetails(notifMsgDtl.uid)
        val title = mds.get(TITLE).map(v => Map(TITLE -> v)).getOrElse(Map.empty)
        val detail = mds.get(DETAIL).map(v => Map(DETAIL -> v)).getOrElse(Map.empty)
        val name = mds.get(NAME_KEY).map(v => Map(NAME_KEY -> v)).getOrElse(Map.empty)
        val logoUrl = mds.get(LOGO_URL_KEY).map(v => Map(LOGO_URL_KEY -> v)).getOrElse(Map.empty)
        val extraData = title ++ detail ++ name ++ logoUrl

        val msgBodyTemplateToUse = getPushNotifTextTemplate(msg.getType)
        val newExtraData = extraData ++ Map(PUSH_NOTIF_BODY_TEMPLATE -> msgBodyTemplateToUse)

        logger.debug("new messages notification: " + notifMsgDtl)
        getCommonPushNotifData(notifMsgDtl, newExtraData) match {
          case Some(pnd) => sendPushNotif(pnd, updateDeliveryStatus = updateDeliveryStatus, allComMethods)
          case None      => Future.successful("push notification not enabled")
        }
      }
    } catch {
      case e: Exception =>
        logger.error("could not send push notification", (LOG_KEY_UID, notifMsgDtl.uid),
          (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
        Future.failed(e)
    }
  }

  private def notifyForErrorResponseFromRemoteAgent(notifMsgDtl: NotifyMsgDetail, updateDeliveryStatus: Boolean): Unit = {
    logger.debug("error response received from remote agent: " + notifMsgDtl, (LOG_KEY_ERR_MSG, notifMsgDtl))
    buildPushNotifDataForFailedMsgDelivery(notifMsgDtl).foreach { pnd =>
      sendPushNotif(pnd, updateDeliveryStatus = updateDeliveryStatus, None)
    }
  }

  private def notifyForSuccessResponseFromRemoteAgent(notifMsgDtl: NotifyMsgDetail, updateDeliveryStatus: Boolean): Unit = {
    logger.debug("successful response received from remote agent: " + notifMsgDtl)
    buildPushNotifDataForSuccessfulMsgDelivery(notifMsgDtl).foreach { pnd =>
      sendPushNotif(pnd, updateDeliveryStatus = updateDeliveryStatus, None)
    }
  }

  private def handleErrorIfFailed(pnds: Any): Unit = {
    pnds match {
      case pnr: PushNotifResponse =>
        if (pnr.statusCode == MSG_DELIVERY_STATUS_FAILED.statusCode) {
          logger.error(s"push notification failed (userDID: $selfRelDID): $pnr")
          val invalidTokenErrorCodes =
            agentActorContext.appConfig.getConfigSetOfStringOption(PUSH_NOTIF_INVALID_TOKEN_ERROR_CODES).
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
    runWithInternalSpan("fwdMsgToSponsor", "MsgNotifierForStoredMsgs") {
      logger.debug("about to get com methods to forward notification")
      withComMethods(allComMethods).map { comMethods =>
        val cms = comMethods.filterByType(COM_METHOD_TYPE_FWD_PUSH)
        logger.debug(s"fwdComMethods: $cms")
        cms.map(_.value).foreach { sponseeDetails =>
          getSponsorEndpoint(comMethods.sponsorId).foreach( url => {
            logger.debug(s"received sponsor's registered http endpoint: $url and sponsee's communication details $cms")

            val mds = getMsgDetails(notifMsgDtl.uid)
            val name = mds.getOrElse(NAME_KEY, "")
            val fwdMeta = FwdMetaData(Some(notifMsgDtl.msgType), Some(name))
            val fwdMsg = FwdMsg(notifMsgDtl.uid, sponseeDetails, msgRecipientDID, fwdMeta)

            msgSendingSvc.sendJsonMsg(new String(DefaultMsgCodec.toJson(fwdMsg)))(UrlParam(url))
            logger.debug("message sent to endpoint: " + url)
          })
        }
      }
    }
  }

  def sendPushNotif(pnData: PushNotifData, updateDeliveryStatus: Boolean = true, allComMethods: Option[CommunicationMethods]): Future[Any] = {
    runWithInternalSpan("sendPushNotif", "MsgNotifierForStoredMsgs") {
      logger.debug("about to get push com methods to send push notification")
      withComMethods(allComMethods).map { comMethods =>
        val cms = comMethods.filterByTypes(Seq(COM_METHOD_TYPE_PUSH, COM_METHOD_TYPE_SPR_PUSH))
        if (cms.nonEmpty) {
          self ! UpdateMsgDeliveryStatus(pnData.uid, selfRelDID, MSG_DELIVERY_STATUS_PENDING.statusCode, None)
          logger.debug("received push com methods: " + cms)
          val pushStart = System.currentTimeMillis()
          sendPushNotif(cms, pnData, comMethods.sponsorId) map { r =>
            val duration = System.currentTimeMillis() - pushStart
            MetricsWriter.gaugeApi.increment(AS_SERVICE_FIREBASE_DURATION, duration)
            r match {
              case pnds: PushNotifResponse =>
                val umds = UpdateMsgDeliveryStatus(pnData.uid, selfRelDID, pnds.statusCode, pnds.statusDetail)
                updatePushNotificationDeliveryStatus(updateDeliveryStatus, umds)
                if (pnds.statusCode == MSG_DELIVERY_STATUS_SENT.statusCode) {
                  MetricsWriter.gaugeApi.increment(AS_SERVICE_FIREBASE_SUCCEED_COUNT)
                } else {
                  MetricsWriter.gaugeApi.increment(AS_SERVICE_FIREBASE_FAILED_COUNT)
                }
              case x =>
                val umds = UpdateMsgDeliveryStatus(pnData.uid, selfRelDID, MSG_DELIVERY_STATUS_FAILED.statusCode, Option(x.toString))
                updatePushNotificationDeliveryStatus(updateDeliveryStatus, umds)
                MetricsWriter.gaugeApi.increment(AS_SERVICE_FIREBASE_FAILED_COUNT)
            }
            handleErrorIfFailed(r)
          }
        } else {
          throttledLogger.warn(NoPushMethodWarning(ownerAgentKeyDIDReq), s"no push com method registered for the user $ownerAgentKeyDIDReq")
        }
      }.recover {
        case e: Exception =>
          logger.error("could not send push notification", (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
         throw e
      }
    }
  }

  private def updatePushNotificationDeliveryStatus(updateDeliveryStatus: Boolean = true, umds: UpdateMsgDeliveryStatus): Unit = {
    if (updateDeliveryStatus)
      self ! umds
  }
}

/**
 * contains common code for 'MsgNotifierForUserAgent' and 'MsgNotifierForUserAgentPairwise' traits
 */
trait MsgNotifierForUserAgentCommon
  extends MsgNotifierForStoredMsgs {
  this: AgentPersistentActor
    with PushNotifMsgBuilder
    with MsgAndDeliveryHandler
    with SendOutgoingMsg
    with HasLogger =>

  def agentMsgRouter: AgentMsgRouter = agentActorContext.agentMsgRouter
  def msgSendingSvc: MsgSendingSvc = agentActorContext.msgSendingSvc

  override def sendStoredMsgToSelf(msgId:MsgId): Future[Any] = {
    logger.debug("about to send stored msg to self: " + msgId)
    val msg = getMsgReq(msgId)
    notifyUserForNewMsg(NotifyMsgDetail(msgId, msg.getType), updateDeliveryStatus = true)
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
  override def ownerAgentKeyDIDReq: DID = state.ownerAgentKeyDID.getOrElse(
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
case class FwdMsg(msgId: String, sponseeDetails: String, relationshipDid: DID, metaData: FwdMetaData)
case class NotifyMsgDetail(uid: MsgId, msgType: String)
