package com.evernym.verity.agentmsg.question_answer

import com.evernym.verity.agentmsg.msgfamily.pairwise.MsgThread
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{SigBlock, Timing}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Msg.Question
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.QuestionAnswerProtocol.Nonce
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.QuestionResponse
import com.evernym.verity.AgentId
import com.evernym.verity.protocol.engine.{MsgId, MsgTypeStr}

case class QuestionAnswerQuestion(`@type`: MsgTypeStr,
                                  `@id`: AgentId,
                                  questionText: String,
                                  questionDetail: Option[String],
                                  nonce: Nonce,
                                  signatureRequired: Boolean,
                                  validResponses: Vector[QuestionResponse],
                                  `~timing`: Option[Timing])

case class QuestionAnswerResponse(`@type`: MsgTypeStr,
                                  `~thread`: MsgThread,
                                  response: String,
                                  `response~sig`: Option[SigBlock],
                                  `~timing`: Option[Timing])


case class AskQuestionMsg(`@type`: MsgTypeStr,
                          `@id`: MsgId,
                          `~thread`: MsgThread,
                          `~for_relationship`: String,
                          question: Question)
