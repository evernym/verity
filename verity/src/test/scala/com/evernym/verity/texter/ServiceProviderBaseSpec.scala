package com.evernym.verity.texter

import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.testkit.BasicSpec



trait ServiceProviderBaseSpec extends BasicSpec {

  val appConfig = new TestAppConfig()

  def service: SMSServiceProvider

  commonTests()

  def commonTests(): Unit = {
    "Any service provider" - {
      "when asked to normalize phone number without leading plus symbol" - {
        "should return same number without any replacement" in {
          service.getNormalizedPhoneNumber("44123456780") shouldBe "44123456780"
        }
      }
      "when asked to normalize phone number with leading zero" - {
        "should return same number without any replacement" in {
          service.getNormalizedPhoneNumber("044123456780") shouldBe "044123456780"
        }
      }
      "when asked to normalize phone number with leading zeros" - {
        "should return same number without any replacement" in {
          service.getNormalizedPhoneNumber("0044123456780") shouldBe "0044123456780"
        }
      }
    }
  }
}
