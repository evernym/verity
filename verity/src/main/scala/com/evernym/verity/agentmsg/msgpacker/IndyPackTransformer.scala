package com.evernym.verity.agentmsg.msgpacker

import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.wallet.{PackedMsg, UnpackedMsg}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.vault.{KeyInfo, WalletAPI, WalletAPIParam}
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

/**
 * this uses 'indy' (https://github.com/hyperledger/indy-sdk/blob/master/libindy/src/api/crypto.rs)
 * for 'pack' and 'unpack' functions
 */
class IndyPackTransformer
  extends MsgTransformer {

  val msgPackFormat: MsgPackFormat = MPF_INDY_PACK

  val logger: Logger = getLoggerByClass(classOf[IndyPackTransformer])

  override def pack(msg: String, recipVerKeys: Set[KeyInfo],
                    senderVerKey: Option[KeyInfo], packParam: PackParam)
                   (implicit wap: WalletAPIParam, walletAPI: WalletAPI): PackedMsg = {
    walletAPI.packMessage(msg.getBytes, recipVerKeys, senderVerKey)
  }

  override def unpack(msg: Array[Byte], fromVerKey: Option[KeyInfo],
                      unpackParam: UnpackParam)(implicit wap: WalletAPIParam, walletAPI: WalletAPI)
  : AgentBundledMsg = {
    val um = walletAPI.unpackMessage(msg)
    prepareAgentBundledMsg(um, unpackParam)
  }

  override def unpackAsync(msg: Array[Byte], fromVerKey: Option[KeyInfo], unpackParam: UnpackParam)
                          (implicit wap: WalletAPIParam, walletAPI: WalletAPI): Future[AgentBundledMsg] = {
    walletAPI.unpackMessageAsync(msg).map { um =>
      prepareAgentBundledMsg(um, unpackParam)
    }
  }

  private def prepareAgentBundledMsg(um: UnpackedMsg, unpackParam: UnpackParam): AgentBundledMsg = {
    val binaryMsg = um.msg
    val jsonStringMsg = new String(binaryMsg)
    val resultMap = DefaultMsgCodec.fromJson[Map[String, String]](jsonStringMsg)
    val msgJsonString = resultMap("message")
    val senderVerKeyOpt = resultMap.get("sender_verkey")
    val recipVerKeyOpt = resultMap.get("recipient_verkey")
    val unpackedMsg = UnpackedMsg(msgJsonString, senderVerKeyOpt, recipVerKeyOpt)
    AgentMsgParseUtil.parse(unpackedMsg, unpackParam.parseParam)
  }
}
