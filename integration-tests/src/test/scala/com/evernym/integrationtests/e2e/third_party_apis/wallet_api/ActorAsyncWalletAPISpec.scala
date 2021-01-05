package com.evernym.integrationtests.e2e.third_party_apis.wallet_api

import com.evernym.integrationtests.e2e.third_party_apis.wallet_api.base.ActorClientWalletAPISpecBase

/**
 * this is an integration test and it depends on mysql based wallet storage
 * mysql can be installed by executing        : <repo-root>/devops/scripts/wallet-storage-mysql/install.sh
 * required tables can be setup by executing  : <repo-root>/devops/scripts/wallet-storage-mysql/clean-setup.sh
 */

//NOTE: this one exercises "ASYNC wallet api" from within actors
class ActorAsyncWalletAPISpec
  extends ActorClientWalletAPISpecBase {

  val totalUsers: Int = 1000

  "WalletService" - {
    "when tried to setup lots of user wallets concurrently" - {
      "should be successful" in {
        startUserWalletSetupWithAsyncAPI()
        waitForAllResponses()
      }
    }
  }
}
