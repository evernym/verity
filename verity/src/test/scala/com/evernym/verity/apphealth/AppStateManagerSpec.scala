package com.evernym.verity.apphealth

import com.evernym.verity.Status._
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreAkkaEvents
import com.evernym.verity.apphealth.AppStateConstants._
import com.evernym.verity.apphealth.state._
import com.evernym.verity.testkit.util.TestUtil
import com.evernym.verity.testkit.{BasicFixtureSpec, CancelGloballyAfterFailure}
import com.evernym.verity.util.UtilBase
import com.evernym.verity.{AppVersion, BuildInfo}
import com.typesafe.scalalogging.Logger
import org.scalatest.Outcome
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}


class AppStateManagerSpec extends PersistentActorSpec with BasicFixtureSpec with CancelGloballyAfterFailure with Eventually {

  type FixtureParam = TestAppStateManager

  "AppStateMachine" - {

    "when initialized for first time " - {
      "should switch to 'InitializingState'" in { implicit asm =>
        withPreviousAppManagerState( { previousState =>
          asm.getCurrentState shouldBe InitializingState
          assertEvents(previousState.events, 1)
          assertCausesByState(previousState.causesByState, Map.empty)
          assertCausesByContext(previousState.causesByContext, Map.empty)
        })
      }
    }

    "when in 'InitializingState' state" - {

      "MildSystemError event is received" - {
        "should switch to 'DegradedState' state" in { implicit asm =>
          withPreviousAppManagerState( { previousState =>
            sendErrorEvent(ErrorEventParam(MildSystemError, CONTEXT_GENERAL,
              new RuntimeException("exception message"), msg = Option(CAUSE_MESSAGE_MILD_SYSTEM_ERROR)))
            eventually {
              asm.getCurrentState shouldBe DegradedState

              assertEvents(previousState.events, 2)
              assertCausesByState(previousState.causesByState, Map(STATUS_DEGRADED ->
                List(withUnhandledCodeAndMsg(CAUSE_DETAIL_MILD_SYSTEM_ERROR))))
              assertCausesByContext(previousState.causesByContext, Map(CONTEXT_GENERAL ->
                Set(withUnhandledCodeAndMsg(CAUSE_DETAIL_MILD_SYSTEM_ERROR))))
            }
          })
        }
      }

      "SeriousSystemError event is received" - {
        "should switch to 'ShuttingDownState' state" in { implicit asm =>
          //TODO: why this loop is required?
          withPreviousAppManagerState({ previousState =>
            (1 to 11).foreach { _ =>
              sendErrorEvent(ErrorEventParam(SeriousSystemError, CONTEXT_GENERAL, new RuntimeException("exception message"),
                msg = Option(CAUSE_MESSAGE_SERIOUS_SYSTEM_ERROR)))
            }
            eventually {
              asm.getCurrentState shouldBe ShutdownWithErrors

              assertEvents(previousState.events, 2)
              assertCausesByState(previousState.causesByState, Map(STATUS_SHUTDOWN_WITH_ERRORS ->
                List(withUnhandledCodeAndMsg(CAUSE_DETAIL_SERIOUS_SYSTEM_ERROR))))
              assertCausesByContext(previousState.causesByContext, Map(CONTEXT_GENERAL ->
                Set(withUnhandledCodeAndMsg(CAUSE_DETAIL_SERIOUS_SYSTEM_ERROR))))
            }
          })
        }
      }

      "Listening event is received" - {
        "should switch to 'ListeningState' state" in { implicit asm =>
          switchToListeningState()
        }
      }
    }

    "when in 'Listening' state" - {

      "MildSystemError event is received" - {
        "should switch to 'DegradedState' state" in { implicit asm =>
          switchToListeningState()

          withPreviousAppManagerState({ previousState =>
            sendErrorEvent(ErrorEventParam(MildSystemError, CONTEXT_GENERAL,
              new RuntimeException("exception message"), msg = Option(CAUSE_MESSAGE_MILD_SYSTEM_ERROR)))

            eventually {
              asm.getCurrentState shouldBe DegradedState

              assertEvents(previousState.events, 3)
              assertCausesByState(previousState.causesByState,
                Map(
                  STATUS_LISTENING -> List(CAUSE_DETAIL_LISTENING_SUCCESSFULLY),
                  STATUS_DEGRADED -> List(withUnhandledCodeAndMsg(CAUSE_DETAIL_MILD_SYSTEM_ERROR))))
              assertCausesByContext(previousState.causesByContext,
                Map(
                  CONTEXT_GENERAL -> Set(withUnhandledCodeAndMsg(CAUSE_DETAIL_MILD_SYSTEM_ERROR), CAUSE_DETAIL_LISTENING_SUCCESSFULLY)))
            }
          })
        }
      }

      "SeriousSystemError event is received" - {
        "should switch to 'SickState' state" in { implicit asm =>
          switchToListeningState()
          switchToSickState()
        }
      }

      "DrainingStarted event is received" - {
        "should switch to 'Draining' state" taggedAs (UNSAFE_IgnoreAkkaEvents) in { implicit asm =>
          switchToListeningState()
          switchToDraining()
        }
      }
    }

    "when in 'Sick' state" - {
      "'ManualUpdate' is received" - {
        "should switch to 'ListeningState' state again" in { implicit asm =>
          switchToListeningState()
          switchToSickState()

          withPreviousAppManagerState({ previousState =>
            val causeDetail = CauseDetail(APP_STATUS_UPDATE_MANUAL.statusCode, "manual-update")
            sendSuccessEvent(SuccessEventParam(ManualUpdate(STATUS_LISTENING), CONTEXT_MANUAL_UPDATE, causeDetail))

            eventually(timeout(Span(2, Seconds))) {
              asm.getCurrentState shouldBe ListeningState

              assertEvents(previousState.events, 4)
              assertCausesByState(previousState.causesByState,
                Map(
                  STATUS_LISTENING -> List(causeDetail, CAUSE_DETAIL_LISTENING_SUCCESSFULLY),
                  STATUS_SICK -> List(withUnhandledCodeAndMsg(CAUSE_DETAIL_SERIOUS_SYSTEM_ERROR))))
              assertCausesByContext(previousState.causesByContext,
                Map(
                  CONTEXT_GENERAL -> Set(withUnhandledCodeAndMsg(CAUSE_DETAIL_SERIOUS_SYSTEM_ERROR), CAUSE_DETAIL_LISTENING_SUCCESSFULLY),
                  CONTEXT_MANUAL_UPDATE -> Set(causeDetail)))
            }
          })
        }
      }
    }

    "when in 'Sick' state" - {
      "'RecoveredEvent' is received" - {
        "should switch to 'ListeningState' state again" in { implicit asm =>
          switchToListeningState()
          switchToSickState()

          withPreviousAppManagerState({ previousState =>
            sendRecoverByContext(CONTEXT_GENERAL)
            eventually {
              asm.getCurrentState shouldBe ListeningState

              assertEvents(previousState.events, 5)
              assertCausesByState(previousState.causesByState,
                Map(
                  STATUS_LISTENING -> List(CAUSE_DETAIL_AUTO_RECOVERED, CAUSE_DETAIL_LISTENING_SUCCESSFULLY),
                  STATUS_SICK -> List.empty))
              assertCausesByContext(previousState.causesByContext,
                Map(
                  CONTEXT_GENERAL -> Set(withUnhandledCodeAndMsg(CAUSE_DETAIL_SERIOUS_SYSTEM_ERROR), CAUSE_DETAIL_LISTENING_SUCCESSFULLY)))
            }
          })
        }
      }
    }

  }

