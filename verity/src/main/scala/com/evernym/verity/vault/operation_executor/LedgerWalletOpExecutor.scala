package com.evernym.verity.vault.operation_executor

import com.evernym.verity.ledger.LedgerRequest
import com.evernym.verity.vault.WalletExt
import com.evernym.verity.util2.ExecutionContextProvider.walletFutureExecutionContext
import org.hyperledger.indy.sdk.ledger.Ledger.{multiSignRequest, signRequest}

import scala.concurrent.Future

object LedgerWalletOpExecutor extends OpExecutorBase {

  def handleSignRequest(submitterDid: String, reqDetail: LedgerRequest)
                       (implicit we: WalletExt): Future[LedgerRequest] = {
    signRequest(
      we.wallet,
      submitterDid,
      reqDetail.req
    ).map(reqDetail.prepared)
  }

  def handleMultiSignRequest(submitterDid: String, reqDetail: LedgerRequest)
                            (implicit we: WalletExt): Future[LedgerRequest] = {
    multiSignRequest(
      we.wallet,
      submitterDid,
      reqDetail.req
    ).map(reqDetail.prepared)
  }
}
