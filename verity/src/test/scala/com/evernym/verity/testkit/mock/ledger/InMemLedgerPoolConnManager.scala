package com.evernym.verity.testkit.mock.ledger

import com.evernym.verity.actor.testkit.actor.MockLedgerTxnExecutor
import com.evernym.verity.ledger.{LedgerPoolConnManager, LedgerTxnExecutor}
import com.evernym.verity.vault.wallet_api.WalletAPI

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

class InMemLedgerPoolConnManager(val executionContext: ExecutionContext, txnExecutor: Option[LedgerTxnExecutor] = None)
                                (implicit executor: ExecutionContextExecutor)
  extends LedgerPoolConnManager {

  def this(executionContext: ExecutionContext)(implicit executor: ExecutionContextExecutor) =  {
    this(executionContext, Some(new MockLedgerTxnExecutor(executionContext)))
  }

  val ledgerTxnExecutor: LedgerTxnExecutor = {
    txnExecutor.getOrElse(new MockLedgerTxnExecutor(executionContext))
  }

  override def open(): Unit = ()

  override def close(): Unit = ()

  override def isConnected: Boolean = true

  override def deletePoolLedgerConfig(): Unit = ()

  override def txnExecutor(walletAPI: Option[WalletAPI]): LedgerTxnExecutor = ledgerTxnExecutor

  override def connHandle: Option[Int] = Some(10)
}