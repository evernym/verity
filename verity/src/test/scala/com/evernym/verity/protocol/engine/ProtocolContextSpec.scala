package com.evernym.verity.protocol.engine

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.MsgFamily.EVERNYM_QUALIFIER
import com.evernym.verity.protocol.engine.ProtocolRegistry.DriverGen
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.testkit.InteractionType.OneParty
import com.evernym.verity.protocol.testkit.{InteractionController, InteractionType, SimpleControllerProviderInputType, TestsProtocolsImpl}
import com.evernym.verity.protocol.{Control, HasMsgType}
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.TestExecutionContextProvider
import org.scalatest.Assertions.fail

import scala.concurrent.ExecutionContext

class ProtocolContextSpec extends TestsProtocolsImpl(TestProtoDef2) with BasicFixtureSpec {

  override val defaultInteractionType: InteractionType = OneParty

  "A ProtocolContext" - {
    // Does lots of things that are not tested directly here, but rather are tested in test protocols like TicTacToe and CoinFlip

    "will not send signal messages if the protocol throws an exception" in { f =>

      val alwaysFailDriver: DriverGen[SimpleControllerProviderInputType] = Option { (i: SimpleControllerProviderInputType, ec: ExecutionContext) =>
        new InteractionController(i) {
          override def signal[A]: SignalHandler[A] = {
            case SignalEnvelope(_: TestSignalMsg, _, _, _, _) => fail()
          }
        }
      }

      f.setup("alice", alwaysFailDriver)

      intercept[SomeBadException] {
        f.alice ~ TestControlMsg()
      }
    }
  }
  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
}

case class TestSignalMsg()
case class TestControlMsg() extends Control

sealed trait Role
case class Role1() extends Role
case class Role2() extends Role

class SomeBadException(m: String) extends RuntimeException(m)

class TestProto2(val ctx: ProtocolContextApi[TestProto2, Role, String, String, String, String])
  extends Protocol[TestProto2, Role, String, String, String, String](TestProtoDef2) {
  def handleProtoMsg: (String, Option[Role], String) ?=> Any = ???
  def handleControl: Control ?=> Any = {
    case _: Control =>
      ctx.signal(TestSignalMsg())
      throw new SomeBadException("Something went wrong!")
  }
  def applyEvent: ApplyEvent = ???
}

trait TestProtoDef2MsgType extends HasMsgType with MsgBase {
  val msgFamily = TestMsgFamily2
}
case class TestMsg2(int: Int) extends TestProtoDef2MsgType {
  val msgName = "TEST-MSG-3"
}
case class TestMsg4(str: String) extends TestProtoDef2MsgType {
  val msgName = "TEST-MSG-4"
}


object TestMsgFamily2 extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = EVERNYM_QUALIFIER
  override val name: MsgFamilyName = "TestProto2"
  override val version: MsgFamilyVersion = "0.1"

  override protected val controlMsgs: Map[MsgName, Class[_]] = Map(
    "TestControlMsg" -> classOf[TestControlMsg]
  )
  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "TEST-MSG-3" -> classOf[TestMsg2],
    "TEST-MSG-4" -> classOf[TestMsg4],
  )
  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[TestSignalMsg]  -> "test-signal-msg"
  )
}

object TestProtoDef2 extends ProtocolDefinition[TestProto2, Role, String, String, String, String] {

  override val msgFamily: MsgFamily = TestMsgFamily2

  def protoRef = ProtoRef("TestProto1", "0.1")

  override def supportedMsgs: ProtoReceive = {
    case _ =>
  }

  def create(ctx: ProtocolContextApi[TestProto2, Role, String, String, String, String], executionContext: ExecutionContext): TestProto2 = {
    new TestProto2(ctx)
  }

  def initialState: String = "initial state"
}

class TestDriver extends Driver {
  override def signal[A]: SignalHandler[A] = {
    case SignalEnvelope(_: TestSignalMsg, _, _, _, _) => fail()
  }
}
