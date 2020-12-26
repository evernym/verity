package com.evernym.verity.agentmsg.msgpacker

import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.wallet.{PackedMsg, UnpackedMsg}
import com.evernym.verity.util.MessagePackUtil
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.verity.vault.{KeyInfo, WalletAPIParam}
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

/**
 * this transformer uses 'MessagePack' (https://msgpack.org/index.html) for 'pack' and 'unpack' functions
 */
class MsgPackTransformer
  extends MsgTransformer {

  val msgPackFormat: MsgPackFormat = MPF_MSG_PACK

  val logger: Logger = getLoggerByClass(classOf[MsgPackTransformer])

  override def pack(msg: String,
                    recipVerKeys: Set[KeyInfo],
                    senderVerKey: Option[KeyInfo])
                   (implicit wap: WalletAPIParam, walletAPI: WalletAPI): PackedMsg = {

    val msgBytes = MessagePackUtil.convertJsonStringToPackedMsg(msg)
    walletAPI.LEGACY_packMsg(msgBytes, recipVerKeys, senderVerKey)
  }

  override def unpack(msg: Array[Byte],
                      fromVerKey: Option[KeyInfo],
                      unpackParam: UnpackParam)
                     (implicit wap: WalletAPIParam, walletAPI: WalletAPI): AgentBundledMsg = {
    val result = walletAPI.LEGACY_unpackMsg(msg, fromVerKey, unpackParam.isAnonCryptedMsg)
    val msgUnpacked = MessagePackUtil.convertPackedMsgToJsonString(result.msg)
    val unpackedMsg = UnpackedMsg(msgUnpacked, result.senderVerKey, None)
    AgentMsgParseUtil.parse(unpackedMsg, unpackParam.parseParam)
  }

  override def unpackAsync(msg: Array[Byte], fromVerKey: Option[KeyInfo], unpackParam: UnpackParam)
                          (implicit wap: WalletAPIParam, walletAPI: WalletAPI): Future[AgentBundledMsg] = {

    walletAPI.LEGACY_unpackMsgAsync(msg, fromVerKey, unpackParam.isAnonCryptedMsg).map { r =>
      val msgUnpacked = MessagePackUtil.convertPackedMsgToJsonString(r.msg)
      val unpackedMsg = UnpackedMsg(msgUnpacked, r.senderVerKey, None)
      AgentMsgParseUtil.parse(unpackedMsg, unpackParam.parseParam)
    }
  }
}
