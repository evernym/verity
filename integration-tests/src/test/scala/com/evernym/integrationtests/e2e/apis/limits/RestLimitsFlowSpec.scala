package com.evernym.integrationtests.e2e.apis.limits

import com.evernym.integrationtests.e2e.apis.SdkFlowSpec
import com.evernym.integrationtests.e2e.env.IntegrationTestEnv

class RestLimitsFlowSpec extends LimitsFlowSpec {
  override def specifySdkType(env: IntegrationTestEnv): IntegrationTestEnv =
    SdkFlowSpec.specifySdkForType("rest", "0.1.1", env)
}
