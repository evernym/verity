package com.evernym.integrationtests.e2e.third_party_apis.wallet_api

import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreAkkaEvents
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.vault.wallet_api.base.ActorClientWalletAPISpecBase

import scala.concurrent.ExecutionContext

/**
 * this is an integration test and it depends on mysql based wallet storage
 * See devlab README to have a local mysql DB available
 */

//NOTE: this one exercises "ASYNC wallet api" called from inside an actor
class ActorAsyncWalletAPISpec
  extends ActorClientWalletAPISpecBase
    with MySqlWalletAPISpec {

  val totalUsers: Int = 1000

  "WalletService" - {
    s"when tried to setup $totalUsers user wallets concurrently" - {
      s"may take max ~5 min or so" - {
        "but should be successful" taggedAs UNSAFE_IgnoreAkkaEvents in {
          startUserWalletSetupWithAsyncAPI()
          waitForAllResponses()
        }
      }
    }
  }
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
}
