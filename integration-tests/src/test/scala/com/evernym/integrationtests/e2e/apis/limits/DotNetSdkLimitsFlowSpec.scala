package com.evernym.integrationtests.e2e.apis.limits

import com.evernym.integrationtests.e2e.apis.SdkFlowSpec
import com.evernym.integrationtests.e2e.env.IntegrationTestEnv

class DotNetSdkLimitsFlowSpec extends LimitsFlowSpec {
  override def specifySdkType(env: IntegrationTestEnv): IntegrationTestEnv =
    SdkFlowSpec.specifySdkForType("dotnet", "0.4.7-6e248981", env)
}
