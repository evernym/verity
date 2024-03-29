package com.evernym.verity.agentmsg.msgpacker

import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.actor.wallet.{LegacyPackMsg, LegacyUnpackMsg, PackedMsg, UnpackedMsg}
import com.evernym.verity.util.MessagePackUtil
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.verity.vault.{KeyParam, WalletAPIParam}
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}

/**
 * this transformer uses 'MessagePack' (https://msgpack.org/index.html) for 'pack' and 'unpack' functions
 */
class MsgPackTransformer
  extends MsgTransformer {

  val msgPackFormat: MsgPackFormat = MPF_MSG_PACK

  val logger: Logger = getLoggerByClass(classOf[MsgPackTransformer])

  override def packAsync(msg: String,
                         recipVerKeyParams: Set[KeyParam],
                         senderVerKeyParam: Option[KeyParam])
                        (implicit wap: WalletAPIParam, walletAPI: WalletAPI): Future[PackedMsg] = {
    val msgBytes = MessagePackUtil.convertJsonStringToPackedMsg(msg)
    walletAPI.executeAsync[PackedMsg](LegacyPackMsg(msgBytes, recipVerKeyParams, senderVerKeyParam))
  }

  override def unpackAsync(msg: Array[Byte],
                           fromVerKeyParam: Option[KeyParam],
                           unpackParam: UnpackParam)
                          (implicit wap: WalletAPIParam, walletAPI: WalletAPI, ec: ExecutionContext): Future[AgentBundledMsg] = {

    walletAPI.executeAsync[UnpackedMsg](LegacyUnpackMsg(msg, fromVerKeyParam, unpackParam.isAnonCryptedMsg)).map { um =>
      prepareAgentBundledMsg(um, unpackParam)
    }
  }

  private def prepareAgentBundledMsg(um: UnpackedMsg, unpackParam: UnpackParam): AgentBundledMsg = {
    val msgUnpacked = MessagePackUtil.convertPackedMsgToJsonString(um.msg)
    val unpackedMsg = UnpackedMsg(msgUnpacked, um.senderVerKey, None)
    AgentMsgParseUtil.parse(unpackedMsg, unpackParam.parseParam)
  }
}
