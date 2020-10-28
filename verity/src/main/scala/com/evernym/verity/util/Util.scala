package com.evernym.verity.util

import java.util.concurrent.ExecutionException

import com.evernym.verity.vault.WalletExt
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.libindy.IndyLedgerPoolConnManager
import com.evernym.verity.protocol.engine.{DID, VerKey}
import org.hyperledger.indy.sdk.did.Did

import scala.compat.java8.FutureConverters
import scala.concurrent.Future
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext

object Util extends UtilBase {

  override def getVerKey(did: DID, walletExt: WalletExt, getKeyFromPool: Boolean, poolConnManager: LedgerPoolConnManager): Future[VerKey] = {
    //TODO: need to fix this so that it can work directly with LedgerPoolConnManager interface
    // instead of relying on which implementation is provided
    val completableFuture = (getKeyFromPool, poolConnManager) match {
      case (true, ilp: IndyLedgerPoolConnManager) =>
        Did.keyForDid(ilp.poolConn_!, walletExt.wallet, did)
      case _ => Did.keyForLocalDid(walletExt.wallet, did)
    }
    FutureConverters.toScala(completableFuture)
      .recover{ case e: Exception => throw new ExecutionException(e)}
  }

}
