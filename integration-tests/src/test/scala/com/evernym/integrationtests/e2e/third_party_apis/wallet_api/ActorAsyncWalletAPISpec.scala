package com.evernym.integrationtests.e2e.third_party_apis.wallet_api

import com.evernym.verity.vault.wallet_api.base.ActorClientWalletAPISpecBase

/**
 * this is an integration test and it depends on mysql based wallet storage
 * See devlab README to see how to have a local mysql DB available
 */

//NOTE: this one exercises "ASYNC wallet api" from within actors
//TODO: This Async test fails most of the time with
// 'swallowing exception during message send akka.actor.dungeon.SerializationCheckFailedException'
// need to come back to it and see if we find/fix root cause
class ActorAsyncWalletAPISpec
  extends ActorClientWalletAPISpecBase
    with MySqlWalletAPISpec {

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
