package com.evernym.verity.protocol.protocols.questionAnswer.v_1_0

// A better name for this protocol would probably be AskQuestion, but QuestionAnswer is used
// because that is what the community has decided to call it.  We may need to influence the
// community to change it.

import java.util.UUID
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.util2.Base64Encoded
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.protocol.engine.context.{ProtocolContextApi, Roster}
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.SegmentKey
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{SigBlock, Timing => BaseTiming}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Role.{Questioner, Responder}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Signal.StatusReport
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.State.AnswerValidity
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.legacy.QuestionAnswerLegacy
import com.evernym.verity.util.Base64Util.{getBase64MultiDecoded, getBase64UrlEncoded}
import com.evernym.verity.util.{HashAlgorithm, HashUtil}
import org.joda.time.{DateTime, DateTimeZone}

import scala.util.{Failure, Success, Try}


class QuestionAnswerProtocol(val ctx: ProtocolContextApi[QuestionAnswerProtocol, Role, Msg, Event, State, String])
  extends Protocol[QuestionAnswerProtocol, Role, Msg, Event, State, String](QuestionAnswerDefinition)
    with QuestionAnswerLegacy {

  import QuestionAnswerProtocol._

  override def handleProtoMsg: (State, Option[Role], Msg) ?=> Any = mainProtoMsg orElse legacyProtoMsg
  def applyEvent: ApplyEvent = applyCommonEvt orElse applyQuestionerEvt orElse applyResponderEvt orElse legacyApply

  // Event Handlers
  def applyCommonEvt: ApplyEvent = {
    case (_: State.Uninitialized , _ , e: Initialized  ) => (State.Initialized(), initialize(e))
    case (_                      , _ , MyRole(n)       ) => (None, setRole(n))
    case (s                      , _ , Error(code, comment)) => recordError(s, code, comment)
    case (_: State.Initialized   , r , q: QuestionUsedRef) =>
      r.selfRole_! match {
        case Questioner => State.QuestionSent(q.segRef)
        case Responder  => State.QuestionReceived(q.segRef)
      }
  }

  def applyQuestionerEvt: ApplyEvent = {
    case (_: State.QuestionSent       , _ , e: AnswerUsedRef    ) =>
      State.AnswerReceived(e.segRef, sigRequired=false, None)
    case (_: State.QuestionSent       , _ , e: SignedAnswerUsedRef) =>
      State.AnswerReceived(e.segRef, sigRequired=true, None)
    case (s: State.AnswerReceived     , _ , e: Validity  ) =>
      s.copy(validStatus = Some(AnswerValidity(e.answerValidity, e.signatureValidity, e.timingValidity)))
  }

  def applyResponderEvt: ApplyEvent = {
    case (State.QuestionReceived(_), _ , e: AnswerUsedRef ) =>
      State.AnswerSent(e.segRef)
    case (State.QuestionReceived(_), _ , e: SignedAnswerUsedRef) =>
      State.AnswerSent(e.segRef)
  }

  // Protocol Msg Handlers
  def mainProtoMsg: (State, Option[Role], Msg) ?=> Any = {
    case (_: State.Initialized     , _ , m: Msg.Question ) => receiveQuestion(m)
    case (s: State.QuestionSent    , _ , m: Msg.Answer   ) => receiveAnswer(s, m)
  }

  // Control Message Handlers
  def handleControl: Control ?=> Any = {
    case m: Ctl.Init            => ctx.apply(Initialized(m.selfId, m.otherId))
    case _: Ctl.GetStatus       => getStatus()
    case m: Ctl.AskQuestion     => ask(m)
    case m: Ctl.AnswerQuestion  => answer(m)
  }

  def receiveQuestion(m: Msg.Question): Unit = {
    ctx.storeSegment(segment=questionToEvt(m)) {
      case Success(s) =>
        ctx.apply(MyRole(Responder.roleNum))
        ctx.apply(QuestionUsedRef(s.segmentKey))

        val signal = Signal.AnswerNeeded(
          m.question_text,
          m.question_detail,
          responsesToStrings(m.valid_responses),
          m.signature_required,
          m.`~timing`.flatMap(_.expires_time)
        )
        ctx.signal(signal)
      case Failure(e) => reportSegStoreFailed("error during processing question")
    }
  }

  def receiveAnswer(s: State.QuestionSent, m: Msg.Answer): Unit = {
    ctx.withSegment[QuestionUsed](s.id) {
      case Success(Some(q)) =>
        ctx.storeSegment(segment=answerToEvt(m)) {
          case Success(stored) =>
            if (m.`response~sig`.isDefined) ctx.apply(SignedAnswerUsedRef(stored.segmentKey))
            else ctx.apply(AnswerUsedRef(stored.segmentKey))

            val notExpired = isNotExpired(Some(q.expiresTime))
            val validResponse = q.validResponses.contains(m.response)
            if (q.signatureRequired) {
              m.`response~sig` match {
                case Some(x) =>
                  val checkData = buildSignableHash(q.questionText, m.response, q.nonce)
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
                      case Success(sigVerifResult) =>
                        answerValidated(m.response, validResponse, sigVerifResult.verified, notExpired)
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
          case Failure(e) => reportSegStoreFailed("error during processing answer")
        }
      case Success(None) => reportSegRetrieveFailed()
      case Failure(e) => reportSegRetrieveFailed(Some(e))
    }
  }

  def answerValidated(answer: String, validResponse: Boolean, validSignature: Boolean, notExpired: Boolean): Unit = {
    ctx.apply(Validity(validResponse, validSignature, notExpired))
    ctx.signal(Signal.AnswerGiven(answer, validResponse, validSignature, notExpired))
  }

  def withAnswerReceived(s: State.AnswerReceived): Unit = {
    s.validStatus.foreach {vs =>
      val eType = if(s.sigRequired) SignedAnswerUsed.defaultInstance else AnswerUsed.defaultInstance
      ctx.withSegment[eType.type](s.id) {
        case Success(Some(a)) =>
          val resp = Some(Signal.AnswerGiven(a.response, vs.answerValidity, vs.signatureValidity, vs.timingValidity))
          ctx.signal(Signal.StatusReport(ctx.getState.getClass.getSimpleName, resp))
        case Success(None) => ctx.signal(Signal.StatusReport(ctx.getState.getClass.getSimpleName, None))
        case Failure(e) => reportSegRetrieveFailed(Some(e))
      }
    }
  }

  def getStatus(): Unit = {
    ctx.getState match {
      case s: State.AnswerReceived => withAnswerReceived(s)
      case _ => ctx.signal(StatusReport(ctx.getState.getClass.getSimpleName, None))
    }
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

    ctx.storeSegment(segment=questionToEvt(questionMsg)) {
      case Success(s) =>
        ctx.apply(QuestionUsedRef(s.segmentKey))
        ctx.send(questionMsg, Some(Responder), Some(Questioner))
      case Failure(e) => reportSegStoreFailed("error during processing ask-question")
    }
  }

  def answer(m: Ctl.AnswerQuestion): Unit = {
    ctx.getState match {
      case state: State.QuestionReceived =>
        ctx.withSegment[QuestionUsed](state.id) {
          case Success(Some(q)) =>
            buildAnswer(m.response, q, ctx.wallet) {
              case Success(answer) =>
                ctx.storeSegment(segment=answerToEvt(answer)) {
                  case Success(s) =>
                    ctx.apply(answerToEvtRef(answer, s.segmentKey))
                    ctx.send(answer, Some(Questioner), Some(Responder))
                  case Failure(e) => reportSegStoreFailed("error during processing answer question")
                }
              case Failure(ex) =>
                val failureMsg = s"Unable build answer - ${ex.getMessage}"
                ctx.logger.info(failureMsg)
                ctx.apply(Error(1, failureMsg))
            }
          case Success(None) => reportSegRetrieveFailed()
          case Failure(e) => reportSegRetrieveFailed(Some(e))
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

  def reportSegRetrieveFailed(e: Option[Throwable]=None): Unit = {
    e match {
      case Some(_e) =>
        ctx.logger.warn(s"could retrieve stored segment with e: ${_e.getMessage}")
        ctx.signal(
          Signal.buildProblemReport("segmented state retrieval failed", ProblemReportCodes.expiredDataRetention)
        )
      case None =>
        ctx.logger.warn(s"segmented state expired")
        ctx.signal(
          Signal.buildProblemReport("segmented state retrieval failed", ProblemReportCodes.expiredDataRetention)
        )
    }
  }

  def reportSegStoreFailed(error: String): Unit = {
    ctx.signal(
      Signal.buildProblemReport(error, ProblemReportCodes.segmentStorageFailure)
    )
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

  def evtToResponses(values: Seq[String]): Seq[QuestionResponse] = {
    values.map(x => QuestionResponse(x))
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

  def buildAnswer(response: Option[String], question: QuestionUsed, wallet: WalletAccess)
                 (handler: Try[Msg.Answer] => Unit): Unit = {
    response match {
      case None => ??? // Handle no answer case
      case Some(resp) if !question.signatureRequired =>
        handler(Success(Msg.Answer(resp, None, None)))
      case Some(resp) => // Sig required
        val signable = buildSignableHash(question.questionText, resp, question.nonce)
        wallet.sign(signable.bytes) { result =>
          handler(
            result.map { signedMsg =>
              val sigBlock = SigBlock(
                signedMsg.signatureResult.toBase64UrlEncoded,
                signable.encoded,
                Seq(signedMsg.signatureResult.verKey)
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
      //TODO: the 'AskQuestion' control message has 'detail' as optional, but it seems it is required here?
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

  def answerToEvtRef(ans: Msg.Answer, ref: SegmentKey): Event =
    ans
      .`response~sig`
      .map(_ => SignedAnswerUsedRef(ref))
      .getOrElse(AnswerUsedRef(ref))

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
      case State.AnswerReceived(a, _, v) => State.Error(code, comment, received=Some(a), validStatus=v)
      case State.QuestionReceived(q) => State.Error(code, comment, question=Some(q))
      case State.AnswerSent(r) => State.Error(code, comment, received=Some(r))
      case s => State.Error(code, comment + s" (From an unknown state: ${s.getClass.getSimpleName})")
    }
  }
}
