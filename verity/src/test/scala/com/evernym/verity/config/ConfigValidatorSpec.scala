package com.evernym.verity.config

import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.actor.testkit.{AkkaTestBasic, CommonSpecUtil, TestAppConfig}
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.config.validator.base.ConfDetail
import com.evernym.verity.config.validator.{RequiredConfigValidator, RequiredConfigValidatorBase, ResourceUsageRuleConfigValidator}
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}


class ConfigValidatorSpec extends BasicSpec with CommonSpecUtil {

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

    "when asked to validate ip address" - {
      "should return proper results for it" in {
        val conf = ConfigFactory.load()
        val resourceUsageRuleConfValidator = new ResourceUsageRuleConfigValidator(conf)
        resourceUsageRuleConfValidator.isValidToken("11111") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.234") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.2.34") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("192.168.0.1") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("129.0.0.1") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/1") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/2") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/31") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/32") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/0") shouldBe true // It is valid range
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/asgfajh/1.2.3.4") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("abcd") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("abcd/") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("abcd/xyz") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("abcd/12") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("abcd/abcd") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("abcd/12/abcd/12") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("abcd") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("126378abc") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("abc126378") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("126378-abc") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("abc-126378-abc") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("123-126378-123") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("-------") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("126378.abc") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("126378/.abc") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("/126378/.abc") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("///abcd") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("192.a.1.3.4") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("xyz/abc") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/32/1.2.3.4") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/1.2.3.4/32") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/32/1.2.3.4/32") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("...32") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("....32") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/1.2.3.4") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("/1.2.3.4") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("/1.2.3.4/32") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("32/1.2.3.4") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/32/") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("1.2.3.4/-32") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("122.175.56.232/32") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("122.175.56.232/1") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("122.175.56.232/10") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("52.32.27.134/32") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("52.32.27.134/032") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("52.32.27.134/0") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("52.32.27.134/000") shouldBe false
        resourceUsageRuleConfValidator.isValidToken("52.32.27.134/1") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("52.32.27.134/10") shouldBe true
        resourceUsageRuleConfValidator.isValidToken("52.32.27.13/10") shouldBe true

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
      }
    }
  }
}