  /**************************************************************************************
    * Helper functions
    *************************************************************************************/

  def switchToListeningState()(implicit asm: TestAppStateManager): Unit = {
    withPreviousAppManagerState({ previousState =>
      sendSuccessEvent(SuccessEventParam(ListeningSuccessful, CONTEXT_GENERAL, CAUSE_DETAIL_LISTENING_SUCCESSFULLY,
        msg = Option(CAUSE_MESSAGE_LISTENING_SUCCESSFULLY)))

      eventually(timeout(Span(5, Seconds)), interval(Span(1, Seconds))) {
        asm.getCurrentState shouldBe ListeningState

        assertEvents(previousState.events, 2)
        assertCausesByState(previousState.causesByState, Map(STATUS_LISTENING -> List(CAUSE_DETAIL_LISTENING_SUCCESSFULLY)))
        assertCausesByContext(previousState.causesByContext, Map(CONTEXT_GENERAL -> Set(CAUSE_DETAIL_LISTENING_SUCCESSFULLY)))
      }
    })
  }


  def switchToSickState()(implicit asm: TestAppStateManager): Unit = {
    withPreviousAppManagerState({ previousState =>
      sendErrorEvent(ErrorEventParam(SeriousSystemError, CONTEXT_GENERAL, new RuntimeException("runtime exception"),
        msg = Option(CAUSE_MESSAGE_SERIOUS_SYSTEM_ERROR)))

      eventually {
        asm.getCurrentState shouldBe SickState

        assertEvents(previousState.events, 3)
        assertCausesByState(previousState.causesByState,
          Map(
            STATUS_LISTENING -> List(CAUSE_DETAIL_LISTENING_SUCCESSFULLY),
            STATUS_SICK -> List(withUnhandledCodeAndMsg(CAUSE_DETAIL_SERIOUS_SYSTEM_ERROR))))
        assertCausesByContext(previousState.causesByContext,
          Map(
            CONTEXT_GENERAL -> Set(withUnhandledCodeAndMsg(CAUSE_DETAIL_SERIOUS_SYSTEM_ERROR),
            CAUSE_DETAIL_LISTENING_SUCCESSFULLY)))
      }
    })
  }


