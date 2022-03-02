package com.evernym.verity.push_notification


import com.evernym.verity.actor.HasAppConfig
import com.evernym.verity.constants.Constants._
import com.evernym.verity.actor.agent.msghandler.outgoing.NotifyMsgDetail
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.did.DidStr
import com.evernym.verity.util.StrUtil.camelToSeparateWords
import com.evernym.verity.util.Util.replaceVariables


trait PushNotifMsgBuilder extends HasAppConfig {

  //edge DID (can be selfRel DID or pairwise DIDs) for which msg is received and corresponding push notif is to be sent
  def msgRecipientDID: DidStr

  protected def buildPushNotifDataForFailedMsgDelivery(notifMsgDtl: NotifyMsgDetail): Option[PushNotifData] = {
    val msgTemplateText = errResponseBodyTemplateOpt.getOrElse(
      "#{senderName} responded with error (detail: uid -> #{uid}, msg type -> #{msgType})")
    val newExtraData = Map(PUSH_NOTIF_BODY_TEMPLATE -> msgTemplateText)
    val pnd = buildPushNotifData(notifMsgDtl, newExtraData)
    val updatedExtraData = pnd.map(_.extraData).getOrElse(Map.empty) ++ Map(PUSH_NOTIF_MSG_TYPE -> "error")
    pnd.map(_.copy(extraData = updatedExtraData))
  }

  protected def buildPushNotifDataForSuccessfulMsgDelivery(notifMsgDtl: NotifyMsgDetail): Option[PushNotifData] = {
    val msgTemplateText = successResponseBodyTemplateOpt.getOrElse(
      "#{senderName} responded with successful response (detail: uid -> #{uid}, msg type -> #{msgType})")
    val newExtraData = Map(PUSH_NOTIF_BODY_TEMPLATE -> msgTemplateText)
    val pnd = buildPushNotifData(notifMsgDtl, newExtraData)
    val updatedExtraData = pnd.map(_.extraData).getOrElse(Map.empty) ++ Map(PUSH_NOTIF_MSG_TYPE -> "success")
    pnd.map(_.copy(extraData = updatedExtraData))
  }

  protected def buildPushNotifData(notifMsgDtl: NotifyMsgDetail,
                                   attributes: Map[String, String] = Map.empty): Option[PushNotifData] = {
    if (appConfig.getBooleanOption(PUSH_NOTIF_ENABLED).contains(true)) {

      //given/received values
      val givenSenderName = attributes.get(NAME_KEY)
      val givenSenderLogoUrl = attributes.get(LOGO_URL_KEY)
      val givenPushNotifTitle = attributes.get(TITLE)
      val givenPushNotifText = attributes.get(DETAIL)

      //default values (to be used if it is not explicitly provided in `attributes` parameter)
      val defaultSenderName = appConfig.getStringReq(PUSH_NOTIF_DEFAULT_SENDER_NAME)
      val defaultLogoUrl = appConfig.getStringReq(PUSH_NOTIF_DEFAULT_LOGO_URL)
      val defaultPushNotifTitle = {
        val titleTemplate = appConfig.getStringReq(PUSH_NOTIF_GENERAL_MSG_TITLE_TEMPLATE)
        replaceVariables(titleTemplate, Map(TARGET_NAME -> DEFAULT_INVITE_RECEIVER_USER_NAME))
      }
      val defaultPushNotifText = {
        val bodyTemplate = attributes.getOrElse(PUSH_NOTIF_BODY_TEMPLATE,
          throw new RuntimeException(s"not found: $PUSH_NOTIF_BODY_TEMPLATE"))
        replaceVariables(
          bodyTemplate,
          Map(
            SENDER_NAME -> givenSenderName.getOrElse(defaultSenderName),
            MSG_TYPE    -> buildPushMsgType(notifMsgDtl.msgTypeWithoutFamilyQualifier),
            UID         -> notifMsgDtl.uid
          )
        )
      }

      val notifData = Map(BODY -> givenPushNotifText.getOrElse(defaultPushNotifText), BADGE_COUNT -> 1)

      val extraData = Map(
        FOR_DID               -> msgRecipientDID,
        UID                   -> notifMsgDtl.uid,
        TYPE                  -> notifMsgDtl.deprecatedPushMsgType, // legacy, needed for backward compatibility with mobile app
        PUSH_NOTIF_MSG_TYPE   -> notifMsgDtl.msgType,
        SENDER_LOGO_URL       -> givenSenderLogoUrl.getOrElse(defaultLogoUrl),
        PUSH_NOTIF_MSG_TITLE  -> givenPushNotifTitle.getOrElse(defaultPushNotifTitle),
        PUSH_NOTIF_MSG_TEXT   -> givenPushNotifText.getOrElse(defaultPushNotifText)
      )

      val isAlertPushNotif = sendAsAlertPushNotif(notifMsgDtl.msgTypeWithoutFamilyQualifier)
      Option(PushNotifData(notifMsgDtl.uid, notifMsgDtl.msgType, isAlertPushNotif, notifData, extraData))
    } else None
  }

  private def sendAsAlertPushNotif(msgType: String): Boolean = {
    msgTypesForAlertingPushNotificationOpt.getOrElse(
      Set( CREATE_MSG_TYPE_CRED_OFFER, CREATE_MSG_TYPE_PROOF_REQ,
        CREATE_MSG_TYPE_TOKEN_XFER_OFFER, CREATE_MSG_TYPE_TOKEN_XFER_REQ, CREATE_MSG_TYPE_TOKEN_XFERRED,
        MSG_TYPE_UNKNOWN, "issue-credential/1.0/offer-credential", "issue-credential/1.0/issue-credential",
        "present-proof/1.0/request-presentation", "committedanswer/1.0/question", "questionanswer/1.0/question"
      )
    ).contains(msgType)
  }

  private def buildPushMsgType(msgType: String): String = {
    val msgTypeToBeUsed = msgType match {
      case CREATE_MSG_TYPE_PROOF_REQ                => "proofRequest"
      case MSG_TYPE_UNKNOWN                         => "message"
      case "issue-credential/1.0/offer-credential"  => "credentialOffer"
      case "issue-credential/1.0/issue-credential"  => "credential"
      case "present-proof/1.0/request-presentation" => "proofRequest"
      case "committedanswer/1.0/question"           => "question"
      case "questionanswer/1.0/question"            => "question"
      case mt if mt.contains("/")                   => "message"
      case mt                                       => mt
    }

    val capitalizeMsg =
      if (msgTypeToBeUsed == msgTypeToBeUsed.toUpperCase) msgTypeToBeUsed
      else camelToSeparateWords(msgTypeToBeUsed)
    val vowels = Set('a', 'e', 'i', 'o', 'u')
    val prefixed = if (vowels.contains(capitalizeMsg.head.toLower)) "an " else "a "
    prefixed + capitalizeMsg
  }

  private lazy val msgTypesForAlertingPushNotificationOpt: Option[Set[String]] =
    appConfig.getStringSetOption(PUSH_NOTIF_MSG_TYPES_FOR_ALERT_PUSH_MSGS)

  private lazy val errResponseBodyTemplateOpt: Option[String] =
    appConfig.getStringOption(PUSH_NOTIF_ERROR_RESP_MSG_BODY_TEMPLATE)

  private lazy val successResponseBodyTemplateOpt: Option[String] =
    appConfig.getStringOption(PUSH_NOTIF_SUCCESS_RESP_MSG_BODY_TEMPLATE)

}
