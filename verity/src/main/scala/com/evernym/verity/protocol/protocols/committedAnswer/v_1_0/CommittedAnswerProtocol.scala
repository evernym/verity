package com.evernym.verity.protocol.protocols.committedAnswer.v_1_0

// A better name for this protocol would probably be AskQuestion, but QuestionAnswer is used
// because that is what the community has decided to call it.  We may need to influence the
// community to change it.

import java.util.UUID

import com.evernym.verity.Base64Encoded
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{Timing => BaseTiming}
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.ProblemReportCodes._
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.Role.{Questioner, Responder}
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.Signal.{StatusReport, buildProblemReport}
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.State.AnswerValidity
import com.evernym.verity.util.Base64Util.{getBase64Decoded, getBase64Encoded}
import com.evernym.verity.util.TimeUtil._

import scala.util.{Failure, Success}

sealed trait Role

object Role {

  case object Questioner extends Role {
    def roleNum = 0
  }

  case object Responder extends Role {
    def roleNum = 1
  }

  def numToRole: Int ?=> Role = {
    case 0 => Questioner
    case 1 => Responder
  }

  def otherRole: Role ?=> Role = {
    case Questioner => Responder
    case Responder => Questioner
  }

}

class CommittedAnswerProtocol(val ctx: ProtocolContextApi[CommittedAnswerProtocol, Role, Msg, Event, State, String])
  extends Protocol[CommittedAnswerProtocol, Role, Msg, Event, State, String](CommittedAnswerDefinition) {

  import CommittedAnswerProtocol._

  // Event Handlers
  def applyEvent: ApplyEvent = applyCommonEvt orElse applyQuestionerEvt orElse applyResponderEvt

  def applyCommonEvt: ApplyEvent = {
    case (_: State.Uninitialized , _ , e: Initialized  ) => (State.Initialized(), initialize(e))
    case (_                      , _ , MyRole(n)       ) => (None, setRole(n))
    case (s                      , _ , Error(code, comment)) => recordError(s, code, comment)
    case (_: State.Initialized   , r , e: QuestionUsed) =>
      r.selfRole_! match {
        case Questioner => State.QuestionSent(buildQuestion(e))
        case Responder  => State.QuestionReceived(buildQuestion(e))
      }
  }

  def applyQuestionerEvt: ApplyEvent = {
    case (s: State.QuestionSent       , _ , e: SignedAnswerUsed  ) =>
      State.AnswerReceived(s.question, e.response, Some(evtToSigBlock(e)), e.received, None)
    case (s: State.AnswerReceived     , _ , e: Validity  ) =>
      // AnswerValidated
      s.copy(validStatus = Some(AnswerValidity(e.answerValidity, e.signatureValidity, e.timingValidity)))
  }

  def applyResponderEvt: ApplyEvent = {
    case (State.QuestionReceived(q), _ , e: SignedAnswerUsed) =>
      State.AnswerSent(q, e.response, Some(Sig(e.signature, e.signatureData, e.signatureTimestamp)))
  }

  // Protocol Msg Handlers
  override def handleProtoMsg: (State, Option[Role], Msg) ?=> Any = {
    case (_: State.Initialized  , _ , m: Msg.Question ) => receiveQuestion(m)
    case (s: State.QuestionSent , _ , m: Msg.Answer   ) => receiveAnswer(s, m)
  }

  def receiveQuestion(m: Msg.Question): Unit = {
    ctx.apply(MyRole(Responder.roleNum))
    ctx.apply(questionToEvt(m))

    val signal = Signal.AnswerNeeded(
      m.question_text,
      m.question_detail,
      responsesToStrings(m.valid_responses),
      m.`@timing`.flatMap(_.expires_time)
    )
    ctx.signal(signal)
  }



  def receiveAnswer(s: State.QuestionSent, m: Msg.Answer): Unit = {
    val givenResponse = {
      val responseNonce = new String(
        getBase64Decoded(m.`response.@sig`.sig_data)
      )

      s.question
        .valid_responses
        .find(_.nonce == responseNonce)
    }

    val answer = givenResponse.map(_.text).getOrElse("")

    ctx.apply(answerToEvt(m, answer))

    val notExpired = isNotExpired(
      s.question.`@timing`.flatMap(t => t.expires_time)
    )

    val validResponse = givenResponse.isDefined

    givenResponse match {
      case Some(r) =>
        val checkData = buildSignable(r.nonce)
        val givenSig = m.`response.@sig`
        if (checkData.encoded == m.`response.@sig`.sig_data) {
          ctx.wallet.verify(
            ctx.getRoster.participantIdForRole_!(Role.Responder),
            givenSig.sig_data.getBytes, // Connect.me signs the base64 string
            getBase64Decoded(givenSig.signature),
            None
          ) {
            case Success(sigVerifResult) =>
              answerValidated(answer, validResponse, sigVerifResult.verified, notExpired = notExpired)
            case Failure(ex) =>
              ctx.logger.warn(s"Unable to verify signature - ${ex.getMessage}")
              answerValidated(answer, validResponse, validSignature = false, notExpired = notExpired)
          }
        }
        else {
          answerValidated(answer, validResponse, validSignature = false, notExpired = notExpired)
        }
      case None =>
        answerValidated(answer, validResponse, validSignature = false, notExpired = notExpired)
    }
  }

  def answerValidated(answer: String, validResponse: Boolean, validSignature: Boolean, notExpired: Boolean): Unit = {
    ctx.apply(Validity(validResponse, validSignature, notExpired))
    ctx.signal(
      Signal.AnswerGiven(
        answer,
        validResponse,
        validSignature,
        notExpired
      )
    )
  }

  // Control Message Handlers
  def handleControl: Control ?=> Any = {
    case c: Control => mainHandleControl(ctx.getState, c)
  }

  def mainHandleControl: (State, Control) ?=> Unit = {
    case (State.Uninitialized(),     m: Ctl.Init)            => ctx.apply(Initialized(m.selfId, m.otherId))
    case (_: State.Initialized,      m: Ctl.AskQuestion)     => ask(m)
    case (_: State.QuestionReceived, m: Ctl.AnswerQuestion)  => answer(m)
    case (st: State,                 m: Ctl.GetStatus)       => getStatus(st, m)
    case (st: State,                 m: Ctl)                 =>
      ctx.signal(Signal.buildProblemReport(
        s"Unexpected '${CommittedAnswerMsgFamily.msgType(m.getClass).msgName}' message in current state '${st.getClass.getSimpleName}",
        unexpectedMessage
      ))
  }

  def getStatus(st: State, m: Ctl.GetStatus): Unit = {
    val answer = st match {
      case s: State.AnswerReceived =>
        s.validStatus.map{ vs =>
          Signal.AnswerGiven(s.response, vs.answerValidity, vs.signatureValidity, vs.timingValidity)
        }
      case _ => None
    }
    ctx.signal(StatusReport(st.getClass.getSimpleName, answer))
  }

  def ask(m: Ctl.AskQuestion): Unit = {
    ctx.apply(MyRole(Questioner.roleNum))
    val questionMsg = Msg.Question(
      m.text,
      m.detail,
      stringsToResponses(m.valid_responses),
      m.expiration.map(stringToTiming)
    )
    ctx.apply(questionToEvt(questionMsg))
    ctx.send(questionMsg, Some(Responder), Some(Questioner))
  }

  def answer(m: Ctl.AnswerQuestion): Unit = {
    ctx.getState match {
      case state: State.QuestionReceived =>
        m.response match {
          case None =>
            failedAnswer(new Exception("No answer given")) // Handle no answer case
          case Some(resp) => // Sig required
            val nonce = state
              .question
              .valid_responses
              .find(_.text == resp)
              .map(_.nonce)

            nonce match {
              case Some(n) =>
                val signable = buildSignable(n)
                ctx.wallet.sign(signable.bytes) {
                  case Success(signedMsg) =>
                    val sigBlock = Sig(
                      signedMsg.signatureResult.toBase64,
                      signable.encoded,
                      now.toString
                    )
                    successfulAnswer(Msg.Answer(sigBlock), resp)
                  case Failure(ex) =>
                    failedAnswer(ex)
                }
              case None =>
                failedAnswer(new Exception("A valid answer was not selected")) // Handle invalid response given
            }
        }
      case _ => // Don't answer if there is no question
    }
  }

  def successfulAnswer(answer: Msg.Answer, response: String): Unit = {
    ctx.apply(answerToEvt(answer, response))
    ctx.send(answer, Some(Questioner), Some(Responder))
  }

  def failedAnswer(ex: Throwable): Unit = {
    val failureMsg = s"Unable build answer - ${ex.getMessage}"
    ctx.logger.info(failureMsg)
    ctx.apply(Error(1, failureMsg))
    ctx.signal(buildProblemReport(failureMsg, invalidAnswer))
  }

  // Helper Functions
  def setRole(role: Int): Option[Roster[Role]] = {
    val myRole = Role.numToRole(role)
    val otherAssignment = Role.otherRole(myRole) -> ctx.getRoster.otherId()
    ctx.getRoster.withSelfAssignment(myRole).withAssignmentById(otherAssignment)
  }



  def initialize(p: Initialized): Roster[Role] = {
    ctx.updatedRoster(Seq(InitParamBase(SELF_ID, p.selfIdValue), InitParamBase(OTHER_ID, p.otherIdValue)))
  }
}

