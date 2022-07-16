package com.evernym.verity.vault.operation_executor

import com.evernym.verity.util2.Exceptions.{BadRequestErrorException, InternalServerErrorException}
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.util.Util.jsonArray

import scala.concurrent.ExecutionContext
import com.evernym.verity.util2.Status.{INVALID_VALUE, SIGNATURE_VERIF_FAILED, UNHANDLED}
import com.evernym.verity.actor.wallet.{LegacyPackMsg, LegacyUnpackMsg, PackMsg, PackedMsg, SignMsg, SignedMsg, UnpackMsg, UnpackedMsg, VerifySigResult}
import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.util2.Exceptions
import com.evernym.verity.vault.service.WalletMsgHandler.handleGetVerKey
import com.evernym.verity.vault.WalletExt
import com.evernym.verity.vault.service._
import com.evernym.vdrtools.{InvalidParameterException, InvalidStructureException}
import com.evernym.vdrtools.crypto.Crypto
import com.evernym.vdrtools.wallet.WalletItemNotFoundException

import java.util.concurrent.ExecutionException
import scala.concurrent.Future


object CryptoOpExecutor extends OpExecutorBase {

  def handleLegacyPackMsg(lpm: LegacyPackMsg, ledgerPoolManager: Option[LedgerPoolConnManager])
                         (implicit we: WalletExt, ec: ExecutionContext): Future[PackedMsg] = {
    val resp = for (
      recipKeyResp      <- verKeyFuture(lpm.recipVerKeyParams, ledgerPoolManager).map(_.head);
      senderVerKeyResp  <- verKeyFuture(lpm.senderVerKeyParam.toSet, ledgerPoolManager).map(_.headOption)
    ) yield {
      val fut = senderVerKeyResp match {
        case None        => Crypto.anonCrypt(recipKeyResp.verKey, lpm.msg)
        case Some(gvkr)  => Crypto.authCrypt(we.wallet, gvkr.verKey, recipKeyResp.verKey, lpm.msg)
      }
      fut.map(r => PackedMsg(r))
    }
    resp.flatten
  }

  def handleLegacyUnpackMsg(lum: LegacyUnpackMsg, ledgerPoolManager: Option[LedgerPoolConnManager])
                           (implicit we: WalletExt, ec: ExecutionContext): Future[UnpackedMsg] = {

    val result = for (
      fromVerKeyResp <- verKeyFuture(lum.fromVerKeyParam.toSet, ledgerPoolManager).map(_.head)
    ) yield {
      val result = if (lum.isAnonCryptedMsg) {
        Crypto.anonDecrypt(we.wallet, fromVerKeyResp.verKey, lum.msg)
          .map(dm => UnpackedMsg(dm, None, None))
      } else {
        Crypto.authDecrypt(we.wallet, fromVerKeyResp.verKey, lum.msg)
          .map(dr => UnpackedMsg(dr.getDecryptedMessage, Option(dr.getVerkey), None))
      }
      result.recover {
        case e: InvalidStructureException =>
          throw new BadRequestErrorException(
            INVALID_VALUE.statusCode,
            Option("invalid sealed/encrypted box: " + e.getSdkMessage),
            errorDetail = buildOptionErrorDetail(e))
        case e: Exception =>
          throw new BadRequestErrorException(
            UNHANDLED.statusCode,
            Option("unhandled error while unsealing/decrypting msg: " + e.getMessage),
            errorDetail = buildOptionErrorDetail(e))
      }
    }
    result.flatten
  }

