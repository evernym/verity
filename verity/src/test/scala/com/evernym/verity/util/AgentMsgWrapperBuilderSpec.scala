package com.evernym.verity.util

import com.evernym.verity.actor.testkit.{CommonSpecUtil, TestAppConfig}
import com.evernym.verity.agentmsg.msgpacker.AgentMsgWrapper
import com.evernym.verity.config.AppConfig
import com.evernym.verity.testkit.agentmsg.AgentMsgWrapperBuilder
import com.evernym.verity.testkit.util.AssertionUtil.expectMsgType
import com.evernym.verity.testkit.BasicSpec



class AgentMsgWrapperBuilderSpec extends BasicSpec with CommonSpecUtil {

  val testAppConfig: AppConfig = new TestAppConfig()
  override def appConfig: AppConfig = testAppConfig

  "AgentMsgWrapperBuilder" - {

    "when asked to prepare CREATE_AGENT msg wrapper without any parameter" - {
      "should be able to create it successfully" in {
        val amw = AgentMsgWrapperBuilder.buildCreateAgentMsgWrapper_MFV_0_6
        expectMsgType[AgentMsgWrapper](amw)
      }
    }

    "when asked to prepare CREATE_AGENT msg wrapper with parameters" - {
      "should be able to create it successfully" in {
        val newDID = generateNewDid()
        val amw = AgentMsgWrapperBuilder.buildCreateAgentMsgWrapper_MFV_0_6(newDID.DID, newDID.verKey)
        expectMsgType[AgentMsgWrapper](amw)
      }
    }

  }
}
