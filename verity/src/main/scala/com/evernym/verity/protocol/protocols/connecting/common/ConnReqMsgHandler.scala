package com.evernym.verity.protocol.protocols.connecting.common

import com.evernym.verity.util2.Exceptions.{BadRequestErrorException, HandledErrorException}
import com.evernym.verity.util2.Status.{ALREADY_EXISTS, DATA_NOT_FOUND, MSG_DELIVERY_STATUS_FAILED, MSG_DELIVERY_STATUS_PENDING, MSG_DELIVERY_STATUS_SENT}
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_PLAIN
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.actor.{AgentKeyDlgProofSet, MsgDetailAdded}
import com.evernym.verity.agentmsg.msgfamily.AgentMsgContext
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.CREATE_MSG_TYPE_CONN_REQ
import com.evernym.verity.agentmsg.msgfamily.pairwise.{ConnReqMsg, ConnReqMsgHelper}
import com.evernym.verity.agentmsg.msgpacker.AgentMsgPackagingUtil
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.config.ConfigUtil.findAgentSpecificConfig
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.protocol.container.actor.ProtoMsg
import com.evernym.verity.protocol.engine._
import com.evernym.verity.util.HashAlgorithm.SHA256_trunc4
import com.evernym.verity.util.HashUtil
import com.evernym.verity.util.HashUtil.byteArray2RichBytes
import com.evernym.verity.util.Util._
import com.evernym.verity.util2.UrlParam
import com.evernym.verity.vault.{EncryptParam, KeyParam}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Left


trait ConnReqMsgHandler[S <: ConnectingStateBase[S]] {
  this: ConnectingProtocolBase[_,_,S,_] with Protocol[_,_,ProtoMsg,Any,S,_] =>

  private implicit val executionContext: ExecutionContext = futureExecutionContext

  protected def handleConnReqMsgBase(connReqMsg: ConnReqMsg,
                                     sourceId: Option[String]=None)(implicit amc: AgentMsgContext): PackedMsg = {
    validateConnReqMsg(connReqMsg)
    processValidatedConnReqMsg(connReqMsg, sourceId)
    processPersistedConnReqMsg(connReqMsg, sourceId)
  }

