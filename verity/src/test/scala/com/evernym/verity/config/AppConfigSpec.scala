package com.evernym.verity.config
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.testkit.BasicSpec

class AppConfigSpec extends BasicSpec with ConfigUtilBaseSpec with CommonSpecUtil {

  val configFile = "verity/target/scala-2.12/test-classes/application.conf"

  "Config Wrapper" - {
    "when asked to refresh config" - {
      "should be able to refresh config changes" taggedAs (UNSAFE_IgnoreLog) in {
        assert(AppConfigWrapper.getConfigStringReq("verity.test.http-route-timeout-in-seconds") == "20")
        withChangedConfigFileContent(
          configFile,
          "http-route-timeout-in-seconds = 20",
          "http-route-timeout-in-seconds = 21", {
            AppConfigWrapper.reload()
            assert(AppConfigWrapper.getConfigStringReq("verity.test.http-route-timeout-in-seconds") == "21")
          })
      }
    }
  }

}
