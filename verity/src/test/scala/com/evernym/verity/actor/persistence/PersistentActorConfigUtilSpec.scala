package com.evernym.verity.actor.persistence

import com.evernym.verity.actor.testkit.TestAppConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers


class PersistentActorConfigUtilSpec extends AnyFreeSpec with Matchers {
  "PersistentActorConfigUtil" - {
    "getBackoffStrategy should find strategy in base when available" in {
      val configStr ="""
         verity.persistent-actor.base.supervisor {
            enabled = true
            backoff {
              strategy = onStop
              min-seconds = 3
              max-seconds = 20
              random-factor = 0
            }
        }
      """


      val appConfig = TestAppConfig(Some(ConfigFactory.parseString(configStr)), true)

      val v = PersistentActorConfigUtil.getBackoffStrategy(
        appConfig,
        "DEFAULTVAL",
        "verity.persistent-actor.base",
        "typeA"
      )

      v should not be empty
      v shouldBe "onStop"
    }



  }

}
