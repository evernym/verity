package com.evernym.verity.libindy.wallet.api

import java.util.concurrent.ExecutionException

import com.evernym.verity.Exceptions
import com.evernym.verity.Exceptions.{BadRequestErrorException, InternalServerErrorException}
import com.evernym.verity.Status.{ALREADY_EXISTS, INVALID_VALUE, UNHANDLED}
import com.evernym.verity.actor.wallet.{CreateDID, CreateNewKey, NewKeyCreated, StoreTheirKey, TheirKeyStored}
import com.evernym.verity.constants.LogKeyConstants.LOG_KEY_ERR_MSG
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.libindy.wallet.api.WalletOpExecutor.logger
import com.evernym.verity.metrics.CustomMetrics.{AS_SERVICE_LIBINDY_WALLET_FAILED_COUNT, AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT}
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.vault.WalletExt
import org.hyperledger.indy.sdk.InvalidStructureException
import org.hyperledger.indy.sdk.did.{Did, DidJSONParameters}
import org.hyperledger.indy.sdk.wallet.WalletItemAlreadyExistsException

import scala.concurrent.Future

object DidOpExecutor extends FutureConverter {

  def handleCreateDID(d: CreateDID)(implicit we: WalletExt): Future[NewKeyCreated] = {
    asScalaFuture {
      val didJson = s"""{"crypto_type": "${d.keyType}"}"""
      Did.createAndStoreMyDid(we.wallet, didJson)
    }.map(r => NewKeyCreated(r.getDid, r.getVerkey))
  }

  def handleCreateNewKey(cnk: CreateNewKey)(implicit we: WalletExt): Future[NewKeyCreated] = {
    try {
      asScalaFuture {
        val DIDJson = new DidJSONParameters.CreateAndStoreMyDidJSONParameter(
          cnk.DID.orNull, cnk.seed.orNull, null, null)
        Did.createAndStoreMyDid(we.wallet, DIDJson.toJson)
      }.map { r =>
        MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT)
        NewKeyCreated(r.getDid, r.getVerkey)
      }
    } catch {
      case e: ExecutionException =>
        e.getCause match {
          case _: InvalidStructureException =>
            throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option(e.getMessage))
          case e: Exception =>
            logger.error("could not create new key", (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
            MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_FAILED_COUNT)
            throw new InternalServerErrorException(
              UNHANDLED.statusCode, Option("unhandled error while creating new key"))
        }
      case e: Exception =>
        logger.error("could not create new key", (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
        MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_FAILED_COUNT)
        throw new BadRequestErrorException(UNHANDLED.statusCode, Option("unhandled error while creating new key"))
    }
  }

  def handleStoreTheirKey(stk: StoreTheirKey)(implicit we: WalletExt): Future[TheirKeyStored] = {
    try {
      asScalaFuture {
        val DIDJson = s"""{\"did\":\"${stk.theirDID}\",\"verkey\":\"${stk.theirDIDVerKey}\"}"""
        Did.storeTheirDid(we.wallet, DIDJson)
      }.map { _ =>
        MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT)
        TheirKeyStored(stk.theirDID, stk.theirDIDVerKey)
      }
    } catch {
      case e: Exception if stk.ignoreIfAlreadyExists && e.getCause.isInstanceOf[WalletItemAlreadyExistsException] =>
        Future(TheirKeyStored(stk.theirDID, stk.theirDIDVerKey))
      case e: Exception if e.getCause.isInstanceOf[WalletItemAlreadyExistsException] =>
        logger.error("error while storing their key: " + Exceptions.getErrorMsg(e))
        throw new BadRequestErrorException(
          ALREADY_EXISTS.statusCode, Option("'their' pw keys are already in the wallet"))
      case e: Exception =>
        logger.error("could not store their key", ("their_did", stk.theirDID),
          (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
        MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_FAILED_COUNT)
        throw new InternalServerErrorException(
          UNHANDLED.statusCode, Option("unhandled error while storing their key"))
    }
  }
}
