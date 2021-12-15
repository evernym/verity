package com.evernym.verity.texter

class TwilioServiceProviderSpec extends ServiceProviderBaseSpec {

  lazy val service = new TwilioDispatcher(appConfig)

  "Twilio service provider" - {
    "when asked to normalize phone number with leading plus symbol" - {
      "should not replace any leading plus symbol" in {
        service.getNormalizedPhoneNumber("+44123456780") shouldBe "+44123456780"
      }
    }
    "when asked to normalize phone number with leading plus symbol with a space after that" - {
      "should remove plus space" in {
        service.getNormalizedPhoneNumber("+ 44123456780") shouldBe "+44123456780"
      }
    }
    "when asked to normalize phone number with leading plus symbol with hyphen in between" - {
      "should remove hyphen symbols" in {
        service.getNormalizedPhoneNumber("+44-123-456-780") shouldBe "+44123456780"
      }
    }
  }
}
