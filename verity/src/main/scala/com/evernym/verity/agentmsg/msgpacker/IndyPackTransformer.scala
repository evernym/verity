package com.evernym.verity.agentmsg.msgpacker

import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.wallet.{PackedMsg, UnpackedMsg}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.verity.vault.{KeyInfo, WalletAPIParam}
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

  override def pack(msg: String,
                    recipVerKeys: Set[KeyInfo],
                    senderVerKey: Option[KeyInfo],
                    packParam: PackParam)
                   (implicit wap: WalletAPIParam, walletAPI: WalletAPI): PackedMsg = {
    walletAPI.packMessage(msg.getBytes, recipVerKeys, senderVerKey)
  }

  override def unpack(msg: Array[Byte], fromKeyInfo: Option[KeyInfo],
                      unpackParam: UnpackParam)(implicit wap: WalletAPIParam, walletAPI: WalletAPI)
  : AgentBundledMsg = {
    val unpackedMsg = walletAPI.unpackMessage(msg)
    AgentMsgParseUtil.parse(unpackedMsg, unpackParam.parseParam)
  }

  override def unpackAsync(msg: Array[Byte], fromKeyInfo: Option[KeyInfo], unpackParam: UnpackParam)
                          (implicit wap: WalletAPIParam, walletAPI: WalletAPI): Future[AgentBundledMsg] = {
    walletAPI.unpackMessageAsync(msg).map { unpackedMsg =>
      AgentMsgParseUtil.parse(unpackedMsg, unpackParam.parseParam)
    }
  }
}
