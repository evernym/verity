package com.evernym.verity.testkit.mock.ledger

import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.ledger.{LedgerPoolConnManager, LedgerTxnExecutor}
import com.evernym.verity.vault.wallet_api.WalletAPI

import scala.concurrent.ExecutionContextExecutor

case class InitLedgerData(nymInitMap: Map[String, DidPair] = Map(), attrInitMap: Map[String, String] = Map())

class InMemLedgerPoolConnManager(txnExecutor: Option[LedgerTxnExecutor] = None)
                                (implicit executor: ExecutionContextExecutor)
  extends LedgerPoolConnManager {

  def this(initData: InitLedgerData)
          (implicit executor: ExecutionContextExecutor) {
    this(Some(new MockInMemLedgerTxnExecutor(initData)))
  }

  val ledgerTxnExecutor: LedgerTxnExecutor = {
    txnExecutor.getOrElse(new MockInMemLedgerTxnExecutor(InitLedgerData()))
  }

  override def open(): Unit = ()

  override def close(): Unit = ()

  override def isConnected: Boolean = true

  override def deletePoolLedgerConfig(): Unit = ()

  override def txnExecutor(walletAPI: Option[WalletAPI]): LedgerTxnExecutor = ledgerTxnExecutor

  override def connHandle: Option[Int] = Some(10)
}