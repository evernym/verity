package com.evernym.verity.agentmsg.msgpacker

import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.wallet.{LegacyUnpackMsg, PackedMsg, UnpackedMsg}
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
    val um = walletAPI.LEGACY_unpackMsg(msg, fromVerKey, unpackParam.isAnonCryptedMsg)
    prepareAgentBundledMsg(um, unpackParam)
  }

  override def unpackAsync(msg: Array[Byte], fromVerKey: Option[KeyInfo], unpackParam: UnpackParam)
                          (implicit wap: WalletAPIParam, walletAPI: WalletAPI): Future[AgentBundledMsg] = {

    walletAPI.executeAsync[UnpackedMsg](LegacyUnpackMsg(msg, fromVerKey, unpackParam.isAnonCryptedMsg)).map { um =>
      prepareAgentBundledMsg(um, unpackParam)
    }
  }

  private def prepareAgentBundledMsg(um: UnpackedMsg, unpackParam: UnpackParam): AgentBundledMsg = {
    val msgUnpacked = MessagePackUtil.convertPackedMsgToJsonString(um.msg)
    val unpackedMsg = UnpackedMsg(msgUnpacked, um.senderVerKey, None)
    AgentMsgParseUtil.parse(unpackedMsg, unpackParam.parseParam)
  }
}
