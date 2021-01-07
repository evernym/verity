package com.evernym.verity.libindy.wallet.operation_executor


import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.util.Util.jsonArray
import com.evernym.verity.util.UtilBase
import com.evernym.verity.ExecutionContextProvider.walletFutureExecutionContext
import com.evernym.verity.Status.{INVALID_VALUE, SIGNATURE_VERIF_FAILED, UNHANDLED}
import com.evernym.verity.actor.wallet.{GetVerKey, LegacyPackMsg, LegacyUnpackMsg, PackMsg, PackedMsg, SignMsg, UnpackMsg, UnpackedMsg, VerifySigResult}
import com.evernym.verity.protocol.engine.VerKey
import com.evernym.verity.vault.service.WalletMsgHandler.handleGetVerKey
import com.evernym.verity.vault.WalletExt
import com.evernym.verity.vault.service._
import org.hyperledger.indy.sdk.{InvalidParameterException, InvalidStructureException}
import org.hyperledger.indy.sdk.crypto.Crypto
import org.hyperledger.indy.sdk.wallet.WalletItemNotFoundException

import scala.concurrent.Future


object CryptoOpExecutor extends OpExecutorBase {

  def handleLegacyPackMsg(lpm: LegacyPackMsg, util: UtilBase, ledgerPoolManager: LedgerPoolConnManager)
                         (implicit we: WalletExt): Future[PackedMsg] = {
    val resp = for (
      recipKey      <- verKeyFuture(lpm.recipVerKeyParams, util, ledgerPoolManager).map(_.head);
      senderVerKey  <- verKeyFuture(lpm.senderVerKeyParam.toSet, util, ledgerPoolManager).map(_.headOption)
    ) yield {
      val fut = senderVerKey match {
        case None             => Crypto.anonCrypt(recipKey, lpm.msg)
        case Some(senderKey)  => Crypto.authCrypt(we.wallet, senderKey, recipKey, lpm.msg)
      }
      fut.map(r => PackedMsg(r))
    }
    resp.flatten
  }

  def handleLegacyUnpackMsg(lum: LegacyUnpackMsg, util: UtilBase, ledgerPoolManager: LedgerPoolConnManager)
                           (implicit we: WalletExt): Future[UnpackedMsg] = {

    val result = for (
      fromVerKey <- verKeyFuture(lum.fromVerKeyParam.toSet, util, ledgerPoolManager).map(_.head)
    ) yield {
      val result = if (lum.isAnonCryptedMsg) {
        Crypto.anonDecrypt(we.wallet, fromVerKey, lum.msg)
          .map(dm => UnpackedMsg(dm, None, None))
      } else {
        Crypto.authDecrypt(we.wallet, fromVerKey, lum.msg)
          .map(dr => UnpackedMsg(dr.getDecryptedMessage, Option(dr.getVerkey), None))
      }
      result.recover {
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
    // Question: Should JSON validation for msg happen here or is it left to libindy?
    // Question: Since libindy expects bytes, should msg be bytes and not string. This will
    // make API of pack and unpack consistent (pack takes input what unpack outputs)

    val result = for (
      recipKeys     <- verKeyFuture(pm.recipVerKeyParams, util, ledgerPoolManager);
      senderVerKey  <- verKeyFuture(pm.senderVerKeyParam.toSet, util, ledgerPoolManager).map(_.headOption)
    ) yield {
      val recipKeysJson = jsonArray(recipKeys)
      Crypto.packMessage(we.wallet, recipKeysJson, senderVerKey.orNull, pm.msg)
      .map(PackedMsg(_))
    }
    result.flatten
  }

  def handleUnpackMsg(um: UnpackMsg)(implicit we: WalletExt): Future[UnpackedMsg] = {
    Crypto.unpackMessage(we.wallet, um.msg)
      .map(r => UnpackedMsg(r, None, None))
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
    val verKeyFuture = handleGetVerKey(GetVerKey(smp.keyParam))
    verKeyFuture.flatMap { verKey =>
      Crypto.cryptoSign(we.wallet, verKey, smp.msg)
    }
  }

  def verifySig(verKey: VerKey, challenge: Array[Byte], signature: Array[Byte]): Future[VerifySigResult] = {
    val detail = s"challenge: '$challenge', signature: '$signature'"
    Crypto.cryptoVerify(verKey, challenge, signature)
      .map(VerifySigResult(_))
      .recover {
          case _@ (_:InvalidStructureException |_: InvalidParameterException) =>
            throw new BadRequestErrorException(SIGNATURE_VERIF_FAILED.statusCode,
              Option("signature verification failed"), Option(detail))
          case _: Exception => throw new BadRequestErrorException(SIGNATURE_VERIF_FAILED.statusCode,
            Option("unhandled error"), Option(detail))
      }
  }
}
