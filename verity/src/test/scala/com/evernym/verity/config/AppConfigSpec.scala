package com.evernym.verity.config
import com.evernym.verity.actor.testkit.{CommonSpecUtil, TestAppConfig}
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.testkit.BasicSpec

class AppConfigSpec extends BasicSpec with ConfigUtilBaseSpec with CommonSpecUtil {

  lazy val testAppConfig: AppConfig = new TestAppConfig()
  override def appConfig: AppConfig = testAppConfig

  val configFile = "verity/target/scala-2.12/test-classes/application.conf"

  "Config Wrapper" - {
    "when asked to refresh config" - {
      "should be able to refresh config changes" taggedAs (UNSAFE_IgnoreLog) in {
        assert(appConfig.getStringReq("verity.test.http-route-timeout-in-seconds") == "20")
        withChangedConfigFileContent(
          configFile,
          "http-route-timeout-in-seconds = 20",
          "http-route-timeout-in-seconds = 21", {
            appConfig.reload()
            assert(appConfig.getStringReq("verity.test.http-route-timeout-in-seconds") == "21")
          })
      }
    }
  }

}
