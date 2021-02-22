package com.evernym.verity.protocol.protocols.questionAnswer.v_1_0

// A better name for this protocol would probably be AskQuestion, but QuestionAnswer is used
// because that is what the community has decided to call it.  We may need to influence the
// community to change it.

import java.util.{Base64, UUID}
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.Base64Encoded
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.external_api_access.WalletAccess
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{SigBlock, Timing => BaseTiming}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.QuestionAnswerProtocol.Nonce
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Role.{Questioner, Responder}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Signal.StatusReport
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.State.AnswerValidity
import com.evernym.verity.util.Base64Util.{getBase64MultiDecoded, getBase64UrlEncoded}
import com.evernym.verity.util.{HashAlgorithm, HashUtil}
import org.joda.time.{DateTime, DateTimeZone}

import scala.util.{Failure, Success, Try}

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

trait Event

sealed trait State

object State {

  case class Uninitialized() extends State

  case class Initialized() extends State

  case class Error(errorCode:   Int,
                   comment:     String,
                   question:    Option[Msg.Question] = None,
                   response:    Option[String] = None,
                   signature:   Option[SigBlock] = None,
                   received:    Option[String] = None,
                   validStatus: Option[AnswerValidity] = None) extends State

  // Questioner STATES:

  case class QuestionSent(question: Msg.Question) extends State

  case class AnswerReceived(question:    Msg.Question,
                            response:    String,
                            signature:   Option[SigBlock],
                            received:    String,
                            validStatus: Option[AnswerValidity]) extends State

  // NOT a named state, but a dependent data structure
  case class AnswerValidity(answerValidity:    Boolean,
                            signatureValidity: Boolean,
                            timingValidity:    Boolean)

  // Responder STATES:

  case class QuestionReceived(question: Msg.Question) extends State

  case class AnswerSent(question:  Msg.Question,
                        response:  String,
                        signature: Option[SigBlock]) extends State
}

// Sub Types
case class QuestionResponse(text: String)

// Messages
sealed trait Msg extends MsgBase

object Msg {

  case class Question(question_text: String,
                      question_detail: Option[String],
                      nonce: Nonce,
                      signature_required: Boolean,
                      valid_responses: Vector[QuestionResponse],
                      `~timing`: Option[BaseTiming] // Expects field expireTime
                     ) extends Msg


  case class Answer(response: String,
                    `response~sig`: Option[SigBlock],
                    `~timing`: Option[BaseTiming] // Expects field outTime
                   ) extends Msg
}

// Control Messages
sealed trait Ctl extends Control with MsgBase

object Ctl {

  case class Init(selfId: ParameterValue, otherId: ParameterValue) extends Ctl

  case class AskQuestion(text: String,
                         detail: Option[String],
                         valid_responses: Vector[String],
                         signature_required: Boolean,
                         expiration: Option[String]) extends Ctl

  case class AnswerQuestion(response: Option[String]) extends Ctl

  case class GetStatus() extends Ctl
}

// Signal Messages
sealed trait SignalMsg

object Signal {
  case class AnswerNeeded(text: String,
                          details: Option[String],
                          valid_responses: Vector[String],
                          signature_required: Boolean,
                          expiration: Option[String]
                         ) extends SignalMsg

  case class AnswerGiven(answer: Base64Encoded,
                         valid_answer: Boolean,
                         valid_signature: Boolean,
                         not_expired: Boolean
                        ) extends SignalMsg

  case class ProblemReport(errorCode: Int,
                           comment: String
                    ) extends SignalMsg

  case class StatusReport(status: String, answer: Option[AnswerGiven] = None)
}


