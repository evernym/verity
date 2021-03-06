package com.evernym.verity.libindy.wallet.operation_executor

import java.util.concurrent.ExecutionException

import com.evernym.verity.Exceptions.{BadRequestErrorException, InternalServerErrorException}
import com.evernym.verity.Status.{ALREADY_EXISTS, INVALID_VALUE, UNHANDLED}
import com.evernym.verity.actor.wallet.{CreateDID, CreateNewKey, GetVerKeyResp, NewKeyCreated, StoreTheirKey, TheirKeyStored}
import com.evernym.verity.ExecutionContextProvider.walletFutureExecutionContext
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.libindy.ledger.IndyLedgerPoolConnManager
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.vault.WalletExt
import org.hyperledger.indy.sdk.InvalidStructureException
import org.hyperledger.indy.sdk.did.{Did, DidJSONParameters}
import org.hyperledger.indy.sdk.wallet.WalletItemAlreadyExistsException

import scala.concurrent.Future

object DidOpExecutor extends OpExecutorBase {

  def getVerKey(did: DID,
                getKeyFromPool: Boolean,
                ledgerPoolManager: Option[LedgerPoolConnManager])(implicit we: WalletExt): Future[GetVerKeyResp] = {
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

  def handleCreateDID(d: CreateDID)(implicit we: WalletExt): Future[NewKeyCreated] = {
    val didJson = s"""{"crypto_type": "${d.keyType}"}"""
    Did.createAndStoreMyDid(we.wallet, didJson)
      .map(r => NewKeyCreated(r.getDid, r.getVerkey))
  }

  def handleCreateNewKey(cnk: CreateNewKey)(implicit we: WalletExt): Future[NewKeyCreated] = {
    try {
      val DIDJson = new DidJSONParameters.CreateAndStoreMyDidJSONParameter(
        cnk.DID.orNull, cnk.seed.orNull, null, null)
      Did.createAndStoreMyDid(we.wallet, DIDJson.toJson)
      .map { r =>
        NewKeyCreated(r.getDid, r.getVerkey)
      }
    } catch {
      case e: ExecutionException =>
        e.getCause match {
          case _: InvalidStructureException =>
            throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option(e.getMessage))
          case _: Exception =>
            throw new InternalServerErrorException(
              UNHANDLED.statusCode, Option("unhandled error while creating new key"))
        }
      case _: Exception =>
        throw new BadRequestErrorException(UNHANDLED.statusCode, Option("unhandled error while creating new key"))
    }
  }

  def handleStoreTheirKey(stk: StoreTheirKey)(implicit we: WalletExt): Future[TheirKeyStored] = {
    try {
      val DIDJson = s"""{\"did\":\"${stk.theirDID}\",\"verkey\":\"${stk.theirDIDVerKey}\"}"""
      Did.storeTheirDid(we.wallet, DIDJson)
      .map(_ =>TheirKeyStored(stk.theirDID, stk.theirDIDVerKey))
    } catch {
      case e: Exception if stk.ignoreIfAlreadyExists && e.getCause.isInstanceOf[WalletItemAlreadyExistsException] =>
        Future(TheirKeyStored(stk.theirDID, stk.theirDIDVerKey))
      case e: Exception if e.getCause.isInstanceOf[WalletItemAlreadyExistsException] =>
        throw new BadRequestErrorException(
          ALREADY_EXISTS.statusCode, Option("'their' pw keys are already in the wallet"))
      case _: Exception =>
        throw new InternalServerErrorException(
          UNHANDLED.statusCode, Option("unhandled error while storing their key"))
    }
  }
}