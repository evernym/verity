package com.evernym.integrationtests.e2e.apis

import com.evernym.integrationtests.e2e.env.IntegrationTestEnv
import com.evernym.integrationtests.e2e.tag.annotation.Integration

@Integration
class PythonSdkFlowSpec extends SdkFlowSpec {
  override def specifySdkType(env: IntegrationTestEnv): IntegrationTestEnv =
    SdkFlowSpec.specifySdkForType("python", "0.4.5-7ccf07a5", env)
}
