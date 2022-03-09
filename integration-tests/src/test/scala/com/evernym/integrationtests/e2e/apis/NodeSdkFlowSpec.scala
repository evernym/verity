package com.evernym.integrationtests.e2e.apis

import com.evernym.integrationtests.e2e.env.IntegrationTestEnv
import com.evernym.integrationtests.e2e.tag.annotation.Integration

@Integration
class NodeSdkFlowSpec extends SdkFlowSpec {
  override def specifySdkType(env: IntegrationTestEnv): IntegrationTestEnv =
    SdkFlowSpec.specifySdkForType("node", "0.6.1-0692296d", env)
}
