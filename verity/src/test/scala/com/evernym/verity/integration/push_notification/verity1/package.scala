package com.evernym.verity.integration.push_notification

package object verity1 {
  case class AskQuestion(text: String, detail: Option[String], valid_responses: Vector[String], signature_required: Boolean)
  case class Question(text: String, detail: String, signature_required: Boolean, valid_responses: List[String])
}
