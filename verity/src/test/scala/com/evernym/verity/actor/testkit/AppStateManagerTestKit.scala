package com.evernym.verity.actor.testkit

import java.util.UUID
import akka.actor.ActorRef
import akka.testkit.TestKitBase
import com.evernym.verity.util2.Status.APP_STATUS_UPDATE_MANUAL
import com.evernym.verity.actor.appStateManager.AppStateConstants.{CONTEXT_MANUAL_UPDATE, STATUS_LISTENING}
import com.evernym.verity.actor.appStateManager.{AppStateDetailed, AppStateManager, CauseDetail, GetCurrentState, GetDetailedAppState, ListeningSuccessful, ManualUpdate, SuccessEvent}
import com.evernym.verity.actor.appStateManager.state.{AppState, InitializingState, ListeningState}
import com.evernym.verity.actor.base.{Done, Ping, Stop}
import com.evernym.verity.actor.testkit.actor.{MockNotifierService, MockShutdownService}
import com.evernym.verity.config.AppConfig
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}

/**
 * A special test kit to be able to test AppStateManager functionality.
 * Sometimes we need a clean app state manager state for each test (to make each test independent)
 * and in those situations we can use Fixture to instantiate this class each time and provide it to the test code.
 *
 * AppStateManagerSpec is using this extensively
 *
 * @param testKit this is to be able to use 'expectMsgType' functionality provided by TestKit
 */
class AppStateManagerTestKit(testKit: TestKitBase, appConfig: AppConfig)
  extends Eventually with Matchers {

  implicit val self: ActorRef = testKit.testActor

  private val actorName: String = UUID.randomUUID().toString

  private val appManager: ActorRef = {
    val ar = testKit.system.actorOf(
      AppStateManager.props(appConfig, MockNotifierService, MockShutdownService), actorName)
    ar ! Ping(sendAck = true)
    testKit.expectMsgType[Done.type]
    ar
  }

  def sendToAppStateManager(cmd: Any): Unit = {
    appManager ! cmd
  }

  def currentDetailedAppState: AppStateDetailed = {
    sendToAppStateManager(GetDetailedAppState)
    testKit.expectMsgType[AppStateDetailed]
  }

  def currentAppState: AppState = {
    sendToAppStateManager(GetCurrentState)
    testKit.expectMsgType[AppState]
  }

  def checkAppManagerState(expectedState: AppState): Unit = {
    sendToAppStateManager(GetCurrentState)
    testKit.expectMsgType[AppState] shouldBe expectedState
  }

  def stop(): Unit = {
    sendToAppStateManager(Stop(sendAck = true))
    testKit.expectMsgType[Done.type]
  }

  def changeAppState(newAppState: AppState): Unit = {
    val currentState = currentAppState
    (currentState, newAppState) match {
      case (_: InitializingState, ListeningState) =>
        sendToAppStateManager(SuccessEvent(ListeningSuccessful, "SERVICE_INIT",
          CauseDetail("agent-service-started", "agent-service-started-listening-successfully")))

      case (_, ListeningState) =>
        sendToAppStateManager(SuccessEvent(ManualUpdate(STATUS_LISTENING), CONTEXT_MANUAL_UPDATE,
          CauseDetail(APP_STATUS_UPDATE_MANUAL.statusCode, "manual-update")))

      case other => throw new RuntimeException("changing app state not yet supported: " + other)
    }
    eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
      checkAppManagerState(newAppState)
    }
  }

  def withListeningAppState()(f: => Unit): Unit = {
    changeAppState(ListeningState)
    f
    changeAppState(ListeningState)
  }

}
