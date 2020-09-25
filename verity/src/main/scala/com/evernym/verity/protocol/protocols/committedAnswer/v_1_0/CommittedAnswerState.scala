package com.evernym.verity.protocol.protocols.committedAnswer.v_1_0

sealed trait State

object State {

  case class Uninitialized() extends State

  case class Initialized() extends State

  case class Error(errorCode:   Int,
                   comment:     String,
                   question:    Option[Msg.Question] = None,
                   response:    Option[String] = None,
                   signature:   Option[Sig] = None,
                   received:    Option[String] = None,
                   validStatus: Option[AnswerValidity] = None) extends State

  // Questioner STATES:

  case class QuestionSent(question: Msg.Question) extends State

  case class AnswerReceived(question:    Msg.Question,
                            response:    String,
                            signature:   Option[Sig],
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
                        signature: Option[Sig]) extends State
}

