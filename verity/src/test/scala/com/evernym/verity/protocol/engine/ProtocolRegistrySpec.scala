package com.evernym.verity.protocol.engine

import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.MSG_FAMILY_UNKNOWN
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.protocol._
import com.evernym.verity.protocol.engine.Constants.MFV_UNKNOWN
import com.evernym.verity.protocol.engine.MsgFamily.EVERNYM_QUALIFIER
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.testkit.BasicSpec


class ProtocolRegistrySpec extends BasicSpec {

  val TEST_MSG_FAMILY_NAME = "TEST_MSG_FAMILY"
  val TEST_MSG_FAMILY_VERSION = "1.0"

  val defaultTestRegistry = ProtocolRegistry(
    TestProtoDef1 -> PinstIdResolution.V0_2,
    TestProtoDef2 -> PinstIdResolution.V0_2
  )

  "A ProtocolRegistry" - {
    "can support multiple protocol definitions" in {
      ProtocolRegistry(
        TestProtoDef1 -> PinstIdResolution.V0_2,
        TestProtoDef2 -> PinstIdResolution.V0_2
      )
    }

    "will map a message to a registered protocol" in {
      val r = defaultTestRegistry
      r.protoDefForMsg_!(TestMsg1(1).typedMsg) shouldBe TestProtoDef1
      r.protoDefForMsg_!(TestMsg2("x").typedMsg) shouldBe TestProtoDef1
      r.protoDefForMsg_!(TestMsg3(2).typedMsg) shouldBe TestProtoDef2
      r.protoDefForMsg_!(TestMsg4("y").typedMsg) shouldBe TestProtoDef2
    }

    "will return None when looking up the protocol for an unsupported message" in {
      val r = defaultTestRegistry
      r.entryForMsg(TestMsg5(3)) shouldBe None
    }

    "will throw an exception when looking up the protocol for an unsupported message" in {
      val r = defaultTestRegistry
      intercept[UnsupportedMessageType] {
        r.entryForMsg_!(TestMsg5(3))
      }
    }

  }


  trait TestProtoDef1MsgType extends HasMsgType with MsgBase {
    val msgFamily = TestMsgFamily1
  }
  case class TestMsg1(int: Int) extends TestProtoDef1MsgType {
    val msgName = "TEST-MSG-1"
  }

  case class TestMsg2(str: String) extends TestProtoDef1MsgType {
    val msgName = "TEST-MSG-2"
  }

  class TestProto1(val ctx: ProtocolContextApi[TestProto1, String, String, String, String, String])
    extends Protocol[TestProto1, String, String, String, String, String](TestProtoDef1) {
    def handleProtoMsg: (String, Option[String], String) ?=> Any = ???
    def handleControl: Control ?=> Any = ???
    def applyEvent: ApplyEvent = ???
  }

  object TestMsgFamily1 extends MsgFamily {
    override val qualifier: MsgFamilyQualifier = EVERNYM_QUALIFIER
    override val name: MsgFamilyName = "TestProto1"
    override val version: MsgFamilyVersion = "0.1"
    override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
      "TEST-MSG-1" -> classOf[TestMsg1],
      "TEST-MSG-2" -> classOf[TestMsg2]
    )
  }

  object TestProtoDef1 extends ProtocolDefinition[TestProto1, String, String, String, String, String] {

    val msgFamily: MsgFamily = TestMsgFamily1

    def create(ctx: ProtocolContextApi[TestProto1, String, String, String, String, String], mw: MetricsWriter): TestProto1 = {
      new TestProto1(ctx)
    }

    def initialState: String = "initial state"

  }

  trait TestProtoDef2MsgType extends HasMsgType with MsgBase {
    val msgFamily = TestMsgFamily2
  }
  case class TestMsg3(int: Int) extends TestProtoDef2MsgType {
    val msgName = "TEST-MSG-3"
  }
  case class TestMsg4(str: String) extends TestProtoDef2MsgType {
    val msgName = "TEST-MSG-4"
  }

  class TestProto2(val ctx: ProtocolContextApi[TestProto2, String, String, String, String, String])
    extends Protocol[TestProto2, String, String, String, String, String](TestProtoDef2) {
    def handleProtoMsg: (String, Option[String], String) ?=> Any = ???
    def handleControl: Control ?=> Any = ???
    def applyEvent: ApplyEvent = ???
  }

  object TestMsgFamily2 extends MsgFamily {
    override val qualifier: MsgFamilyQualifier = EVERNYM_QUALIFIER
    override val name: MsgFamilyName = "TestProto2"
    override val version: MsgFamilyVersion = "0.1"
    override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
      "TEST-MSG-3" -> classOf[TestMsg2],
      "TEST-MSG-4" -> classOf[TestMsg4],
    )
  }

  object TestProtoDef2 extends ProtocolDefinition[TestProto2, String, String, String, String, String] {

    override val msgFamily: MsgFamily = TestMsgFamily2

    def create(ctx: ProtocolContextApi[TestProto2, String, String, String, String, String], mw: MetricsWriter): TestProto2 = {
      new TestProto2(ctx)
    }

    def initialState: String = "initial state"

  }

  case class TestMsg5(int: Int) extends TypedMsgLike{
    def msg = this
    def msgName = "TEST-MSG-5"
    def msgType = MsgType(EVERNYM_QUALIFIER, MSG_FAMILY_UNKNOWN, MFV_UNKNOWN, msgName)
  }
}
