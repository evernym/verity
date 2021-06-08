package com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.legacy

import com.evernym.verity.protocol.protocols.CommonProtoTypes.SigBlock
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.State.AnswerValidity
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Msg
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.State

trait StateLegacy {
  case class QuestionSentLegacy(question: Msg.Question) extends State

  case class AnswerReceivedLegacy(question:    Msg.Question,
                                  response:    String,
                                  signature:   Option[SigBlock],
                                  received:    String,
                                  validStatus: Option[AnswerValidity]) extends State

  case class QuestionReceivedLegacy(question: Msg.Question) extends State

  case class AnswerSentLegacy(question:  Msg.Question,
                              response:  String,
                              signature: Option[SigBlock]) extends State

  case class ErrorLegacy(errorCode:   Int,
                         comment:     String,
                         question:    Option[Msg.Question] = None,
                         response:    Option[String] = None,
                         signature:   Option[SigBlock] = None,
                         received:    Option[String] = None,
                         validStatus: Option[AnswerValidity] = None) extends State

}
