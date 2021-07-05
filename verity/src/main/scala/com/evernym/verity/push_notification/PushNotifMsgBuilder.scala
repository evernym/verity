package com.evernym.verity.push_notification


import com.evernym.verity.constants.Constants._
import com.evernym.verity.actor.agent.msghandler.outgoing.NotifyMsgDetail
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.protocol.protocols.HasAppConfig
import com.evernym.verity.util.Util.replaceVariables


trait PushNotifMsgBuilder extends HasAppConfig {

  //edge DID (can be main DID or pairwise DIDs) for which msg is received and corresponding push notif is being sent
  def msgRecipientDID: DID

  lazy val msgTypesForAlertingPushNotificationOpt: Option[Set[String]] =
    appConfig.getStringSetOption(PUSH_NOTIF_MSG_TYPES_FOR_ALERT_PUSH_MSGS)

  lazy val errResponseBodyTemplateOpt: Option[String] =
    appConfig.getStringOption(PUSH_NOTIF_ERROR_RESP_MSG_BODY_TEMPLATE)

  lazy val successResponseBodyTemplateOpt: Option[String] =
    appConfig.getStringOption(PUSH_NOTIF_SUCCESS_RESP_MSG_BODY_TEMPLATE)

  lazy val errorsForWhichComMethodShouldBeDeleted =
    Set(PUSH_COM_METHOD_NOT_REGISTERED_ERROR, PUSH_COM_METHOD_INVALID_REGISTRATION_ERROR,
      PUSH_COM_METHOD_MISMATCH_SENDER_ID_ERROR)

  def sendAsAlertPushNotif(msgType: String): Boolean = {
    msgTypesForAlertingPushNotificationOpt.getOrElse(
      Set( CREATE_MSG_TYPE_CRED_OFFER, CREATE_MSG_TYPE_PROOF_REQ,
        CREATE_MSG_TYPE_TOKEN_XFER_OFFER, CREATE_MSG_TYPE_TOKEN_XFER_REQ, CREATE_MSG_TYPE_TOKEN_XFERRED,
        MSG_TYPE_UNKNOWN, "issue-credential/1.0/offer-credential", "issue-credential/1.0/issue-credential",
        "present-proof/1.0/request-presentation", "committedanswer/1.0/question", "questionanswer/1.0/question"
      )
    ).contains(msgType)
  }

  protected def buildPushNotifDataForFailedMsgDelivery(notifMsgDtl: NotifyMsgDetail): Option[PushNotifData] = {
    val msgTemplateText = errResponseBodyTemplateOpt.getOrElse(
      "#{senderName} responded with error (detail: uid -> #{uid}, msg type -> #{msgType})")
    val newExtraData = Map(PUSH_NOTIF_BODY_TEMPLATE -> msgTemplateText)
    val pnd = getCommonPushNotifData(notifMsgDtl, newExtraData)
    val updatedExtraData = pnd.map(_.extraData).getOrElse(Map.empty) ++ Map(PUSH_NOTIF_MSG_TYPE -> "error")
    pnd.map(_.copy(extraData = updatedExtraData))
  }

  protected def buildPushNotifDataForSuccessfulMsgDelivery(notifMsgDtl: NotifyMsgDetail): Option[PushNotifData] = {
    val msgTemplateText = successResponseBodyTemplateOpt.getOrElse(
      "#{senderName} responded with successful response (detail: uid -> #{uid}, msg type -> #{msgType})")
    val newExtraData = Map(PUSH_NOTIF_BODY_TEMPLATE -> msgTemplateText)
    val pnd = getCommonPushNotifData(notifMsgDtl, newExtraData)
    val updatedExtraData = pnd.map(_.extraData).getOrElse(Map.empty) ++ Map(PUSH_NOTIF_MSG_TYPE -> "success")
    pnd.map(_.copy(extraData = updatedExtraData))
  }

  protected def getCommonPushNotifData(notifMsgDtl: NotifyMsgDetail, mds: Map[String, String] = Map.empty): Option[PushNotifData] = {
    if (appConfig.getBooleanOption(PUSH_NOTIF_ENABLED).contains(true)) {
      val rcvdSenderName = mds.get(NAME_KEY)
      val rcvdSenderLogoUrl = mds.get(LOGO_URL_KEY)
      val rcvdTitle = mds.get(TITLE)
      val rcvdDetail = mds.get(DETAIL)
      val bodyTemplate = mds.getOrElse(PUSH_NOTIF_BODY_TEMPLATE, throw new RuntimeException(s"not found: $PUSH_NOTIF_BODY_TEMPLATE"))

      val titleTemplate = appConfig.getStringReq(PUSH_NOTIF_GENERAL_MSG_TITLE_TEMPLATE)
      val defaultSenderName = appConfig.getStringReq(PUSH_NOTIF_DEFAULT_SENDER_NAME)
      val defaultLogoUrl = appConfig.getStringReq(PUSH_NOTIF_DEFAULT_LOGO_URL)
      val defaultTitle = replaceVariables(titleTemplate, Map(TARGET_NAME -> DEFAULT_INVITE_RECEIVER_USER_NAME))
      val defaultDetail = replaceVariables(bodyTemplate, Map(SENDER_NAME -> rcvdSenderName.getOrElse(defaultSenderName),
        MSG_TYPE -> PusherUtil.getPushMsgType(notifMsgDtl.msgTypeWithoutFamilyQualifier), UID -> notifMsgDtl.uid))

      val notifData = Map(BODY -> rcvdDetail.getOrElse(defaultDetail), BADGE_COUNT -> 1)
      val extraData = Map(
        TYPE -> notifMsgDtl.deprecatedPushMsgType, // legacy, needed for backward compatibility with mobile app
        PUSH_NOTIF_MSG_TYPE -> notifMsgDtl.msgType,
        FOR_DID -> msgRecipientDID,
        UID -> notifMsgDtl.uid,
        SENDER_LOGO_URL -> rcvdSenderLogoUrl.getOrElse(defaultLogoUrl),
        PUSH_NOTIF_MSG_TITLE -> rcvdTitle.getOrElse(defaultTitle),
        PUSH_NOTIF_MSG_TEXT -> rcvdDetail.getOrElse(defaultDetail)
      )

      val isAlertPushNotif = sendAsAlertPushNotif(notifMsgDtl.msgTypeWithoutFamilyQualifier)
      Option(PushNotifData(notifMsgDtl.uid, notifMsgDtl.msgType, isAlertPushNotif, notifData, extraData))
    } else None
  }

}