  private def validateConnReqMsg(connReqMsg: ConnReqMsg): Unit = {
    checkNoAcceptedInvitationExists()
    connReqMsg.phoneNo.foreach(checkIfValidPhoneNumber)
    connReqMsg.keyDlgProof.foreach (verifyAgentKeyDlgProof(_, myPairwiseVerKeyReq, isEdgeAgentsKeyDlgProof = true))
    val expectedConfigNames = Set(NAME, LOGO_URL)
    val configsFound = ctx.getState.parameters.initParams.filter(cd => expectedConfigNames.contains(cd.name)).map(_.name)
    if (configsFound.size != 2 ) {
      val configsNotFound = configsFound.intersect(configsFound)
      throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("required config not yet set: " + configsNotFound.mkString(",")))
    }
  }

  protected def checkIfValidPhoneNumber(ph: String): Unit = getNormalizedPhoneNumber(ph)

  private def processValidatedConnReqMsg(connReqMsg: ConnReqMsg,
                                         sourceId: Option[String]=None): Unit = {
    val msgCreated = buildMsgCreatedEvt(
      connReqMsg.id,
      CREATE_MSG_TYPE_CONN_REQ,
      ctx.getState.myPairwiseDIDReq,
      connReqMsg.sendMsg,
      connReqMsg.threadOpt
    )
    ctx.apply(msgCreated)
    writeConnReqMsgDetail(msgCreated.uid, connReqMsg, sourceId)
    connReqMsg.keyDlgProof.foreach(kdp => ctx.apply(AgentKeyDlgProofSet(kdp.agentDID, kdp.agentDelegatedKey, kdp.signature)))

    //NOTE: below is a signal messages to be sent to agent actor to be stored/updated in agent's message store
    // because get/download message API only queries agent's message store
    DEPRECATED_sendSpecialSignal(AddMsg(msgCreated))
  }

  private def processPersistedConnReqMsg(connReqMsg: ConnReqMsg,
                                         sourceId: Option[String]=None)(implicit agentMsgContext: AgentMsgContext): PackedMsg = {
    val otherRespMsgs = buildSendMsgResp(connReqMsg.id)
    val inviteDetail = buildInviteDetail(connReqMsg.id, checkIfExpired=false)
    updateParentAboutConnReqProcessed(inviteDetail)
    sendConnReqCreatedRespMsg(connReqMsg.id, inviteDetail, otherRespMsgs, sourceId)
  }

  private def sendConnReqCreatedRespMsg(connReqUid: MsgId,
                                        inviteDetail: InviteDetail,
                                        otherRespMsgs: List[Any],
                                        sourceId: Option[String]=None)
                                       (implicit agentMsgContext: AgentMsgContext): PackedMsg = {
    val inviteUrl = getInviteUrl(connReqUid)
    val createInviteRespMsg = ConnReqMsgHelper.buildRespMsg(connReqUid, threadIdReq,
      inviteDetail, inviteUrl, encodedUrl(inviteUrl), sourceId)(agentMsgContext)

    if (agentMsgContext.msgPackFormat == MPF_PLAIN) {
      (createInviteRespMsg ++ otherRespMsgs).foreach{ msg => ctx.signal(msg) }
    }

    val param = AgentMsgPackagingUtil.buildPackMsgParam(encParamFromThisAgentToOwner,
      createInviteRespMsg ++ otherRespMsgs, agentMsgContext.wrapInBundledMsg)
    buildAgentPackedMsg(agentMsgContext.msgPackFormatToBeUsed, param)
  }

  private def getInviteUrl(uid: MsgId): String = {
    val baseUrl = buildAgencyUrl(appConfig, Some("agency/invite/"))
    baseUrl.toString + ctx.getState.myPairwiseDIDReq + "?uid=" + uid
  }

  private def updateParentAboutConnReqProcessed(id: InviteDetail): Unit = {
    ctx.signal(ConnReqReceived(id))
  }

  private def writeConnReqMsgDetail(uid: MsgId, connReqMsg: ConnReqMsg, sourceId: Option[String]=None): Unit = {
    writeMsgDetail(uid, PHONE_NO, connReqMsg.phoneNo)
    writeMsgDetail(uid, TARGET_NAME, connReqMsg.targetName)
    writeMsgDetail(uid, SOURCE_ID, sourceId)
    connReqMsg.includePublicDID.filter( _ == true).foreach { _ =>
      writeMsgDetail(uid, INCLUDE_PUBLIC_DID, Option(YES))
    }
  }

  private def writeMsgDetail(uid: MsgId, name: String, valueOpt: Option[String]): Unit = {
    valueOpt.foreach { value =>
      ctx.apply(MsgDetailAdded(uid, name, value))
    }
  }

  protected def sendConnReqMsg(uid: MsgId): Unit = {
    getMsgDetail(uid, PHONE_NO).foreach { ph =>
      updateMsgDeliveryStatus(uid, PHONE_NO, MSG_DELIVERY_STATUS_PENDING.statusCode, None)
      buildAndSendInviteSms(ph, uid)
    }
  }

  private def buildAndSendInviteSms(phoneNo: String, uid: MsgId): Unit = {
    logger.debug(s"[$uid] invite sms preparation started...")
    val urlMapperSvcHost = appConfig.getStringReq(URL_MAPPER_SVC_ENDPOINT_HOST)
    val urlMapperSvcPort = appConfig.getIntReq(URL_MAPPER_SVC_ENDPOINT_PORT)
    val urlMapperPathPrefix = Option(appConfig.getStringReq(URL_MAPPER_SVC_ENDPOINT_PATH_PREFIX))
    val urlMapperEndpoint = UrlParam(urlMapperSvcHost, urlMapperSvcPort, urlMapperPathPrefix)
    implicit val param: CreateAndSendTinyUrlParam = CreateAndSendTinyUrlParam(uid, phoneNo, urlMapperEndpoint)
    val domainId = ctx.getBackState.domainId
    createAndSendTinyUrl(`userName_!`, domainId, tryCount = 1)
  }

  private def createAndSendTinyUrl(userName: String, domainId: Option[DomainId], tryCount: Int)(implicit param: CreateAndSendTinyUrlParam): Unit = {
    try {
      val addRespFut = ctx.SERVICES_DEPRECATED.tokenToActorMappingProvider.createToken(param.uid)
      addRespFut.map {
        case Right(token)                       => buildAndStoreUrlMapping(userName, token, domainId, tryCount = 1)
        case Left(he) if canRetry(he, tryCount) => createAndSendTinyUrl(userName, domainId, tryCount + 1)
        case e                                  =>
          updateMsgDeliveryStatus(param.uid, PHONE_NUMBER, MSG_DELIVERY_STATUS_FAILED.statusCode, Option(e.toString))
      }.recover {
        case e: Exception =>
          updateMsgDeliveryStatus(param.uid, PHONE_NUMBER, MSG_DELIVERY_STATUS_FAILED.statusCode, Option(e.getMessage))
      }
    } catch {
      case e: Exception =>
        updateMsgDeliveryStatus(param.uid, PHONE_NUMBER, MSG_DELIVERY_STATUS_FAILED.statusCode, Option(e.getMessage))
    }
  }

  private def canRetry(he: HandledErrorException, tryCount: Int): Boolean =
    he.respCode == ALREADY_EXISTS.statusCode && withinMaxTryCount(tryCount)

  private def buildAndStoreUrlMapping(userName: String, token: String, domainId: Option[DomainId], tryCount: Int)
                                     (implicit param: CreateAndSendTinyUrlParam): Unit = {
    try {
      logger.debug(s"[${param.uid}] token added")
      val actualUrl = buildActualInviteUrl(token)
      val shortHashedUrl = HashUtil.hash(SHA256_trunc4)(actualUrl).hex
      val jsonData = getJsonStringFromMap(Map(URL -> actualUrl, HASHED_URL -> shortHashedUrl))
      val resFut = msgSendingSvc.sendPlainTextMsg(jsonData)(param.urlMappingServiceEndpoint)
      handleUrlMappingResp(userName, domainId, shortHashedUrl, resFut, tryCount)
    } catch {
      case e: Exception =>
        logger.warn(s"[${param.uid}] failed during storing url mapping")
        updateMsgDeliveryStatus(param.uid, PHONE_NUMBER, MSG_DELIVERY_STATUS_FAILED.statusCode, Option(e.getMessage))
    }
  }

  private def handleUrlMappingResp(userName: String, domainId: Option[DomainId], shortHashedUrl: String, resFut: Future[Either[HandledErrorException, String]], tryCount: Int)
                                  (implicit param: CreateAndSendTinyUrlParam): Unit = {
    resFut.map {
      case Right(_)                                   => sendSMS(shortHashedUrl, userName)
      case Left(he: HandledErrorException) if canRetry(he, tryCount) => createAndSendTinyUrl(userName, domainId, tryCount=1)
      case e =>
        updateMsgDeliveryStatus(param.uid, PHONE_NUMBER, MSG_DELIVERY_STATUS_FAILED.statusCode, Option(e.toString))
    }.recover {
      case e: Exception =>
        updateMsgDeliveryStatus(param.uid, PHONE_NUMBER, MSG_DELIVERY_STATUS_FAILED.statusCode, Option(e.getMessage))
    }

    def sendSMS(hashToken: String, userName: String): Unit = {
      logger.debug(s"[${param.uid}] sending sms started ...")
      val url = {
        val template = findAgentSpecificConfig(
          SMS_OFFER_TEMPLATE_DEEPLINK_URL,
          domainId,
          appConfig
        )
        replaceVariables(template, Map(TOKEN -> hashToken))
      }
      val senderName = userName
      val offerConnMsg = findAgentSpecificConfig(
        SMS_MSG_TEMPLATE_OFFER_CONN_MSG,
        domainId,
        appConfig
      )
      val content = replaceVariables(offerConnMsg, Map(APP_URL_LINK -> url, REQUESTER_NAME -> senderName))

      val sendSmsFut = ctx.sendSMS(param.phoneNo, content)
      handleSmsSentResp(sendSmsFut)
    }
  }

  private def handleSmsSentResp(sendSmsFut: Future[String])
                               (implicit param: CreateAndSendTinyUrlParam): Unit = {
    sendSmsFut.map { _ =>
      logger.debug(s"[${param.uid}] invite sms sent successfully")
      updateMsgDeliveryStatus(param.uid, PHONE_NUMBER, MSG_DELIVERY_STATUS_SENT.statusCode, None)
    }.recover {
      case er: HandledErrorException =>
        // Phone number should already be validated at this point, but lets double check before we print an error
        try {
          checkIfValidPhoneNumber(param.phoneNo)
          logger.error(s"could not send invite sms", (LOG_KEY_UID, param.uid),
            (LOG_KEY_RESPONSE_CODE, er.respCode), (LOG_KEY_ERR_MSG, er.respMsg))
        } catch {
          case _: BadRequestErrorException =>
            logger.warn("invalid phone number", (LOG_KEY_UID, param.uid),
              (LOG_KEY_RESPONSE_CODE, er.respCode), (LOG_KEY_ERR_MSG, er.respMsg))
        }
        updateMsgDeliveryStatus(param.uid, PHONE_NUMBER, MSG_DELIVERY_STATUS_FAILED.statusCode, er.respMsg)
    }
  }

  private def buildActualInviteUrl(token: String): String = {
    val url = appConfig.getStringReq(SMS_MSG_TEMPLATE_INVITE_URL)
    val baseUrl = buildAgencyUrl(appConfig, None)
    replaceVariables(url, Map(BASE_URL -> baseUrl.toString, TOKEN -> token))
  }

  val MAX_BUILD_AND_SEND_SMS_TRY_COUNT = 5

  private def withinMaxTryCount(curTryCount: Int): Boolean = curTryCount <= MAX_BUILD_AND_SEND_SMS_TRY_COUNT

  def encParamFromThisAgentToOwner: EncryptParam = EncryptParam(
    Set(KeyParam(Left(getVerKeyReqViaCache(getEncryptForDID).verKey))),
    Option(KeyParam(Left(ctx.getState.thisAgentVerKeyReq)))
  )
}
