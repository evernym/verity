package com.evernym.verity.protocol.protocols.updateConfigs.v_0_6

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.agent.user.GetConfigs
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.Constants._
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.SignalEnvelope
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.Ctl.{SendConfig, Update}
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.Sig.{ConfigResult, UpdateConfig}
import com.evernym.verity.protocol.testkit.DSL.signal
import com.evernym.verity.protocol.testkit.{InteractionController, SimpleControllerProviderInputType, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.TestExecutionContextProvider
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.ExecutionContext


class UpdateConfigsSpec
  extends TestsProtocolsImpl(UpdateConfigsDefinition)
    with BasicFixtureSpec
    with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = {
    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
  }

  "UpdateConfigs Protocol Definition" - {
    "has one role" in { f =>
      UpdateConfigsDefinition.roles.size shouldBe 1
      UpdateConfigsDefinition.roles shouldBe Set(Role.Updater())
    }
  }

  "UpdateConfigsProtocol" - {
    "are control messages working correctly" in { f =>

      Option{ i: SimpleControllerProviderInputType =>
        new InteractionController(i) {
          override def signal[A]: SignalHandler[A] = {
            case SignalEnvelope(msg: ConfigResult,_,_,_,_) =>
              print(s"SignalEnvelope-StatusReport: $msg")

              None
            case s =>
              print(s"SignalEnvelope: $s")
              None
          }
        }
      }

      val updater = f.alice

      val name = "test name"
      val logoUrl = "/logo.ico"
      val resultConfigs = Map(
        NAME_KEY     -> name,
        LOGO_URL_KEY -> logoUrl
      )

      interaction(updater) {
        val configs = Set(
          Config(NAME_KEY, name),
          Config(LOGO_URL_KEY, logoUrl))

        updater ~ Update(configs)

        val agentCall = updater expect signal[UpdateConfig]
        val getAgentCall = updater expect signal[GetConfigs]
        assert(getAgentCall.names == Set(NAME_KEY, LOGO_URL_KEY))

        updater ~ SendConfig(configs)

        val statusReport = updater expect signal[ConfigResult]
        statusReport.configs.foreach(cd =>
          if (cd.name.equals(NAME_KEY)) assert(cd.value == name) else assert(cd.value == logoUrl))
        agentCall.configs.foreach(configData =>
          assert((resultConfigs get configData.name) == Option(configData.value)))
      }

    }
  }

  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
  override def appConfig: AppConfig = TestExecutionContextProvider.testAppConfig
}