  def handlePackMsg(pm: PackMsg, ledgerPoolManager: Option[LedgerPoolConnManager])
                   (implicit we: WalletExt, ec: ExecutionContext): Future[PackedMsg] = {
    // Question: Should JSON validation for msg happen here or is it left to libindy?
    // Question: Since libindy expects bytes, should msg be bytes and not string. This will
    // make API of pack and unpack consistent (pack takes input what unpack outputs)
    val result = for (
      recipKeys     <- verKeyFuture(pm.recipVerKeyParams, ledgerPoolManager);
      senderVerKeyResp  <- verKeyFuture(pm.senderVerKeyParam.toSet, ledgerPoolManager).map(_.headOption)
    ) yield {
      val recipKeysJson = jsonArray(recipKeys.map(_.verKey))
      Crypto
        .packMessage(we.wallet, recipKeysJson, senderVerKeyResp.map(_.verKey).orNull, pm.msg)
        .map(PackedMsg(_))
    }
    result.flatten
  }

  def handleUnpackMsg(um: UnpackMsg)(implicit we: WalletExt, ec: ExecutionContext): Future[UnpackedMsg] = {
    Crypto
      .unpackMessage(we.wallet, um.msg)
      .map(r => UnpackedMsg(r, None, None))
      .recover {
        case e: WalletItemNotFoundException =>
          throw new BadRequestErrorException(
            INVALID_VALUE.statusCode,
            Option(e.getSdkMessage),
            errorDetail = Option(Exceptions.getStackTraceAsSingleLineString(e)))
        case e: InvalidStructureException =>
          throw new BadRequestErrorException(
            INVALID_VALUE.statusCode,
            Option("invalid packed message: " + e.getSdkMessage),
            errorDetail = buildOptionErrorDetail(e))
        case e: Exception =>
          throw new BadRequestErrorException(
            UNHANDLED.statusCode,
            Option("unhandled error while unpacking message: " + e.getMessage),
            errorDetail = buildOptionErrorDetail(e))
      }
  }

  def handleSignMsg(smp: SignMsg)(implicit wmp: WalletMsgParam, we: WalletExt, ec: ExecutionContext): Future[SignedMsg] = {
    val verKeyFuture = handleGetVerKey(smp.keyParam)
    verKeyFuture
      .flatMap { gvkr =>
        Crypto
          .cryptoSign(we.wallet, gvkr.verKey, smp.msg)
          .map(SignedMsg(_, gvkr.verKey))
      }.recover {
        case e: ExecutionException =>
          e.getCause match {
            case e: WalletItemNotFoundException =>
              throw new BadRequestErrorException(
                INVALID_VALUE.statusCode,
                Option(e.getMessage),
                errorDetail = Option(Exceptions.getStackTraceAsSingleLineString(e)))
            case e: Exception =>
              throw new InternalServerErrorException(
                UNHANDLED.statusCode,
                Option("unhandled error while creating new key"),
                errorDetail = buildOptionErrorDetail(e))
          }
        case e: Exception =>
          throw new BadRequestErrorException(
            UNHANDLED.statusCode,
            Option("unhandled error while creating new key"),
            errorDetail = buildOptionErrorDetail(e))
      }
  }

  def verifySig(verKey: VerKeyStr, challenge: Array[Byte], signature: Array[Byte])
               (implicit ec: ExecutionContext): Future[VerifySigResult] = {
    val detail = s"challenge: '$challenge', signature: '$signature'"
    Crypto
      .cryptoVerify(verKey, challenge, signature)
      .map(VerifySigResult(_))
      .recover {
          case e @ (_:InvalidStructureException |_: InvalidParameterException) =>
            throw new BadRequestErrorException(
              SIGNATURE_VERIF_FAILED.statusCode,
              Option("signature verification failed"),
              Option(detail),
              errorDetail = buildOptionErrorDetail(e))
          case e: Exception =>
            throw new BadRequestErrorException(
              SIGNATURE_VERIF_FAILED.statusCode,
              Option("unhandled error"),
              Option(detail),
              errorDetail = buildOptionErrorDetail(e))
      }
  }

  def verifySig(vs: VerifySigByVerKey)(implicit ec: ExecutionContext): Future[VerifySigResult] = {
    verifySig(vs.verKey, vs.challenge, vs.signature)
  }
}

case class VerifySigByVerKey(verKey: VerKeyStr, challenge: Array[Byte], signature: Array[Byte])
