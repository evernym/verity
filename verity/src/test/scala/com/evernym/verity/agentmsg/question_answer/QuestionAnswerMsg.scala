package com.evernym.verity.agentmsg.question_answer

import com.evernym.verity.did.didcomm.v1.Thread
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.MsgTypeStr
import com.evernym.verity.did.didcomm.v1.messages.MsgId
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{SigBlock, Timing}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Msg.Question
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.QuestionAnswerProtocol.Nonce
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.QuestionResponse
import com.evernym.verity.util2.AgentId

case class QuestionAnswerQuestion(`@type`: MsgTypeStr,
                                  `@id`: AgentId,
                                  questionText: String,
                                  questionDetail: Option[String],
                                  nonce: Nonce,
                                  signatureRequired: Boolean,
                                  validResponses: Vector[QuestionResponse],
                                  `~timing`: Option[Timing])

case class QuestionAnswerResponse(`@type`: MsgTypeStr,
                                  `~thread`: Thread,
                                  response: String,
                                  `response~sig`: Option[SigBlock],
                                  `~timing`: Option[Timing])


case class AskQuestionMsg(`@type`: MsgTypeStr,
                          `@id`: MsgId,
                          `~thread`: Thread,
                          `~for_relationship`: String,
                          question: Question)
