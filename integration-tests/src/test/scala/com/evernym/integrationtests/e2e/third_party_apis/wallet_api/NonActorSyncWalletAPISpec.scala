package com.evernym.integrationtests.e2e.third_party_apis.wallet_api

import com.evernym.verity.vault.wallet_api.base.NonActorClientWalletAPISpec

/**
 * this is an integration test and it depends on mysql based wallet storage
 * See devlab README to see how to have a local mysql DB available
 */

//NOTE: this one exercises "SYNC wallet api"
class NonActorSyncWalletAPISpec
  extends NonActorClientWalletAPISpec
    with MySqlWalletAPISpec {

  val totalUsers: Int = 1000

  "WalletService" - {
    "when tried to setup lots of user wallets concurrently" - {
      "should be successful" in {
        startUserWalletSetupWithSyncAPI()
        waitForAllResponses()
      }
    }
  }
}