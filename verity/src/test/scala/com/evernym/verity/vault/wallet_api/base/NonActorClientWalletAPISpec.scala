package com.evernym.verity.vault.wallet_api.base

import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Minutes, Seconds, Span}


trait NonActorClientWalletAPISpec
  extends ClientWalletAPISpecBase
    with Eventually {

  def startUserWalletSetupWithAsyncAPI(): Unit = {
    (1 to totalUsers).foreach { _ =>
      _baseWalletSetupWithAsyncAPI(walletAPI)
        .map { _ =>
          successResp.incrementAndGet()
        }.recover {
          case e: Throwable =>
            e.printStackTrace()
            failedResp.incrementAndGet()
        }
    }
  }

  def waitForAllResponses(): Unit = {
    //wait until all user wallet setup is completed
    eventually(timeout(Span(5, Minutes)), interval(Span(30, Seconds))) {
      totalRespCount shouldBe totalUsers
    }
    failedResp.get() shouldBe 0
  }
}