object CommittedAnswerProtocol {

  val MAX_EXPIRATION_TIME: String = "9999-12-31T23:59:59+0000"

  def createNonce(): Nonce = {
    UUID.randomUUID().toString
  }

  case class Signable(bytes: Array[Byte], encoded: Base64Encoded)

  def buildSignable(nonce: String): Signable = {
    def safeNull(str: String) = Option(str).orElse(Some(""))

    safeNull(nonce)
      .map(_.getBytes())
      .map(getBase64Encoded)
      .map(x=>Signable(x.getBytes, x))
      .getOrElse(Signable("".getBytes, ""))
  }

  def stringsToResponses(values: Vector[String]): Vector[QuestionResponse] = {
    values.map(QuestionResponse(_, createNonce()))
  }

  def responsesToStrings(values: Vector[QuestionResponse]): Vector[String] = {
    values.map(_.text)
  }

  def responsesToNonces(values: Vector[QuestionResponse]): Vector[String] = {
    values.map(_.nonce)
  }

  def stringToTiming(value: String): BaseTiming = {
    BaseTiming(Some(value))
  }

  def buildQuestion(q: QuestionUsed): Msg.Question = {
    Msg.Question(
      q.questionText,
      Some(q.questionDetail),
      q.validResponses
        .zip(q.validNonce)
        .map {x =>QuestionResponse(x._1, x._2)}
        .toVector,
      Some(BaseTiming(expires_time = Some(q.expiresTime)))
    )
  }

