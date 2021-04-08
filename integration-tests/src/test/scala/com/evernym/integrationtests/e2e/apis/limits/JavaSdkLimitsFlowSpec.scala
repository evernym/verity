package com.evernym.integrationtests.e2e.apis.limits

import com.evernym.integrationtests.e2e.apis.SdkFlowSpec
import com.evernym.integrationtests.e2e.env.IntegrationTestEnv
import com.evernym.integrationtests.e2e.tag.annotation.Integration

@Integration
class JavaSdkLimitsFlowSpec extends LimitsFlowSpec {

  override def specifySdkType(env: IntegrationTestEnv): IntegrationTestEnv =
    SdkFlowSpec.specifySdkForType("java", "0.4.1-fa1eaca7", env)
}
