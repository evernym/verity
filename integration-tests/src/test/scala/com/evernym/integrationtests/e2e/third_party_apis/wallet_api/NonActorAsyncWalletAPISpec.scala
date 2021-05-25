package com.evernym.integrationtests.e2e.third_party_apis.wallet_api

import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreAkkaEvents
import com.evernym.verity.vault.wallet_api.base.NonActorClientWalletAPISpec

/**
 * this is an integration test and it depends on mysql based wallet storage
 * See devlab README to have a local mysql DB available
 */

//NOTE: this one exercises "ASYNC wallet api" called from non actor code
class NonActorAsyncWalletAPISpec
  extends NonActorClientWalletAPISpec
    with MySqlWalletAPISpec {

  val totalUsers: Int = 1000

  "WalletService" - {
    s"when tried to setup $totalUsers user wallets concurrently " - {
      s"may take max ~5 min or so" - {
        s"but it should be successful" taggedAs UNSAFE_IgnoreAkkaEvents in {
          startUserWalletSetupWithAsyncAPI()
          waitForAllResponses()
        }
      }
    }
  }
}