  def evtToSigBlock(a: SignedAnswerUsed): Sig = {
    Sig(
      a.signature,
      a.signatureData,
      a.signatureTimestamp
    )
  }

  def questionToEvt(q: Msg.Question): QuestionUsed = {
    QuestionUsed(
      q.question_text,
      q.question_detail.get,
      responsesToStrings(q.valid_responses),
      responsesToNonces(q.valid_responses),
      // We default to a MAX_EXPIRATION_TIME because
      // protobuf don't support a None like type (don't even support null)
      // so if expiration is not given, we set it super far in to the future
      // basally providing unlimited time to complete the protocol.
      q.`@timing`.flatMap(t => t.expires_time).getOrElse(MAX_EXPIRATION_TIME)
    )
  }

  def answerToEvt(ans: Msg.Answer, response: String): Event = {
    val sig = ans.`response.@sig`
    SignedAnswerUsed(
      response,
      now.toString,
      sig.signature,
      sig.sig_data,
      sig.timestamp
    )
  }

  def recordError(s: State, code: Int, comment: String): State.Error = {
    s match {
      case _: State.Uninitialized => State.Error(code, comment)
      case _: State.Initialized   => State.Error(code, comment)
      case State.QuestionSent(q)  => State.Error(code, comment, question = Some(q))
      case State.AnswerReceived(q, r, s, t, v) =>
        State.Error(code,comment,Some(q),Some(r),s,Some(t),v)
      case State.QuestionReceived(q) => State.Error(code, comment, question = Some(q))
      case State.AnswerSent(q, r, s) =>
        State.Error(code, comment, question = Some(q), response = Some(r), signature = s)
      case s => State.Error(code, comment + s" (From an unknown state: ${s.getClass.getSimpleName})")
    }
  }
}
