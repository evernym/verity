package com.evernym.verity.actor.agent.relationship.tags

import com.evernym.verity.actor.agent.relationship.{InvalidMetadata, Tag, TagType}

/**
 * This is used to designate an agent key
 * (currently used in case of "their pairwise did doc", as in that case we don't know if
 * the provided agent key is an edge or a cloud agent)
 */
case object AgentKeyTag extends Tag {
  val metaToString: String = ""
}

object AgentKeyTagType extends TagType[AgentKeyTag.type] {
  val tagName = "AGENT_KEY"
  def fromString(meta: String) = if (meta == "") AgentKeyTag else throw InvalidMetadata
  def toString(t: AgentKeyTag.type): String = s"$tagName"
}