package com.evernym.verity.did.didcomm.v1.messages

import com.evernym.verity.util.OptionUtil

import scala.util.Try

trait AdoptableAck {
  def status: String
}

case class ProblemDescription(en: Option[String], code: String) {
  def bestDescription(): Option[String] = {
    Option(en).flatten.orElse(OptionUtil.blankOption(code))
  }
}

trait AdoptableProblemReport {
  def description: ProblemDescription
  def comment: Option[String] = None// this is a artifact of VCX

  def tryDescription(defaultCode: String = "unknown"): ProblemDescription = {
    Try(Option(description).get).getOrElse(ProblemDescription(None, defaultCode))
  }

  def resolveDescription: String = {
    this
      .description
      .bestDescription()
      .getOrElse(this.comment.getOrElse(""))
  }
}

