package com.evernym.integrationtests.e2e.apis.limits

import com.evernym.integrationtests.e2e.apis.SdkFlowSpec
import com.evernym.integrationtests.e2e.env.IntegrationTestEnv
import com.evernym.integrationtests.e2e.tag.annotation.Integration

@Integration
class PythonSdkLimitsFlowSpec extends LimitsFlowSpec {
  override def specifySdkType(env: IntegrationTestEnv): IntegrationTestEnv =
    SdkFlowSpec.specifySdkForType("python", "0.4.5.54cc6088", env)
}
