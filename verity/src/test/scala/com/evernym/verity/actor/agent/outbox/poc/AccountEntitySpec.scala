package com.evernym.verity.actor.agent.outbox.poc

import akka.Done
import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.pattern.StatusReply
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import com.evernym.verity.actor.agent.outbox.poc
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterEach
import org.slf4j.LoggerFactory

import java.io.ByteArrayInputStream

class AccountEntitySpec {

}


trait CaptureAppender {

  val logbackxml =
    """
      |<configuration>
      |
      |    <appender name="CapturingAppender" class="akka.actor.testkit.typed.internal.CapturingAppender" />
      |
      |    <logger name="akka.actor.testkit.typed.internal.CapturingAppenderDelegate" >
      |      <appender-ref ref="STDOUT"/>
      |    </logger>
      |
      |    <root level="DEBUG">
      |        <appender-ref ref="CapturingAppender"/>
      |    </root>
      |</configuration>
      |""".stripMargin

  val context = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]

  val configurator = new JoranConfigurator
  configurator.setContext(context)
  context.reset()
  configurator.doConfigure(new ByteArrayInputStream(logbackxml.getBytes))

}

object AccountExampleDocSpec {
  val config = ConfigFactory.parseString("""
    akka.actor {
      serialization-bindings {
        "com.evernym.verity.actor.typed.Encodable" = jackson-json
      }
    }
  """).withFallback(EventSourcedBehaviorTestKit.config)
}

class AccountExampleDocSpec
  extends ScalaTestWithActorTestKit(AccountExampleDocSpec.config)
    with BasicSpec
    with BeforeAndAfterEach
    with CaptureAppender
    with LogCapturing {


  private val eventSourcedTestKit =
    EventSourcedBehaviorTestKit[AccountEntity.Command, AccountEntity.Event, AccountEntity.Account](
      system,
      poc.AccountEntity("1", PersistenceId("Account", "1")))

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
  }

  "Account" - {

    "must be created with zero balance" in {
      val result = eventSourcedTestKit.runCommand[StatusReply[Done]](AccountEntity.CreateAccount(_))
      result.reply shouldBe StatusReply.Ack
      result.event shouldBe AccountEntity.AccountCreated
      result.stateOfType[AccountEntity.OpenedAccount].balance shouldBe 0
    }

    "must handle Withdraw" in {
      eventSourcedTestKit.runCommand[StatusReply[Done]](AccountEntity.CreateAccount(_))

      val result1 = eventSourcedTestKit.runCommand[StatusReply[Done]](AccountEntity.Deposit(100, _))
      result1.reply shouldBe StatusReply.Ack
      result1.event shouldBe AccountEntity.Deposited(100)
      result1.stateOfType[AccountEntity.OpenedAccount].balance shouldBe 100

      val result2 = eventSourcedTestKit.runCommand[StatusReply[Done]](AccountEntity.Withdraw(10, _))
      result2.reply shouldBe StatusReply.Ack
      result2.event shouldBe AccountEntity.Withdrawn(10)
      result2.stateOfType[AccountEntity.OpenedAccount].balance shouldBe 90
    }

    "must reject Withdraw overdraft" in {
      eventSourcedTestKit.runCommand[StatusReply[Done]](AccountEntity.CreateAccount(_))
      eventSourcedTestKit.runCommand[StatusReply[Done]](AccountEntity.Deposit(100, _))

      val result = eventSourcedTestKit.runCommand[StatusReply[Done]](AccountEntity.Withdraw(110, _))
      result.reply.isError shouldBe true
      result.hasNoEvents shouldBe true
    }

    "must handle GetBalance" in {
      eventSourcedTestKit.runCommand[StatusReply[Done]](AccountEntity.CreateAccount(_))
      eventSourcedTestKit.runCommand[StatusReply[Done]](AccountEntity.Deposit(100, _))

      val result = eventSourcedTestKit.runCommand[AccountEntity.CurrentBalance](AccountEntity.GetBalance(_))
      result.reply.balance shouldBe 100
      result.hasNoEvents shouldBe true
    }
  }
}
