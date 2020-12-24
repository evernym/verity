package com.evernym.verity.libindy.wallet.api


import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.util.Util.jsonArray
import com.evernym.verity.util.UtilBase
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.{INVALID_VALUE, UNHANDLED}
import com.evernym.verity.actor.wallet.{GetVerKey, LegacyPackMsg, LegacyUnpackMsg, PackMsg, PackedMsg, SignMsg, UnpackMsg, UnpackedMsg}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.protocol.engine.VerKey
import com.evernym.verity.vault.service.WalletMsgHandler.handleGetVerKey
import com.evernym.verity.vault.{GetVerKeyByDIDParam, KeyInfo, WalletExt}
import com.evernym.verity.vault.service._
import org.hyperledger.indy.sdk.InvalidStructureException
import org.hyperledger.indy.sdk.crypto.Crypto
import org.hyperledger.indy.sdk.wallet.WalletItemNotFoundException

import scala.concurrent.Future


object CryptoOpExecutor extends FutureConverter {

  private def verKeyFuture(keys: Set[KeyInfo], util: UtilBase, ledgerPoolManager: LedgerPoolConnManager)
                          (implicit we: WalletExt): Future[Set[VerKey]] = {
    Future.sequence {
      keys.map { rk =>
        rk.verKeyDetail match {
          case Left(vk) => Future(vk)
          case Right(gvk: GetVerKeyByDIDParam) =>
            WalletOpExecutor.getVerKey(gvk.did, gvk.getKeyFromPool, util, ledgerPoolManager)
        }
      }
    }
  }

  def handleLegacyPackMsg(pm: LegacyPackMsg, util: UtilBase, ledgerPoolManager: LedgerPoolConnManager)
                         (implicit we: WalletExt): Future[PackedMsg] = {
    val recipKeyFut = verKeyFuture(pm.recipVerKeys, util, ledgerPoolManager).map(_.head)
    val senderVerKeyFut = verKeyFuture(pm.senderVerKey.toSet, util, ledgerPoolManager).map(_.headOption)

    val resp = for (
      recipKey      <- recipKeyFut;
      senderVerKey  <- senderVerKeyFut
    ) yield {
      asScalaFuture {
        senderVerKey match {
          case None             => Crypto.anonCrypt(recipKey, pm.msg)
          case Some(senderKey)  => Crypto.authCrypt(we.wallet, senderKey, recipKey, pm.msg)
        }
      }.map{ r => PackedMsg(r) }
    }
    resp.flatten
  }

  def handleLegacyUnpackMsg(pm: LegacyUnpackMsg, util: UtilBase, ledgerPoolManager: LedgerPoolConnManager)
                           (implicit we: WalletExt): Future[UnpackedMsg] = {
    val fromVerKeyFut = verKeyFuture(pm.fromVerKey.toSet, util, ledgerPoolManager).map(_.head)

    val result = for (
      fromVerKey <- fromVerKeyFut
    ) yield {
      val result = if (pm.isAnonCryptedMsg) {
        asScalaFuture(Crypto.anonDecrypt(we.wallet, fromVerKey, pm.msg))
          .map((_, None))
      } else {
        asScalaFuture(Crypto.authDecrypt(we.wallet, fromVerKey, pm.msg))
          .map(r => (r.getDecryptedMessage, Option(r.getVerkey)))
      }
      result.map { case (decryptedMsg, senderVerKey) =>
        UnpackedMsg(decryptedMsg, senderVerKey, None)
      }.recover {
        case _: InvalidStructureException =>
          throw new BadRequestErrorException(INVALID_VALUE.statusCode,
            Option("invalid sealed/encrypted box"))
        case _: Exception =>
          throw new BadRequestErrorException(UNHANDLED.statusCode,
            Option("unhandled error while unsealing/decrypting msg"))
      }
    }
    result.flatten
  }

  def handlePackMsg(pm: PackMsg, util: UtilBase, ledgerPoolManager: LedgerPoolConnManager)
                   (implicit we: WalletExt): Future[PackedMsg] = {
    // Question: Should JSON validation happen for msg happen here or is it left to libindy?
    // Question: Since libindy expects bytes, should msg be bytes and not string. This will
    // make API of pack and unpack consistent (pack takes input what unpack outputs)

    val recipKeysFut = verKeyFuture(pm.recipVerKeys, util, ledgerPoolManager)
    val senderVerKeyFut = verKeyFuture(pm.senderVerKey.toSet, util, ledgerPoolManager).map(_.headOption)

    val result = for (
      recipKeys     <- recipKeysFut;
      senderVerKey  <- senderVerKeyFut
    ) yield {
      val recipKeysJson = jsonArray(recipKeys)
      asScalaFuture {
        Crypto.packMessage(we.wallet, recipKeysJson, senderVerKey.orNull, pm.msg)
      }.map(PackedMsg(_))
    }
    result.flatten
  }

  def handleUnpackMsg(um: UnpackMsg)(implicit we: WalletExt): Future[UnpackedMsg] = {
    asScalaFuture(Crypto.unpackMessage(we.wallet, um.msg))
      .map(r => {
        val binaryMsg = r
        val jsonStringMsg = new String(binaryMsg)
        val resultMap = DefaultMsgCodec.fromJson[Map[String, String]](jsonStringMsg)
        val msgJsonString = resultMap("message")
        val senderVerKeyOpt = resultMap.get("sender_verkey")
        val recipVerKeyOpt = resultMap.get("recipient_verkey")
        UnpackedMsg(msgJsonString, senderVerKeyOpt, recipVerKeyOpt)
      })
      .recover {
        case e: BadRequestErrorException => throw e
        case e: WalletItemNotFoundException =>
          throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option(e.getMessage))
        case _: InvalidStructureException =>
          throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option("invalid packed message"))
        case _: Exception =>
          throw new BadRequestErrorException(UNHANDLED.statusCode,
            Option("unhandled error while unpacking message"))
      }
  }

  def handleSignMsg(smp: SignMsg)(implicit wmp: WalletMsgParam, we: WalletExt): Future[Array[Byte]] = {
    val verKeyFuture = handleGetVerKey(GetVerKey(smp.keyInfo))
    val result = for (
      verKey <- verKeyFuture
    ) yield {
      asScalaFuture(Crypto.cryptoSign(we.wallet, verKey, smp.msg))
    }
    result.flatten
  }
}
