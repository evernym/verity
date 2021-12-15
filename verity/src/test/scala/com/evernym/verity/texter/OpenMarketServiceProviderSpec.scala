package com.evernym.verity.texter

class OpenMarketServiceProviderSpec extends ServiceProviderBaseSpec {

  lazy val service = new OpenMarketDispatcherMEP(appConfig)

  "OpenMarket service provider" - {

    "when asked to normalize phone number with leading plus symbol" - {
      "should be able to replace plus with two leading zeros" in {
        service.getNormalizedPhoneNumber("+44123456780") shouldBe "0044123456780"
      }
    }
    "when asked to normalize phone number with leading plus symbol with a space after that" - {
      "should replace plus with two leading zeros and remove space" in {
        service.getNormalizedPhoneNumber("+ 44123456780") shouldBe "0044123456780"
      }
    }
    "when asked to normalize phone number with leading plus symbol with hyphen in between" - {
      "should replace plus with two leading zeros and remove hyphens" in {
        service.getNormalizedPhoneNumber("+44-123-456-780") shouldBe "0044123456780"
      }
    }
  }
}
