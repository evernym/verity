package com.evernym.verity.actor.agent.relationship

trait TagType[T] {
  def tagName: String
  def fromString(meta: String): T
  def toString(t: T): String
}

trait Tag {
  def metaToString: String
}

case object InvalidMetadata extends RuntimeException
