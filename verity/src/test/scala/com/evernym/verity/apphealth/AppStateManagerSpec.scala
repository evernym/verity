package com.evernym.verity.apphealth

import akka.cluster.Cluster
import akka.cluster.MemberStatus.{Down, Removed}
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.util2.Status._
import com.evernym.verity.actor.appStateManager.{AppStateDetailed, AppStateUpdateAPI, CauseDetail, DrainingStarted, ErrorEvent, EventDetail, ListeningSuccessful, ManualUpdate, MildSystemError, RecoverIfNeeded, SeriousSystemError, SuccessEvent}
import com.evernym.verity.actor.testkit.{ActorSpec, AppStateManagerTestKit}
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.actor.appStateManager.state.{DegradedState, DrainingState, InitializingState, ListeningState, ShutdownWithErrors, SickState}
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreAkkaEvents
import com.evernym.verity.testkit.{BasicFixtureSpec, CancelGloballyAfterFailure}
import org.scalatest.Outcome
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.util.Try


class AppStateManagerSpec
  extends ActorSpec
    with BasicFixtureSpec
    with CancelGloballyAfterFailure
    with Eventually {

  type FixtureParam = AppStateManagerTestKit

  "AppStateMachine" - {

    "when initialized for first time " - {
      "should switch to 'InitializingState'" in { implicit asmTestKit =>
        withLatestAppState { implicit las =>
          asmTestKit.currentAppState shouldBe a [InitializingState]
          assertEvents(las.stateEvents, 1)
          assertCausesByState(las.causesByState, Map.empty)
          assertCausesByContext(las.causesByContext, Map.empty)
        }
      }
    }

    "when in 'InitializingState'" - {

      "MildSystemError event is received" - {
        "should switch to 'DegradedState' state" in { implicit asmTestKit =>
          AppStateUpdateAPI(system).publishEvent(ErrorEvent(MildSystemError, CONTEXT_GENERAL,
            new RuntimeException("exception message"), msg = Option(CAUSE_MESSAGE_MILD_SYSTEM_ERROR)))

          eventually(timeout(Span(5, Seconds)), interval(Span(200, Millis))) {
            withLatestAppState { implicit las =>
              las.currentState shouldBe DegradedState

              assertEvents(las.stateEvents, 2)
              assertCausesByState(las.causesByState, Map(STATUS_DEGRADED ->
                List(withUnhandledCodeAndMsg(CAUSE_DETAIL_MILD_SYSTEM_ERROR))))
              assertCausesByContext(las.causesByContext, Map(CONTEXT_GENERAL ->
                Set(withUnhandledCodeAndMsg(CAUSE_DETAIL_MILD_SYSTEM_ERROR))))
            }
          }
        }
      }

      "SeriousSystemError event is received" - {
        "should switch to 'ShuttingDownState' state" in { implicit asmTestKit =>
          //TODO: why this loop is required?
          (1 to 11).foreach { _ =>
            AppStateUpdateAPI(system).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_GENERAL, new RuntimeException("exception message"),
              msg = Option(CAUSE_MESSAGE_SERIOUS_SYSTEM_ERROR)))
          }
          eventually {
            withLatestAppState { implicit las =>
              las.currentState shouldBe ShutdownWithErrors
              assertEvents(las.stateEvents, 2)
              assertCausesByState(las.causesByState, Map(STATUS_SHUTDOWN_WITH_ERRORS ->
                List(withUnhandledCodeAndMsg(CAUSE_DETAIL_SERIOUS_SYSTEM_ERROR))))
              assertCausesByContext(las.causesByContext, Map(CONTEXT_GENERAL ->
                Set(withUnhandledCodeAndMsg(CAUSE_DETAIL_SERIOUS_SYSTEM_ERROR))))
            }
          }
        }
      }

      "Listening event is received" - {
        "should switch to 'ListeningState' state" in { implicit asmTestKit =>
          switchToListeningState()
        }
      }
    }

    "when in 'Sick' state" - {

      "'ManualUpdate' is received" - {
        "should switch to 'ListeningState' state again" in { implicit asmTestKit =>
          switchToListeningState()
          switchToSickState()

          val causeDetail = CauseDetail(APP_STATUS_UPDATE_MANUAL.statusCode, "manual-update")
          AppStateUpdateAPI(system).publishEvent(SuccessEvent(ManualUpdate(STATUS_LISTENING), CONTEXT_MANUAL_UPDATE, causeDetail))

          eventually(timeout(Span(2, Seconds))) {

            withLatestAppState { implicit las =>
              las.currentState shouldBe ListeningState

              assertEvents(las.stateEvents, 4)
              assertCausesByState(las.causesByState,
                Map(
                  STATUS_LISTENING -> List(causeDetail, CAUSE_DETAIL_LISTENING_SUCCESSFULLY),
                  STATUS_SICK -> List(withUnhandledCodeAndMsg(CAUSE_DETAIL_SERIOUS_SYSTEM_ERROR))))
              assertCausesByContext(las.causesByContext,
                Map(
                  CONTEXT_GENERAL -> Set(withUnhandledCodeAndMsg(CAUSE_DETAIL_SERIOUS_SYSTEM_ERROR), CAUSE_DETAIL_LISTENING_SUCCESSFULLY),
                  CONTEXT_MANUAL_UPDATE -> Set(causeDetail)))
            }
          }
        }
      }

      "'RecoveredEvent' is received" - {
        "should switch to 'ListeningState' state again" in { implicit asmTestKit =>
          switchToListeningState()
          switchToSickState()

          sendRecoverByContext(CONTEXT_GENERAL)
          eventually {
            withLatestAppState { implicit las =>
              las.currentState shouldBe ListeningState

              assertEvents(las.stateEvents, 5)
              assertCausesByState(las.causesByState,
                Map(
                  STATUS_LISTENING -> List(CAUSE_DETAIL_AUTO_RECOVERED, CAUSE_DETAIL_LISTENING_SUCCESSFULLY),
                  STATUS_SICK -> List.empty))
              assertCausesByContext(las.causesByContext,
                Map(
                  CONTEXT_GENERAL -> Set(withUnhandledCodeAndMsg(CAUSE_DETAIL_SERIOUS_SYSTEM_ERROR), CAUSE_DETAIL_LISTENING_SUCCESSFULLY)))
            }
          }
        }
      }
    }

    "when in 'Listening' state" - {

      "MildSystemError event is received" - {
        "should switch to 'DegradedState' state" in { implicit asmTestKit =>
          switchToListeningState()

          AppStateUpdateAPI(system).publishEvent(ErrorEvent(MildSystemError, CONTEXT_GENERAL,
            new RuntimeException("exception message"), msg = Option(CAUSE_MESSAGE_MILD_SYSTEM_ERROR)))

          eventually {
            withLatestAppState { implicit las =>
              las.currentState shouldBe DegradedState

              assertEvents(las.stateEvents, 3)
              assertCausesByState(las.causesByState,
                Map(
                  STATUS_LISTENING -> List(CAUSE_DETAIL_LISTENING_SUCCESSFULLY),
                  STATUS_DEGRADED -> List(withUnhandledCodeAndMsg(CAUSE_DETAIL_MILD_SYSTEM_ERROR))))
              assertCausesByContext(las.causesByContext,
                Map(
                  CONTEXT_GENERAL -> Set(withUnhandledCodeAndMsg(CAUSE_DETAIL_MILD_SYSTEM_ERROR), CAUSE_DETAIL_LISTENING_SUCCESSFULLY)))
            }
          }
        }
      }

      "SeriousSystemError event is received" - {
        "should switch to 'SickState' state" in { implicit asmTestKit =>
          switchToListeningState()
          switchToSickState()
        }
      }

      "DrainingStarted event is received" - {
        "should switch to 'Draining' state" taggedAs UNSAFE_IgnoreAkkaEvents in { implicit asmTestKit =>
          switchToListeningState()

          //keep this as the last part of this spec because as part of this test
          // current node leaves the cluster and if if there are any further test (after this one)
          // which try to create new actor (while system is draining), then it throws exception
          // 'cannot create children while terminating or terminated'
          switchToDraining()
        }
      }
    }

  }

  /**************************************************************************************
    * Helper functions
    *************************************************************************************/

  def switchToListeningState()(implicit amt: AppStateManagerTestKit): Unit = {
    AppStateUpdateAPI(system).publishEvent(SuccessEvent(ListeningSuccessful, CONTEXT_GENERAL, CAUSE_DETAIL_LISTENING_SUCCESSFULLY,
        msg = Option(CAUSE_MESSAGE_LISTENING_SUCCESSFULLY)))

    eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
      withLatestAppState { implicit las =>
        las.currentState shouldBe ListeningState

        assertEvents(las.stateEvents, 2)
        assertCausesByState(las.causesByState, Map(STATUS_LISTENING -> List(CAUSE_DETAIL_LISTENING_SUCCESSFULLY)))
        assertCausesByContext(las.causesByContext, Map(CONTEXT_GENERAL -> Set(CAUSE_DETAIL_LISTENING_SUCCESSFULLY)))
      }
    }
  }


  def switchToSickState()(implicit amt: AppStateManagerTestKit): Unit = {
    AppStateUpdateAPI(system).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_GENERAL, new RuntimeException("runtime exception"),
        msg = Option(CAUSE_MESSAGE_SERIOUS_SYSTEM_ERROR)))

    eventually {
      withLatestAppState { implicit las =>
        las.currentState shouldBe SickState

        assertEvents(las.stateEvents, 3)
        assertCausesByState(las.causesByState,
          Map(
            STATUS_LISTENING -> List(CAUSE_DETAIL_LISTENING_SUCCESSFULLY),
            STATUS_SICK -> List(withUnhandledCodeAndMsg(CAUSE_DETAIL_SERIOUS_SYSTEM_ERROR))))
        assertCausesByContext(las.causesByContext,
          Map(
            CONTEXT_GENERAL -> Set(withUnhandledCodeAndMsg(CAUSE_DETAIL_SERIOUS_SYSTEM_ERROR),
              CAUSE_DETAIL_LISTENING_SUCCESSFULLY)))
      }
    }
  }


  def switchToDraining()(implicit amt: AppStateManagerTestKit): Unit = {
    platform.appStateHandler.startBeforeServiceUnbindTask()

    AppStateUpdateAPI(system).publishEvent(SuccessEvent(
        DrainingStarted,
        CONTEXT_AGENT_SERVICE_DRAIN,
        causeDetail = CAUSE_DETAIL_DRAINING_STARTED,
        msg = Option(CAUSE_MESSAGE_DRAINING_STARTED)
      ))

    eventually(timeout(Span(7, Seconds)), interval(Span(100, Millis))) {
      withLatestAppState { implicit las =>
        las.currentState shouldBe DrainingState

        assertEvents(las.stateEvents, 3)
        assertCausesByState(las.causesByState,
          Map(
            STATUS_LISTENING -> List(CAUSE_DETAIL_LISTENING_SUCCESSFULLY),
            STATUS_DRAINING -> List(CAUSE_DETAIL_DRAINING_STARTED)))
        assertCausesByContext(las.causesByContext,
          Map(
            CONTEXT_GENERAL -> Set(CAUSE_DETAIL_LISTENING_SUCCESSFULLY),
            CONTEXT_AGENT_SERVICE_DRAIN -> Set(CAUSE_DETAIL_DRAINING_STARTED))
        )
      }
    }

    val cluster = Cluster(system)
    eventually(timeout(Span(7, Seconds)), interval(Span(200, Millis))) {
      List(Down, Removed).contains(cluster.selfMember.status) shouldBe true
    }
  }

  val CAUSE_DETAIL_LISTENING_SUCCESSFULLY   = CauseDetail("ls", "listening successfully")
  val CAUSE_DETAIL_MILD_SYSTEM_ERROR        = CauseDetail("mse", "mild system error")
  val CAUSE_DETAIL_SERIOUS_SYSTEM_ERROR     = CauseDetail("sse", "serious system error")
  val CAUSE_DETAIL_DRAINING_STARTED         = CauseDetail("ds", "draining started")
  val CAUSE_DETAIL_AUTO_RECOVERED           = CauseDetail("auto-recovered", "recovered from all causes")
  val CAUSE_DETAIL_SHUTDOWN                 = CauseDetail(CONTEXT_AGENT_SERVICE_SHUTDOWN, s"$CONTEXT_AGENT_SERVICE_SHUTDOWN-successfully")

  val CAUSE_MESSAGE_LISTENING_SUCCESSFULLY  = prepareCauseMessage(CAUSE_DETAIL_LISTENING_SUCCESSFULLY)
  val CAUSE_MESSAGE_MILD_SYSTEM_ERROR       = prepareCauseMessage(CAUSE_DETAIL_MILD_SYSTEM_ERROR)
  val CAUSE_MESSAGE_SERIOUS_SYSTEM_ERROR    = prepareCauseMessage(CAUSE_DETAIL_SERIOUS_SYSTEM_ERROR)
  val CAUSE_MESSAGE_DRAINING_STARTED        = prepareCauseMessage(CAUSE_DETAIL_DRAINING_STARTED)
  val CAUSE_MESSAGE_SHUTDOWN                = prepareCauseMessage(CAUSE_DETAIL_SHUTDOWN)

  def withCauseMsg(cd: CauseDetail): CauseDetail = {
    cd.copy(msg = prepareCauseMessage(cd))
  }

  def withUnhandledCodeAndMsg(cd: CauseDetail): CauseDetail = {
    withCauseMsg(cd).copy(code=UNHANDLED.statusCode)
  }

  def prepareCauseMessage(cd: CauseDetail): String = "cause message " + cd.msg

  def sendRecoverByContext(context: String)(implicit amt: AppStateManagerTestKit): Unit = {
    AppStateUpdateAPI(system).publishEvent(RecoverIfNeeded(context))
  }

  def assertEvents(previousEvents: List[EventDetail], newTotalEventSize: Int)
                  (implicit las: AppStateDetailed): Unit = {
    las.stateEvents.size shouldBe newTotalEventSize
    //this confirms, new events are added at the beginning of the event list (TE expects it that way)
    las.stateEvents.takeRight(previousEvents.size) shouldBe previousEvents
  }

  def assertCausesByState(previousCausesByState: Map[String, List[CauseDetail]],
                          expectedCausesByState: Map[String, List[CauseDetail]])
                         (implicit las: AppStateDetailed): Unit = {

    //this is to confirm if causes are getting added at the beginning (TE expects it that way)
    las.causesByState.foreach { entry =>
      val pcbs = previousCausesByState.getOrElse(entry._1, List.empty).takeRight(entry._2.size)
      entry._2.takeRight(pcbs.size) shouldBe pcbs
    }
    las.causesByState shouldBe expectedCausesByState
  }

  def assertCausesByContext(previousCausesByContext: Map[String, Set[CauseDetail]],
                            expectedCausesByContext: Map[String, Set[CauseDetail]])
                           (implicit las: AppStateDetailed): Unit = {
    //this is to confirm if causes are getting added at the top (TE expects it that way)
    las.causesByContext.foreach { entry =>
      val pcbc = previousCausesByContext.getOrElse(entry._1, Set.empty).takeRight(entry._2.size)
      entry._2.takeRight(pcbc.size) shouldBe pcbc
    }
    las.causesByContext shouldBe expectedCausesByContext
  }

  def withLatestAppState(f: AppStateDetailed => Unit)(implicit amt: AppStateManagerTestKit): Unit = {
    f(amt.currentDetailedAppState)
  }

  def withFixture(test: OneArgTest): Outcome = {
    val fixture = new AppStateManagerTestKit(this, appConfig, ecp.futureExecutionContext)
    val r = super.withFixture(test.toNoArgTest(fixture))
    Try(fixture.stop())   //best effort to stop the app state manager actor
    Thread.sleep(100)  //just to make sure the app state manager actor gets stopped
    r
  }

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}
