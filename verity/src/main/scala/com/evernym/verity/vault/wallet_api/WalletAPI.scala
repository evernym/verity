package com.evernym.verity.vault.wallet_api

import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.vault.service.AsyncToSync

import scala.concurrent.Future

trait WalletAPI extends AsyncToSync {

  final def executeSync[T](cmd: Any)(implicit wap: WalletAPIParam): T =
    convertToSyncReq(executeAsync(cmd))

  def executeAsync[T](cmd: Any)(implicit wap: WalletAPIParam): Future[T]

}

