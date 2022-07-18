package com.evernym.verity.vault.operation_executor

import java.util.concurrent.ExecutionException
import com.evernym.verity.util2.Exceptions.{BadRequestErrorException, InternalServerErrorException}
import com.evernym.verity.util2.Status.{ALREADY_EXISTS, INVALID_VALUE, UNHANDLED, logger}
import com.evernym.verity.actor.wallet.{CreateDID, CreateNewKey, GetVerKeyResp, NewKeyCreated, StoreTheirKey, TheirKeyStored}
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.vdrtools.ledger.IndyLedgerPoolConnManager
import com.evernym.verity.did.DidStr
import com.evernym.verity.util2.Exceptions
import com.evernym.verity.vault.WalletExt
import com.evernym.vdrtools.InvalidStructureException
import com.evernym.vdrtools.did.{Did, DidJSONParameters}
import com.evernym.vdrtools.wallet.{WalletItemAlreadyExistsException, WalletItemNotFoundException}
import org.json.JSONObject

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

object DidOpExecutor extends OpExecutorBase {

  def getVerKey(did: DidStr,
                getKeyFromPool: Boolean,
                ledgerPoolManager: Option[LedgerPoolConnManager])
               (implicit we: WalletExt, ec: ExecutionContext): Future[GetVerKeyResp] = {
    val result = (getKeyFromPool, ledgerPoolManager) match {
      case (true, Some(lpm: IndyLedgerPoolConnManager)) => Did.keyForDid(lpm.poolConn_!, we.wallet, did)
      case _ => Did.keyForLocalDid(we.wallet, did)
    }
    result
      .map(vk => GetVerKeyResp(vk))
      .recover {
         case e: Exception => throw new ExecutionException(e)
      }
  }

  def handleCreateDID(d: CreateDID)(implicit we: WalletExt, ec: ExecutionContext): Future[NewKeyCreated] = {
    val didJson = new JSONObject()
    didJson.put("crypto_type", d.keyType)
    didJson.put("ledger_type", d.ledgerPrefix)
    Did
      .createAndStoreMyDid(we.wallet, didJson.toString)
      .map(r => NewKeyCreated(r.getDid, r.getVerkey))
      .recover {
        case e: Throwable =>
          logger.error("error while creating new DID: " + Exceptions.getStackTraceAsSingleLineString(e))
          throw e
      }
  }

  def handleCreateNewKey(cnk: CreateNewKey)(implicit we: WalletExt, ec: ExecutionContext): Future[NewKeyCreated] = {
    try {
      val DIDJson = new DidJSONParameters.CreateAndStoreMyDidJSONParameter(
        cnk.DID.orNull, cnk.seed.orNull, null, null)
      Did
        .createAndStoreMyDid(we.wallet, DIDJson.toJson)
        .map( r => NewKeyCreated(r.getDid, r.getVerkey))
        .recover {
          case e: Throwable =>
            logger.error("error while creating new key: " + Exceptions.getStackTraceAsSingleLineString(e))
            throw e
        }
    } catch {
      case e: ExecutionException =>
        e.getCause match {
          case e: InvalidStructureException =>
            throw new BadRequestErrorException(
              INVALID_VALUE.statusCode,
              Option(e.getMessage),
              errorDetail = Option(Exceptions.getStackTraceAsSingleLineString(e)))
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

  def handleStoreTheirKey(stk: StoreTheirKey)(implicit we: WalletExt, ec: ExecutionContext): Future[TheirKeyStored] = {
    try {
      val DIDJson = s"""{\"did\":\"${stk.theirDID}\",\"verkey\":\"${stk.theirDIDVerKey}\"}"""
      Did.storeTheirDid(we.wallet, DIDJson)
      .map(_ =>TheirKeyStored(stk.theirDID, stk.theirDIDVerKey))
        .recover {
          case e: Throwable =>
            logger.error("error while storing their key: " + Exceptions.getStackTraceAsSingleLineString(e))
            throw e
        }
    } catch {
      case e: Exception if stk.ignoreIfAlreadyExists && e.getCause.isInstanceOf[WalletItemAlreadyExistsException] =>
        Future(TheirKeyStored(stk.theirDID, stk.theirDIDVerKey))
      case e: Exception if e.getCause.isInstanceOf[WalletItemAlreadyExistsException] =>
        throw new BadRequestErrorException(
          ALREADY_EXISTS.statusCode,
          Option("'their' pw keys are already in the wallet"),
          errorDetail = buildOptionErrorDetail(e))
      case e: Exception =>
        throw new InternalServerErrorException(
          UNHANDLED.statusCode,
          Option("unhandled error while storing their key"),
          errorDetail = buildOptionErrorDetail(e))
    }
  }
}