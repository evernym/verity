package com.evernym.verity.actor.agent.relationship.tags

import com.evernym.verity.actor.agent.relationship.{InvalidMetadata, Tag, TagType}

/**
 * This is used to designate an auth key which belongs to an edge agent
 */
case object EdgeAgentKeyTag extends Tag {
  val metaToString: String = ""
}

object EdgeAgentKeyTagType extends TagType[EdgeAgentKeyTag.type] {
  val tagName = "EDGE_AGENT_KEY"
  def fromString(meta: String) = if (meta == "") EdgeAgentKeyTag else throw InvalidMetadata
  def toString(t: EdgeAgentKeyTag.type): String = s"$tagName"
}