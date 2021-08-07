package com.evernym.verity.protocol.protocols.simplecount

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.testkit.InteractionType.OneParty
import com.evernym.verity.protocol.testkit.{InteractionType, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.TestExecutionContextProvider

import scala.concurrent.ExecutionContext


class SimpleCountSpec extends TestsProtocolsImpl(SimpleCountDefinition) with BasicFixtureSpec {

  override val defaultInteractionType: InteractionType = OneParty

  "SimpleCount" - {
    "can start" in { f =>

      f.alice ~ Start()

      f.alice.state shouldBe a[Counting]
    }

    "can count" in { f =>

      f.alice ~ Start()
      f.alice.state shouldBe a[Counting]

      f.alice ~ Increment()
      f.alice.state.data.count shouldBe 1

      f.alice ~ Increment()
      f.alice.state.data.count shouldBe 2
    }
  }

  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  override def appConfig: AppConfig = TestExecutionContextProvider.testAppConfig
}