  def switchToDraining()(implicit asm: TestAppStateManager): Unit = {
    withPreviousAppManagerState({ previousState =>
      sendSuccessEvent(SuccessEventParam(
        DrainingStarted,
        CONTEXT_AGENT_SERVICE_DRAIN,
        causeDetail = CAUSE_DETAIL_DRAINING_STARTED,
        msg = Option(CAUSE_MESSAGE_DRAINING_STARTED),
        system = Option(system)
      ))

      eventually(timeout(Span(5, Seconds)), interval(Span(1, Seconds))) {
        asm.getCurrentState shouldBe DrainingState

        assertEvents(previousState.events, 3)
        assertCausesByState(previousState.causesByState,
          Map(
            STATUS_LISTENING -> List(CAUSE_DETAIL_LISTENING_SUCCESSFULLY),
            STATUS_DRAINING -> List(CAUSE_DETAIL_DRAINING_STARTED)))
        assertCausesByContext(previousState.causesByContext,
          Map(
            CONTEXT_GENERAL -> Set(CAUSE_DETAIL_LISTENING_SUCCESSFULLY),
            CONTEXT_AGENT_SERVICE_DRAIN -> Set(CAUSE_DETAIL_DRAINING_STARTED))
        )
      }

      eventually(timeout(Span(5, Seconds)), interval(Span(1, Seconds))) {
        asm.getCurrentState shouldBe ShutdownState

        assertEvents(previousState.events, 4)
        assertCausesByState(previousState.causesByState,
          Map(
            STATUS_LISTENING -> List(CAUSE_DETAIL_LISTENING_SUCCESSFULLY),
            STATUS_DRAINING -> List(CAUSE_DETAIL_DRAINING_STARTED),
            STATUS_SHUTDOWN -> List(CAUSE_DETAIL_SHUTDOWN)))
        assertCausesByContext(previousState.causesByContext,
          Map(
            CONTEXT_GENERAL -> Set(CAUSE_DETAIL_LISTENING_SUCCESSFULLY),
            CONTEXT_AGENT_SERVICE_DRAIN -> Set(CAUSE_DETAIL_DRAINING_STARTED),
            CONTEXT_AGENT_SERVICE_SHUTDOWN -> Set(CAUSE_DETAIL_SHUTDOWN)))
      }
    })
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

  def withFixture(test: OneArgTest): Outcome = {
    val fixture = new TestAppStateManager

    super.withFixture(test.toNoArgTest(fixture))
  }

  def sendSuccessEvent(event: SuccessEventParam)(implicit asm: TestAppStateManager): Unit = {
    sendEvent(Right(event))
  }

  def sendErrorEvent(event: ErrorEventParam)(implicit asm: TestAppStateManager): Unit = {
    sendEvent(Left(event))
  }

  def sendEvent(event: Either[ErrorEventParam, SuccessEventParam])(implicit asm: TestAppStateManager): Unit = {
    event.fold(
      eep => asm << eep,
      sep => asm << sep
    )
  }

  def sendRecoverByContext(context: String)(implicit asm: TestAppStateManager): Unit = {
    asm.recoverIfNeeded(context)
  }

  def withPreviousAppManagerState(f:AppManagerState=>Unit)(implicit asm: TestAppStateManager): Unit = {
    val ams = AppManagerState(asm.getEvents, asm.getCurrentState, asm.getCausesByState, asm.getCausesByContext)
    f(ams)
  }

  def assertEvents(previousEvents: List[EventDetail], newTotalEventSize: Int)
                  (implicit asm: TestAppStateManager): Unit = {
    asm.getEvents.size shouldBe newTotalEventSize
    //this confirms, new events are added at the beginning of the event list (TE expects it that way)
    asm.getEvents.takeRight(previousEvents.size) shouldBe previousEvents
  }

  def assertCausesByState(previousCausesByState: Map[String, List[CauseDetail]],
                          expectedCausesByState: Map[String, List[CauseDetail]])
                         (implicit asm: TestAppStateManager): Unit = {

    //this is to confirm if causes are getting added at the beginning (TE expects it that way)
    asm.getCausesByState.foreach { entry =>
      val pcbs = previousCausesByState.getOrElse(entry._1, List.empty).takeRight(entry._2.size)
      entry._2.takeRight(pcbs.size) shouldBe pcbs
    }
    asm.getCausesByState shouldBe expectedCausesByState
  }

  def assertCausesByContext(previousCausesByContext: Map[String, Set[CauseDetail]],
                            expectedCausesByContext: Map[String, Set[CauseDetail]])
                           (implicit asm: TestAppStateManager): Unit = {
    //this is to confirm if causes are getting added at the top (TE expects it that way)
    asm.getCausesByContext.foreach { entry =>
      val pcbc = previousCausesByContext.getOrElse(entry._1, Set.empty).takeRight(entry._2.size)
      entry._2.takeRight(pcbc.size) shouldBe pcbc
    }
    asm.getCausesByContext shouldBe expectedCausesByContext
  }

  case class AppManagerState(events: List[EventDetail],
                             currentState: AppState,
                             causesByState: Map[String, List[CauseDetail]],
                             causesByContext: Map[String, Set[CauseDetail]])
}

class TestSysServiceNotifier extends SysServiceNotifier {
  val logger: Logger = Logger("TestSysServiceNotifier")
  def setStatus(newStatus: String): Unit = logger.info("status set to : " + newStatus)

  def started(): Unit = logger.info("notified")

  def stop(): Unit = logger.info("stopped")
}

class TestAppStateManager extends {
  val appVersion: AppVersion = BuildInfo.version
  val sysServiceNotifier: SysServiceNotifier = new TestSysServiceNotifier
  val util: UtilBase = TestUtil
} with AppStateManagerBase
