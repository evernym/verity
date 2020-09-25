package com.evernym.verity.agentmsg.msgpacker

import java.util.concurrent.ExecutionException

import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.Status._
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.engine.{MsgPackVersion, VerKey}
import com.evernym.verity.util.JsonUtil.jsonArray
import com.typesafe.scalalogging.Logger
import org.hyperledger.indy.sdk.InvalidStructureException
import org.hyperledger.indy.sdk.crypto.Crypto
import org.hyperledger.indy.sdk.wallet.{Wallet, WalletItemNotFoundException}

/**
 * this uses 'indy' (https://github.com/hyperledger/indy-sdk/blob/master/libindy/src/api/crypto.rs)
 * for 'pack' and 'unpack' functions
 * @param msgPackVersion
 */
class IndyPackTransformer(val msgPackVersion: MsgPackVersion)
  extends MsgTransformer {

  val logger: Logger = getLoggerByClass(classOf[IndyPackTransformer])

  override def pack(wallet: Wallet, msg: String, recipVerKeys: Set[VerKey],
                    senderVerKey: Option[VerKey], packParam: PackParam): PackedMsg = {
    val recipKeysJson = jsonArray(recipVerKeys)
    val cryptoBoxBytes = senderVerKey match {
      case None =>
        Crypto.packMessage(wallet, recipKeysJson, null, msg.getBytes).get
      case Some(senderKey) =>
        Crypto.packMessage(wallet, recipKeysJson, senderKey, msg.getBytes).get
    }
    PackedMsg(cryptoBoxBytes)
  }

  override def unpack(wallet: Wallet, msg: Array[Byte], fromVerKey: Option[VerKey],
                      unpackParam: UnpackParam): AgentBundledMsg = {
    try {
      val binaryMsg = Crypto.unpackMessage(wallet, msg).get
      val jsonStringMsg = new String(binaryMsg)
      val resultMap = DefaultMsgCodec.fromJson[Map[String,String]](jsonStringMsg)
      val msgJsonString = resultMap("message")
      val senderVerKeyOpt = resultMap.get("sender_verkey")
      val recipVerKeyOpt = resultMap.get("recipient_verkey")
      val unpackedMsg = UnpackedMsg(msgJsonString, senderVerKeyOpt, recipVerKeyOpt)
      AgentMsgParseUtil.parse(unpackedMsg, unpackParam.parseParam)
    } catch {
      case e: BadRequestErrorException => throw e
      case e: ExecutionException =>
        e.getCause match {
          case e: WalletItemNotFoundException =>
            throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option(e.getMessage))
          case _: InvalidStructureException =>
            throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option("invalid packed message"))
          case _: Exception =>
            throw new BadRequestErrorException(UNHANDLED.statusCode,
              Option("unhandled error while unpacking message"))
        }
      case _: Exception =>
        throw new BadRequestErrorException(UNHANDLED.statusCode,
          Option("unhandled error while unpacking message"))
    }
  }
}
