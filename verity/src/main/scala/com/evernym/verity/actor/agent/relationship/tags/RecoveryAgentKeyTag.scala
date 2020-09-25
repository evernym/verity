package com.evernym.verity.actor.agent.relationship.tags

import com.evernym.verity.actor.agent.relationship.{InvalidMetadata, Tag, TagType}

/**
 * This is used to designate an auth key as a recovery key (provided by edge)
 */
case object RecoveryKeyTag extends Tag {
  val metaToString: String = ""
}

object RecoveryKeyTagType extends TagType[RecoveryKeyTag.type] {
  val tagName = "RECOVERY_KEY"
  def fromString(meta: String) = if (meta == "") RecoveryKeyTag else throw InvalidMetadata
  def toString(t: RecoveryKeyTag.type): String = s"$tagName"
}