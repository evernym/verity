package com.evernym.verity.texter

import akka.actor.ActorSystem
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.integration.base.PortProvider
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext.global

class InfoBipServiceProviderSpec
  extends ServiceProviderBaseSpec {

  val port: Int = PortProvider.getFreePort
  val config: TestAppConfig = TestAppConfig(
    Some(ConfigFactory.parseString(s"akka.remote.artery.canonical.port = $port")))

  val system: ActorSystem = ActorSystemVanilla("test", config.config)
  lazy val service = new InfoBipDirectSmsDispatcher(config, global)(system)

  "InfoBip service provider" - {

    "when asked to normalize phone number with leading plus symbol" - {
      "should be unchanged" in {
        service.getNormalizedPhoneNumber("+44123456780") shouldBe "+44123456780"
      }
    }
    "when asked to normalize phone number with leading plus symbol with a space after that" - {
      "should remove extra space" in {
        service.getNormalizedPhoneNumber("+ 44123456780") shouldBe "+44123456780"
      }
    }
    "when asked to normalize phone number with leading plus symbol with hyphen in between" - {
      "should remove hyphens" in {
        service.getNormalizedPhoneNumber("+44-123-456-780") shouldBe "+44123456780"
      }
    }
  }
}
