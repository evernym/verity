package com.evernym.verity.libindy.wallet.api

import com.evernym.verity.ledger.LedgerRequest
import com.evernym.verity.vault.WalletExt
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import org.hyperledger.indy.sdk.ledger.Ledger.signRequest

import scala.concurrent.Future

object LedgerWalletOpExecutor extends FutureConverter {

  def handleSignRequest(submitterDid: String, reqDetail: LedgerRequest)
                 (implicit we: WalletExt): Future[LedgerRequest] = {
    asScalaFuture {
      signRequest(
        we.wallet,
        submitterDid,
        reqDetail.req
      )
    }.map(reqDetail.prepared)
  }
}
