package com.evernym.verity.agentmsg.msgpacker

import com.evernym.verity.actor.agent.{MsgPackFormat, TypeFormat}
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{MsgFamilyName, MsgFamilyQualifier, MsgFamilyVersion, MsgName}
import com.evernym.verity.did.didcomm.v1.messages.MsgType
import com.evernym.verity.protocol.engine._
import com.evernym.verity.vault._
import com.evernym.verity.vault.wallet_api.WalletAPI

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag


class AgentMsgTransformer(val walletAPI: WalletAPI, val ac: AppConfig, val executionContext: ExecutionContext){

  def packAsync(msgPackFormat: MsgPackFormat,
                msg: String,
                encryptParam: EncryptParam)
               (implicit wap: WalletAPIParam): Future[PackedMsg] = {
    AgentMsgTransformerApi.packAsync(msgPackFormat, msg, encryptParam.recipKeyParams, encryptParam.senderKeyParam)(wap, walletAPI)
  }

  def unpackAsync(msg: Array[Byte], fromKeyParam: KeyParam, unpackParam: UnpackParam = UnpackParam())
                 (implicit wap: WalletAPIParam): Future[AgentMsgWrapper] = {
    AgentMsgTransformerApi.unpackAsync(msg, Option(fromKeyParam), unpackParam)(wap, walletAPI, executionContext)
  }
}

/**
 * wrapper around one decrypted agent message
 * @param msg
 * @param msgFamilyDetail
 * @param msgTypeFormat
 */
case class AgentMsg(msg: String, msgFamilyDetail: MsgFamilyDetail, msgTypeFormat: TypeFormat) {
  def convertTo[T: ClassTag]: T = {
    AgentMsgParseUtil.convertTo[T](msg)
  }
}

/**
 * wrapper to hold decrypted complete agent message (usually, it should be one, but legacy message used a
 * bundled wrapper which can contain more than one message)
 * @param msgs
 * @param senderVerKey
 * @param recipVerKey
 * @param legacyMsgFamily
 * @param usesLegacyGenMsgWrapper
 * @param usesLegacyBundledMsgWrapper
 */
case class AgentBundledMsg(msgs: List[AgentMsg],
                           senderVerKey: Option[VerKeyStr],
                           recipVerKey: Option[VerKeyStr],
                           legacyMsgFamily: Option[MsgFamilyDetail]=None,
                           usesLegacyGenMsgWrapper: Boolean=false,
                           usesLegacyBundledMsgWrapper: Boolean=false) {
  def headAgentMsg: AgentMsg = msgs.head
  def tailAgentMsgs: List[AgentMsg] = msgs.tail
}

case class AgentMsgTypeDetail(msgPackFormat: MsgPackFormat,
                              familyQualifier: MsgFamilyQualifier,
                              familyName: MsgFamilyName,
                              familyVersion: MsgFamilyVersion,
                              msgName: MsgFamilyName,
                              msgVer: Option[String]=None) {

  def typeDetail: TypeDetail = TypeDetail(msgName, msgVer.getOrElse(familyVersion))
  def msgType: MsgType = MsgType(familyQualifier, familyName, familyVersion, msgName)
  def msgVersion: String = msgVer.getOrElse(familyVersion)
}

//NOTE: few parameters in this case class is mainly to handle 0.5 version of agent messages
//so, it is for backward compatibility and in future we'll remove it
case class UnpackParam(parseParam: ParseParam=ParseParam(),
                       isAnonCryptedMsg: Boolean=false)

/**
  *
  * @param parseBundledMsgs determines if it needs to parse older style of bundled messages
  * @param useInsideMsgIfPresent determines if it needs to parse msg payload provided under @msg json attribute
  *                                this was one off change to support how libvcx currently constructs message
  *                                for other entity's edge agent
  */
case class ParseParam(parseBundledMsgs: Boolean = true, useInsideMsgIfPresent: Boolean = false)

//NOTE: origDetailOpt in MsgFamilyDetail case class is the original un parsed detail
// for example: did:sov:123456789abcdefghi1234;spec/onboarding/1.0/CONNECT
// 'msgVer' is only for backward compatibility
case class MsgFamilyDetail(familyQualifier: MsgFamilyQualifier,
                           familyName: MsgFamilyName,
                           familyVersion: MsgFamilyVersion,
                           msgName: MsgName,
                           msgVer: Option[String],
                           isLegacyMsg: Boolean = false
                          ) {
  def getTypeDetail: TypeDetail = TypeDetail(msgName, familyVersion)

  def msgType: MsgType = MsgType(familyQualifier, familyName, familyVersion, msgName)
}

/**
 * an interface to pack and unpack a message
 *
 */
trait MsgTransformer {

  def msgPackFormat: MsgPackFormat

  def packAsync(msg: String, recipVerKeyParams: Set[KeyParam], senderVerKeyParam: Option[KeyParam])
          (implicit wap: WalletAPIParam, walletAPI: WalletAPI): Future[PackedMsg]

  def unpackAsync(msg: Array[Byte], fromVerKeyParam: Option[KeyParam], unpackParam: UnpackParam)
                 (implicit wap: WalletAPIParam, walletAPI: WalletAPI, ec: ExecutionContext): Future[AgentBundledMsg]
}