class QuestionAnswerProtocol(val ctx: ProtocolContextApi[QuestionAnswerProtocol, Role, Msg, Event, State, String])
  extends Protocol[QuestionAnswerProtocol, Role, Msg, Event, State, String](QuestionAnswerDefinition) {

  import QuestionAnswerProtocol._

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
    case (s: State.QuestionSent       , _ , e: AnswerUsed        ) =>
      State.AnswerReceived(s.question, e.response, None, e.received, None)
    case (s: State.QuestionSent       , _ , e: SignedAnswerUsed  ) =>
      State.AnswerReceived(s.question, e.response, Some(evtToSigBlock(e)), e.received, None)
    case (s: State.AnswerReceived     , _ , e: Validity  ) =>
      // AnswerValidated
      s.copy(validStatus = Some(AnswerValidity(e.answerValidity, e.signatureValidity, e.timingValidity)))
  }

  def applyResponderEvt: ApplyEvent = {
    case (State.QuestionReceived(q), _ , e: AnswerUsed      ) =>
      State.AnswerSent(q, e.response, None)
    case (State.QuestionReceived(q), _ , e: SignedAnswerUsed) =>
      State.AnswerSent(q, e.response, Some(SigBlock(e.signature, e.signatureData, e.signers)))
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
      m.signature_required,
      m.`~timing`.flatMap(_.expires_time)
    )
    ctx.signal(signal)
  }

  def receiveAnswer(s: State.QuestionSent, m: Msg.Answer): Unit = {
    ctx.apply(answerToEvt(m))

    val notExpired = isNotExpired(
      s.question.`~timing`.flatMap(t => t.expires_time)
    )

    val validResponse = {
      s.question.valid_responses.exists(_.text == m.response)
    }

    if (s.question.signature_required) {
      m.`response~sig` match {
        case Some(x) =>
          val checkData = buildSignableHash(s.question.question_text, m.response, s.question.nonce)
          val receivedSigData = getBase64MultiDecoded(x.sig_data)
          if (!(receivedSigData sameElements checkData.bytes)) {
            answerValidated(m.response, validResponse, validSignature = false, notExpired)
          }
          else {
            ctx.wallet.verify(
              ctx.getRoster.participantIdForRole_!(Role.Responder),
              receivedSigData,
              getBase64MultiDecoded(x.signature),
              x.signers.headOption
            ) {
              case Success(validSignature) =>
                answerValidated(m.response, validResponse, validSignature, notExpired)
              case Failure(ex) =>
                ctx.logger.warn(s"Unable to verify signature - ${ex.getMessage}")
                answerValidated(m.response, validResponse, validSignature = false, notExpired)
            }
          }
        case None =>
          answerValidated(m.response, validResponse, validSignature = false, notExpired)
      }
    } else {
      answerValidated(m.response, validResponse, validSignature = true, notExpired)
    }
  }

  def answerValidated(answer: String, validResponse: Boolean, validSignature: Boolean, notExpired: Boolean): Unit = {
    ctx.apply(Validity(validResponse, validSignature, notExpired))
    ctx.signal(Signal.AnswerGiven(answer, validResponse, validSignature, notExpired))
  }

  // Control Message Handlers
  def handleControl: Control ?=> Any = {
    case m: Ctl.Init            => ctx.apply(Initialized(m.selfId, m.otherId))
    case m: Ctl.GetStatus       => getStatus(m)
    case m: Ctl.AskQuestion     => ask(m)
    case m: Ctl.AnswerQuestion  => answer(m)
  }

  def getStatus(m: Ctl.GetStatus): Unit = {
    val answer = ctx.getState match {
      case s: State.AnswerReceived =>
        s.validStatus.map{ vs =>
          Signal.AnswerGiven(s.response, vs.answerValidity, vs.signatureValidity, vs.timingValidity)
        }
      case _ => None
    }
    ctx.signal(StatusReport(ctx.getState.getClass.getSimpleName, answer))
  }

  def ask(m: Ctl.AskQuestion): Unit = {
    ctx.apply(MyRole(Questioner.roleNum))
    val nonce = createNonce()
    val questionMsg = Msg.Question(
      m.text,
      m.detail,
      nonce,
      m.signature_required,
      stringsToResponses(m.valid_responses),
      m.expiration.map(stringToTiming)
    )
    ctx.apply(questionToEvt(questionMsg))
    ctx.send(questionMsg, Some(Responder), Some(Questioner))
  }

  def answer(m: Ctl.AnswerQuestion): Unit = {
    ctx.getState match {
      case state: State.QuestionReceived =>
        buildAnswer(m.response, state, ctx.wallet) {
          case Success(answer) =>
            ctx.apply(answerToEvt(answer))
            ctx.send(answer, Some(Questioner), Some(Responder))
          case Failure(ex) =>
            val failureMsg = s"Unable build answer - ${ex.getMessage}"
            ctx.logger.info(failureMsg)
            ctx.apply(Error(1, failureMsg))
        }

      case _ => // Don't answer if there is no question
    }
  }

  // Helper Functions
  def setRole(role: Int): Option[Roster[Role]] = {
    val myRole = Role.numToRole(role)
    val otherAssignment = Role.otherRole(myRole) -> ctx.getRoster.otherId()
    val t = ctx.getRoster.withSelfAssignment(myRole).withAssignmentById(otherAssignment)
    t
  }



  def initialize(p: Initialized): Roster[Role] = {
    ctx.updatedRoster(Seq(InitParamBase(SELF_ID, p.selfIdValue), InitParamBase(OTHER_ID, p.otherIdValue)))
  }
}

