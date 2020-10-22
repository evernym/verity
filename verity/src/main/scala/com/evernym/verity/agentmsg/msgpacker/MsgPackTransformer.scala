package com.evernym.verity.agentmsg.msgpacker

import java.util.concurrent.ExecutionException

import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.Status._
import com.evernym.verity.actor.agent.MsgPackVersion
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.engine.VerKey
import com.evernym.verity.util.MessagePackUtil
import com.typesafe.scalalogging.Logger
import org.hyperledger.indy.sdk.InvalidStructureException
import org.hyperledger.indy.sdk.crypto.Crypto
import org.hyperledger.indy.sdk.wallet.Wallet

/**
 * this transformer uses 'MessagePack' (https://msgpack.org/index.html) for 'pack' and 'unpack' functions
 * @param msgPackVersion
 */
class MsgPackTransformer(val msgPackVersion: MsgPackVersion)
  extends MsgTransformer {

  val logger: Logger = getLoggerByClass(classOf[MsgPackTransformer])

  override  def pack(wallet: Wallet, msg: String, recipVerKeys: Set[VerKey],
                     senderVerKey: Option[VerKey], packParam: PackParam): PackedMsg = {

    val msgBytes = MessagePackUtil.convertJsonStringToPackedMsg(msg)
    val recipKey = recipVerKeys.head
    val cryptoBoxBytes = senderVerKey match {
      case None             => Crypto.anonCrypt(recipKey, msgBytes).get
      case Some(senderKey)  => Crypto.authCrypt(wallet, senderKey, recipKey, msgBytes).get
    }
    PackedMsg(cryptoBoxBytes)
  }

  override def unpack(wallet: Wallet, msg: Array[Byte], fromVerKey: Option[VerKey],
                      unpackParam: UnpackParam): AgentBundledMsg = {
    try {
      val (decryptedMsg, senderVerKey) = if (unpackParam.isAnonCryptedMsg) {
        val parsedResult = Crypto.anonDecrypt(wallet, fromVerKey.get, msg).get
        (parsedResult, None)
      } else {
        val parsedResult = Crypto.authDecrypt(wallet, fromVerKey.get, msg).get
        (parsedResult.getDecryptedMessage, Option(parsedResult.getVerkey))
      }
      val msgUnpacked = MessagePackUtil.convertPackedMsgToJsonString(decryptedMsg)
      val unpackedMsg = UnpackedMsg(msgUnpacked, senderVerKey, None)
      AgentMsgParseUtil.parse(unpackedMsg, unpackParam.parseParam)
    } catch {
      case e: ExecutionException =>
        e.getCause match {
          case _: InvalidStructureException =>
            throw new BadRequestErrorException(INVALID_VALUE.statusCode,
              Option("invalid sealed/encrypted box"))
          case _: Exception =>
            throw new BadRequestErrorException(UNHANDLED.statusCode,
              Option("unhandled error while unsealing/decrypting msg"))
        }
      case e: Exception =>
        throw new BadRequestErrorException(UNHANDLED.statusCode,
          Option("unhandled error while unsealing/decrypting msg"), errorDetail=Some(e))
    }
  }

}
