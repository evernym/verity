package com.evernym.verity.agentmsg.msgpacker

import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.wallet.{PackMsg, PackedMsg, UnpackMsg, UnpackedMsg}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.verity.vault.{KeyParam, WalletAPIParam}
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

  override def packAsync(msg: String,
                         recipVerKeyParams: Set[KeyParam],
                         senderVerKeyParam: Option[KeyParam])
                        (implicit wap: WalletAPIParam, walletAPI: WalletAPI): Future[PackedMsg] = {
    walletAPI.executeAsync[PackedMsg](PackMsg(msg.getBytes, recipVerKeyParams, senderVerKeyParam))
  }

  override def unpackAsync(msg: Array[Byte],
                           fromVerKeyParam: Option[KeyParam],
                           unpackParam: UnpackParam)
                          (implicit wap: WalletAPIParam, walletAPI: WalletAPI): Future[AgentBundledMsg] = {
    walletAPI.executeAsync[UnpackedMsg](UnpackMsg(msg)).map { um =>
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
