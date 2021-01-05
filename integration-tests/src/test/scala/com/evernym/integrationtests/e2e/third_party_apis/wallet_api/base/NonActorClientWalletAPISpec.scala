package com.evernym.integrationtests.e2e.third_party_apis.wallet_api.base

import scala.concurrent.Future

trait NonActorClientWalletAPISpec
  extends ClientWalletAPISpecBase {

  def startUserWalletSetupWithSyncAPI(): Unit = {
    (1 to totalUsers).foreach { userId =>
      Future {
        _baseWalletSetupWithSyncAPI(userId, walletAPI)
        successResp += 1
      }
    }
  }

  def startUserWalletSetupWithAsyncAPI(): Unit = {
    (1 to totalUsers).foreach { userId =>
      _baseWalletSetupWithAsyncAPI(userId, walletAPI)
        .map { _ =>
          successResp += 1
        }
    }
  }

  def waitForAllResponses(): Unit = {
    //wait until all user wallet setup is completed
    while (successResp < totalUsers) {
      printExecutorMetrics()
      println("[current-progress] completed user wallet setup count         : " + successResp)
      Thread.sleep(5000)
    }
    println("[final-status] completed user wallet setup count               : " + successResp)
  }
}
