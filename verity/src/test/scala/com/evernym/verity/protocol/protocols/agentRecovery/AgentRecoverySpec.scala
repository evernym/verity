//package com.evernym.verity.protocol.protocols.agentRecovery
//
//import com.evernym.verity.protocol._
//import com.evernym.verity.testkit.BasicSpec

//
//class AgentRecoverySpec extends BasicSpec {
//  type Container = SimpleProtocolContainer[AgentRecovery, Role, Msg, AgentRecoveryEvt, AgentRecoveryState, String]
//  def c: Container.state = {
//    c.protocolInsts(AgentRecoveryDef).protocol.state
//  }
//
//  // Todo: Roles are arbitrarily defined. Need to re-evaluate the roles in this protocol
//  "The AgencyRecovery Protocol" - {
//    "has two roles" in {
//      AgentRecoveryDef.roles.size shouldBe 1
//    }
//  }
//
//}
