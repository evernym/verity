package com.evernym.integrationtests.e2e.apis

import com.evernym.integrationtests.e2e.env.IntegrationTestEnv
import com.evernym.integrationtests.e2e.tag.annotation.Integration

@Integration
class JavaSdkFlowSpec extends SdkFlowSpec {

  override def specifySdkType(env: IntegrationTestEnv): IntegrationTestEnv = {
    // Does not respect the version, defined in build.sbt but we should in the future
    // load this exact version
    SdkFlowSpec.specifySdkForType("java", "0.6.1-0692296d", env)
  }
}
