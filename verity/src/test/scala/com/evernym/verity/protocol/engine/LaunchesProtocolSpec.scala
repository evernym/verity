package com.evernym.verity.protocol.engine

import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{EVERNYM_QUALIFIER, MsgFamilyName, MsgFamilyQualifier, MsgFamilyVersion, MsgName}
import com.evernym.verity.protocol.engine.registry.PinstIdResolution.V0_2
import com.evernym.verity.protocol.engine.registry.ProtocolRegistry.Entry
import com.evernym.verity.protocol.engine.context.ProtocolContextApi
import com.evernym.verity.protocol.engine.registry.{LaunchesProtocol, PinstIdResolution, ProtocolRegistry}
import com.evernym.verity.testkit.BasicSpec


class LaunchesProtocolSpec extends BasicSpec {

  val relationshipId: Option[String] = Some("7474aeb3-b9b6-4947-ab57-dc851cf8555a")

  "Resolve PinstId" - {
    "Agent Scope resolves to same pinstId every time" in {
      val protoDef = new TestDef("TestDef")
      val launcher = buildLaunchesProtocol(List(protoDef))
      val p1 = launcher.resolvePinstId(protoDef, V0_2, relationshipId, "f8c6e76a-b7d5-4365-b5a7-91f798cb9b36")
      val p2 = launcher.resolvePinstId(protoDef, V0_2, relationshipId, "144dc795-09df-45e8-9634-65a9229fae77")

      assert(p1 == p2)
    }

    "Agent Scope resolves to different pintsId for different ProtocolDefinitions" in {
      val protoDef1 = new TestDef("TestDef1")
      val protoDef2 = new TestDef("TestDef2")

      val launcher = buildLaunchesProtocol(List(protoDef1, protoDef2))

      val p1 = launcher.resolvePinstId(protoDef1, V0_2, relationshipId, "f8c6e76a-b7d5-4365-b5a7-91f798cb9b36")
      val p2 = launcher.resolvePinstId(protoDef2, V0_2, relationshipId,"144dc795-09df-45e8-9634-65a9229fae77")

      assert(!(p1 == p2))
    }
  }

  class TestDefMsgFamily(familyName: String) extends MsgFamily {
    override val qualifier: MsgFamilyQualifier = EVERNYM_QUALIFIER
    override val name: MsgFamilyName = familyName
    override val version: MsgFamilyVersion = "0.1"
    override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map.empty
  }

  class TestDef(familyName: String) extends ProtocolDefinition[String, String, String, String, String, String] {
    override def supportedMsgs: ProtoReceive = { case _ => }

    override def create(context: ProtocolContextApi[String, String, String, String, String, String]):
      Protocol[String, String, String, String, String, String] = ???

    override def initialState: String = ???

    override def scope: Scope.ProtocolScope = Scope.Agent

    override val msgFamily: MsgFamily =  new TestDefMsgFamily(familyName)
  }

  def buildLaunchesProtocol(protoDefs: List[ProtoDef]): LaunchesProtocol = {
    new LaunchesProtocol {
      override def contextualId: Option[String] = None
      override def domainId: DomainId = "f4ba2b9d-f50a-498e-8095-8deb361434ea"
      override protected def protocolRegistry: ProtoReg = ProtocolRegistry(protoDefs map { Entry(_, PinstIdResolution.DEPRECATED_V0_1) }: _*)
    }
  }

}