object QuestionAnswerProtocol {
  val MAX_EXPIRATION_TIME: String = "9999-12-31T23:59:59+0000"

  type Nonce = String
  type IsoDateTime = String

  def createNonce(): Nonce = {
    UUID.randomUUID().toString
  }

  def now: Long = DateTime.now.withZone(DateTimeZone.UTC).getMillis

  def nowDateString: IsoDateTime = longToDateString(now)

  def longToDateString(value: Long): IsoDateTime = new DateTime(value).withZone(DateTimeZone.UTC).toString

  def isNotExpired(e: Option[IsoDateTime]): Boolean = {
    e.exists { expiration =>
      val expireTime = DateTime.parse(expiration).withZone(DateTimeZone.UTC).getMillis
      !(now > expireTime)
    }
  }

  case class Signable(bytes: Array[Byte], encoded: Base64Encoded)

  def buildSignableHash(questionText: String, answer: String, nonce: String): Signable = {
    def safeNull(str: String) = Option(str).getOrElse("")

    // RFC has hash as a concatenation and not an iterative hash like safeMultiHash
    val bytes = HashUtil.hash(HashAlgorithm.SHA512)(
      safeNull(questionText) + safeNull(answer) + safeNull(nonce)
    )

    Signable(bytes, getBase64UrlEncoded(bytes))
  }

  def stringsToResponses(values: Vector[String]): Vector[QuestionResponse] = {
    values.map(QuestionResponse)
  }

  def responsesToStrings(values: Vector[QuestionResponse]): Vector[String] = {
    values.map(_.text)
  }

  def stringToTiming(value: String): BaseTiming = {
    BaseTiming(Some(value))
  }

  def buildQuestion(q: QuestionUsed): Msg.Question = {
    Msg.Question(
      q.questionText,
      Some(q.questionDetail),
      q.nonce,
      q.signatureRequired,
      q.validResponses.map(r => QuestionResponse(r)).toVector,
      Some(BaseTiming(expires_time = Some(q.expiresTime)))
    )
  }

  def buildAnswer(response: Option[String], state: State.QuestionReceived, wallet: WalletAccess)
                 (handler: Try[Msg.Answer] => Unit): Unit = {
    response match {
      case None => ??? // Handle no answer case
      case Some(resp) if !state.question.signature_required =>
        handler(Success(Msg.Answer(resp, None, None)))
      case Some(resp) => // Sig required
        val signable = buildSignableHash(state.question.question_text, resp, state.question.nonce)
        wallet.sign(signable.bytes) { result =>
          handler(
            result.map { sig =>
              val sigBlock = SigBlock(
                sig.toBase64UrlEncoded,
                signable.encoded,
                Seq(sig.verKey)
              )
              Msg.Answer(resp, Some(sigBlock), None)
            }
          )
        }
    }
  }

  def evtToSigBlock(a: SignedAnswerUsed): SigBlock = {
    SigBlock(
      a.signature,
      a.signatureData,
      a.signers
    )
  }

  def questionToEvt(q: Msg.Question): QuestionUsed = {
    QuestionUsed(
      q.question_text,
      q.question_detail.get,
      q.nonce,
      q.signature_required,
      responsesToStrings(q.valid_responses),
      // We default to a MAX_EXPIRATION_TIME because
      // protobuf don't support a None like type (don't even support null)
      // so if expiration is not given, we set it super far in to the future
      // basally providing unlimited time to complete the protocol.
      q.`~timing`.flatMap(t => t.expires_time).getOrElse(MAX_EXPIRATION_TIME)
    )
  }

  def answerToEvt(ans: Msg.Answer): Event = {
    ans
      .`response~sig`
      .map{ sig =>
        SignedAnswerUsed(
          ans.response,
          now.toString,
          sig.signature,
          sig.sig_data,
          sig.signers
        )
      }
      .getOrElse {
        AnswerUsed(ans.response, now.toString)
      }
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
