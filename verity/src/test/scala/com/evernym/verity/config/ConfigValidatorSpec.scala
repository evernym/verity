package com.evernym.verity.config

import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.actor.testkit.{AkkaTestBasic, CommonSpecUtil, TestAppConfig}
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.config.validator.base.ConfDetail
import com.evernym.verity.config.validator.{RequiredConfigValidator, RequiredConfigValidatorBase, ResourceUsageRuleConfigValidator}
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}


class ConfigValidatorSpec extends BasicSpec with CommonSpecUtil {

  lazy val testAppConfig: AppConfig = new TestAppConfig()
  override def appConfig: AppConfig = testAppConfig

  class TestReqConfigValidator (val config: Config) extends RequiredConfigValidatorBase {
    override val configsToBeValidated: Set[ConfDetail] = new RequiredConfigValidator(config).configsToBeValidated ++ Set (
      ConfDetail(HTTP_SSL_PORT)
    )
  }
  lazy val testReqConfigValidator = new TestReqConfigValidator(ConfigFactory.load)

  "Config Validators" - {
    "when asked to validate configs" - {
      "should validate it successfully" in {
        val conf = ConfigFactory.load()
        val akkaConf = AkkaTestBasic.getConfig()
        val finalConf = conf.withFallback(akkaConf)
        new TestAppConfig(Option(finalConf))
        intercept[BadRequestErrorException] {
          testReqConfigValidator.performConfigValidation()
        }
      }
    }

    "when asked to validate token" - {
      "should return proper results for it" in {
        val conf = ConfigFactory.load()
        val resourceUsageRuleConfValidator = new ResourceUsageRuleConfigValidator(conf)

        resourceUsageRuleConfValidator.isValidToken("global") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("192.168.0.1") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("129.0.0.1") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/1") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/2") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/31") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/32") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/0") shouldBe true // It is valid range
        resourceUsageRuleConfValidator.isValidToken("122.175.56.232/32") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("122.175.56.232/1") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("122.175.56.232/10") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("52.32.27.134/32") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("52.32.27.134/0") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("52.32.27.134/1") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("52.32.27.134/10") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("52.32.27.13/10") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("owner-EMmo7oSqQk1twmgLDRNjzC") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("owner-8HH5gYEeNc3z7PYXmd54d4x6qAfCNrqQqEB3nS7Zfu7K") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("counterparty-EMmo7oSqQk1twmgLDRNjzC") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("counterparty-8HH5gYEeNc3z7PYXmd54d4x6qAfCNrqQqEB3nS7Zfu7K") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("owner-*") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("counterparty-*") shouldBe true

        resourceUsageRuleConfValidator.isValidToken("local") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("global1") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("global/1") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("global*") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("global-*") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("11111") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.234") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.2.34") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("301.2.3.4") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("301.2.3.4/1") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("52.32.27.134/032") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("52.32.27.134/000") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/asgfajh/1.2.3.4") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("abcd") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("abcd/") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("abcd/xyz") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("abcd/12") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("abcd/abcd") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("abcd/12/abcd/12") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("abcd") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("126378abc") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("abc126378") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("126378-abc") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("abc-126378-abc") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("123-126378-123") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("-------") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("126378.abc") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("126378/.abc") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("/126378/.abc") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("///abcd") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("192.a.1.3.4") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("xyz/abc") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/32/1.2.3.4") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/1.2.3.4/32") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/32/1.2.3.4/32") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("...32") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("....32") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/1.2.3.4") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("/1.2.3.4") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("/1.2.3.4/32") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("32/1.2.3.4") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/32/") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/-32") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/abcd") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("%^%&^$$#$#$") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4////") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/33") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("122.175.56.232/64") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("52.32.27.134/44") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("999.999.999.999/999") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("999.999.999.999/1") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.999.1.999/1") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("10000.999.1.999/11111") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("10000.999.1.999/0000") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("52.32.27.134/0032") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/10000") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4///!!!") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/&83**!!!") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4 / &83**!!!") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4 / 30") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4 /32") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("27356278@") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("%^%&^$$#$#$") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("$$$$$$$`~*") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("******") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("??????") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("%%%%%%") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("######") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("(((())))") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("somebody-EMmo7oSqQk1twmgLDRNjzC") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("somebody-8HH5gYEeNc3z7PYXmd54d4x6qAfCNrqQqEB3nS7Zfu7K") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("EMmo7oSqQk1twmgLDRNjzC") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("8HH5gYEeNc3z7PYXmd54d4x6qAfCNrqQqEB3nS7Zfu7K") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("-EMmo7oSqQk1twmgLDRNjzC") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("-8HH5gYEeNc3z7PYXmd54d4x6qAfCNrqQqEB3nS7Zfu7K") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("owner-EMmo7oSqQk1twmgLDRNj") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("owner-EMmo7oSqQk1twmgLDRNjzC2k") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("counterparty-EMmo7oSqQk1twmgLDRNj") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("counterparty-EMmo7oSqQk1twmgLDRNjzC2k") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("owner-8HH5gYEeNc3z7PYXmd54d4x6qAfCNrqQqEB3nS7Zfu") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("owner-8HH5gYEeNc3z7PYXmd54d4x6qAfCNrqQqEB3nS7Zfu7KmY") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("counterparty-8HH5gYEeNc3z7PYXmd54d4x6qAfCNrqQqEB3nS7Zfu") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("counterparty-8HH5gYEeNc3z7PYXmd54d4x6qAfCNrqQqEB3nS7Zfu7KmY") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("owner_EMmo7oSqQk1twmgLDRNjzC") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("counterparty_EMmo7oSqQk1twmgLDRNjzC") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("ownerEMmo7oSqQk1twmgLDRNjzC") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("counterpartyEMmo7oSqQk1twmgLDRNjzC") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("owner-EMmo7oSq-k1twmgLDRNjzC") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("owner-8HH5gYEeNc3z7PYXmd5+d4x6qAfCNrqQqEB3nS7Zfu7K") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("counterparty-EMmo7oSqQk1t_mgLDRNjzC") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("owner-") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("owner") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("counterparty-") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("counterparty") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("owner-**") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("counterparty-**") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("owner-?") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("counterparty-?") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("owner-?*") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("counterparty-*?") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("owner*") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("counterparty*") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("owner_*") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("counterparty_*") shouldBe false
      }
    }
  }
}
