package com.evernym.verity.observability.logs

trait LoggerIdentity {
  def idTuplePair: (String, String)
}