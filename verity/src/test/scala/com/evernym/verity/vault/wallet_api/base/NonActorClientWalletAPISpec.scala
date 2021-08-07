package com.evernym.verity.vault.wallet_api.base

import com.evernym.verity.util2.HasExecutionContextProvider
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Minutes, Span}

import scala.concurrent.ExecutionContext


trait NonActorClientWalletAPISpec
  extends ClientWalletAPISpecBase
    with HasExecutionContextProvider
    with Eventually {
  lazy implicit val executionContext: ExecutionContext = futureExecutionContext


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
    eventually(timeout(Span(10, Minutes)), interval(Span(100, Millis))) {
      totalRespCount shouldBe totalUsers
    }
    failedResp.get() shouldBe 0
  }
}
