package com.evernym.verity.vault.wallet_api.base

import java.util.concurrent.Executors

import scala.concurrent.{ExecutionContext, Future}

trait NonActorClientWalletAPISpec
  extends ClientWalletAPISpecBase {

  override implicit def testCodeExecutionContext: ExecutionContext = {
    //com.evernym.verity.ExecutionContextProvider.futureExecutionContext
    //comment above and uncomment/modify below to use custom thread pool
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))
  }

  def startUserWalletSetupWithSyncAPI(): Unit = {
    (1 to totalUsers).foreach { userId =>
      Future {
        try {
          _baseWalletSetupWithSyncAPI(userId, walletAPI)
          successResp += 1
        } catch {
          case e: RuntimeException =>
            e.printStackTrace()
            failedResp +=1
        }
      }
    }
  }

  def startUserWalletSetupWithAsyncAPI(): Unit = {
    (1 to totalUsers).foreach { userId =>
      _baseWalletSetupWithAsyncAPI(userId, walletAPI)
        .map { _ =>
          successResp += 1
        }.recover {
          case e: RuntimeException =>
            e.printStackTrace()
            failedResp += 1
        }
    }
  }

  def waitForAllResponses(): Unit = {
    //wait until all user wallet setup is completed
    while (totalRespCount < totalUsers) {
      printExecutorMetrics()
      println(s"[current-progress] success: $successResp, failed: $failedResp")
      failedResp shouldBe 0
      Thread.sleep(5000)
    }
    println(s"[final-status] success: $successResp, failed: $failedResp")
    failedResp shouldBe 0
  }
}
