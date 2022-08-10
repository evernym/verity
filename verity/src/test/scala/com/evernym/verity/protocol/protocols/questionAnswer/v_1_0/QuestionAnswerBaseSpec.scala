package com.evernym.verity.protocol.protocols.questionAnswer.v_1_0

import java.util.Base64

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{SigBlock, Timing => BaseTiming}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Ctl._
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.QuestionAnswerProtocol._
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Signal.StatusReport
import com.evernym.verity.protocol.testkit.DSL._
import com.evernym.verity.protocol.testkit.{MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.TestExecutionContextProvider

import scala.concurrent.ExecutionContext
import scala.language.{implicitConversions, reflectiveCalls}


abstract class QuestionAnswerBaseSpec
  extends TestsProtocolsImpl(QuestionAnswerDefinition)
  with BasicFixtureSpec {

  import QuestionAnswerVars._

  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  protected implicit def EnhancedScenario(s: Scenario) = new {
    val questioner: TestEnvir = s(QUESTIONER)
    val responder: TestEnvir = s(RESPONDER)
  }

  def checkStatus(envir: TestEnvir, expectedStatus: StatusReport): Unit = {
    envir clear signals
    envir ~ GetStatus()
    (envir expect signal [StatusReport]) shouldBe expectedStatus
  }

  def withDefaultWalletAccess(s: Scenario, f: => Unit): Unit = {
    s.responder walletAccess MockableWalletAccess()
    s.questioner walletAccess MockableWalletAccess()
    f
  }

  def askAndAnswer(s: Scenario,
                   ex: Option[BaseTiming] = EXPIRATION_TIME,
                   out: Option[BaseTiming] = OUT_TIME): Unit = {

    s.questioner ~ testAskQuestion(ex)
    s.questioner expect state [State.QuestionSent]
    s.responder expect signal [Signal.AnswerNeeded]
    s.responder expect state [State.QuestionReceived]

    s.responder ~ AnswerQuestion(Some(CORRECT_ANSWER))
    s.responder expect state [State.AnswerSent]
    s.questioner expect state [State.AnswerReceived]
  }

  override val containerNames: Set[ContainerName] = Set(QUESTIONER, RESPONDER)
}

object QuestionAnswerVars {
  val QUESTIONER = "questioner"
  val RESPONDER = "responder"
  val QUESTION_TEXT = "ask a question"
  val QUESTION_DETAIL = Option("Some Context to question")
  val CORRECT_ANSWER = "answer1"
  val SIG: String = Base64.getEncoder().encodeToString(CORRECT_ANSWER.getBytes())
  val VALID_RESPONSES: Vector[QuestionResponse] = Vector(
    QuestionResponse(text = CORRECT_ANSWER),
    QuestionResponse(text = "reject")
  )
  val EXPIRATION_TIME = Option(BaseTiming(expires_time = Some("2118-12-13T17:29:06+0000")))
  val TIME_EXPIRED = Option(BaseTiming(expires_time = Some("2017-12-13T17:29:06+0000")))
  val OUT_TIME = Option(BaseTiming(out_time = Some("2018-12-13T17:29:34+0000")))
  val TEST_SIGNATURE_DATA: SigBlock = SigBlock(SIG, "base64 of response", Vector("V1", "V2"))

  def testAskQuestion(time: Option[BaseTiming], sigRequired: Boolean = true): AskQuestion = {
    AskQuestion(
      QUESTION_TEXT,
      QUESTION_DETAIL,
      responsesToStrings(VALID_RESPONSES),
      sigRequired,
      time.flatMap(_.expires_time)
    )
  }

  def testQuestion(time: Option[BaseTiming], sigRequired: Boolean = true): Msg.Question =
    Msg.Question(QUESTION_TEXT, QUESTION_DETAIL, "", signature_required = sigRequired, VALID_RESPONSES, time)

  def testAnswer(time: Option[BaseTiming], sigData: Option[SigBlock] = Some(TEST_SIGNATURE_DATA)): Msg.Answer =
    Msg.Answer(CORRECT_ANSWER, sigData, time)
}
