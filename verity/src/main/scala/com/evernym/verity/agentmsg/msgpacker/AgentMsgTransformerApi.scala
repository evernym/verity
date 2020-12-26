package com.evernym.verity.agentmsg.msgpacker

import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_MSG_PACK}
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.util.JsonUtil.getDeserializedJson
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.verity.vault.{KeyInfo, WalletAPIParam}
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future


/**
 * this object contains all supported msg transformers ('msgPackTransformer', 'indyPackTransformer' etc)
 * and takes care of choosing correct one (either based on given input or otherwise)
 */
object AgentMsgTransformerApi {

  val logger: Logger = getLoggerByName("AgentMsgTransformerApi")

  private val msgPackTransformer: MsgTransformer = new MsgPackTransformer
  private val indyPackTransformer: MsgTransformer = new IndyPackTransformer

  def msgTransformer(mpf: MsgPackFormat): MsgTransformer = {
    mpf match {
      case MPF_MSG_PACK   => msgPackTransformer
      case MPF_INDY_PACK  => indyPackTransformer
      case _ => throw new RuntimeException("given msg-pack format is not supported: " + mpf)
    }
  }

  def pack(mpf: MsgPackFormat,
           msg: String,
           recipVerKeys: Set[KeyInfo],
           senderVerKey: Option[KeyInfo])(implicit wap: WalletAPIParam, walletAPI: WalletAPI): PackedMsg = {
    msgTransformer(mpf).pack(msg, recipVerKeys, senderVerKey)
  }

  def unpack(msg: Array[Byte],
             fromVerKeyOpt: Option[KeyInfo],
             unpackParam: UnpackParam = UnpackParam())(implicit wap: WalletAPIParam, walletAPI: WalletAPI): AgentMsgWrapper = {

    val (transformer, fromVerKey) = if (isIndyPacked(msg)) {
      (indyPackTransformer, None)
    } else {
      (msgPackTransformer, fromVerKeyOpt)
    }

    val unpackedMsg = transformer.unpack(msg, fromVerKey, unpackParam)
    AgentMsgWrapper(transformer.msgPackFormat, unpackedMsg)
  }

  def unpackAsync(msg: Array[Byte],
                  fromVerKeyOpt: Option[KeyInfo],
                  unpackParam: UnpackParam = UnpackParam())
                 (implicit wap: WalletAPIParam, walletAPI: WalletAPI): Future[AgentMsgWrapper] = {

    val (transformer, fromVerKey) = if (isIndyPacked(msg)) {
      (indyPackTransformer, None)
    } else {
      (msgPackTransformer, fromVerKeyOpt)
    }

    transformer.unpackAsync(msg, fromVerKey, unpackParam).map { unpackedMsg =>
      AgentMsgWrapper(transformer.msgPackFormat, unpackedMsg)
    }
  }


  //set of keys to be present in any indy packed json message
  val indyPackedJsonRequiredKeys = Set("ciphertext")

  def isIndyPacked(msg: Array[Byte]): Boolean = {
    getDeserializedJson(msg).exists { jsObj =>
      val isValidIndyPackedMsg = indyPackedJsonRequiredKeys.forall(k => jsObj.has(k))
      if (! isValidIndyPackedMsg) {
        logger.debug(
          s"given binary msg successfully deserialized as json, " +
            s"but it wasn't a valid indy packed msg either:\n" +
            s"binary msg: ${msg.mkString(",")}, \n" +
            s"jsonMsg: ${jsObj.toString}")
      }
      isValidIndyPackedMsg
    }
  }

}