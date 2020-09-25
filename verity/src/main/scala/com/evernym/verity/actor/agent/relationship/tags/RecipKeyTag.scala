package com.evernym.verity.actor.agent.relationship.tags

import com.evernym.verity.actor.agent.relationship.{InvalidMetadata, Tag, TagType}

/**
 * This is used to designate a recipient authorized key (only used to pack a message for that recipient key)
 * (currently used in case of "their pairwise did doc", as in that case we don't know if
 * the provided agent key is an edge or a cloud agent)
 */
case object RecipKeyTag extends Tag {
  val metaToString: String = ""
}

object RecipKeyTagType extends TagType[RecipKeyTag.type] {
  val tagName = "RECIP_KEY"
  def fromString(meta: String) = if (meta == "") RecipKeyTag else throw InvalidMetadata
  def toString(t: RecipKeyTag.type): String = s"$tagName"
}