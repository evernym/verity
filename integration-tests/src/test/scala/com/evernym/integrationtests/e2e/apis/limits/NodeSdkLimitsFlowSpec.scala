package com.evernym.integrationtests.e2e.apis.limits

import com.evernym.integrationtests.e2e.apis.SdkFlowSpec
import com.evernym.integrationtests.e2e.env.IntegrationTestEnv
import com.evernym.integrationtests.e2e.tag.annotation.Limits

@Limits
class NodeSdkLimitsFlowSpec extends LimitsFlowSpec {
  override def specifySdkType(env: IntegrationTestEnv): IntegrationTestEnv =
    SdkFlowSpec.specifySdkForType("node", "0.4.5-705fb23c", env)
}
