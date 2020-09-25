package com.evernym.verity.actor.agent.relationship.tags

import com.evernym.verity.actor.agent.relationship.{InvalidMetadata, Tag, TagType}

/**
 * This is used to designate an auth key which belongs to a cloud agent
 */
case object CloudAgentKeyTag extends Tag {
  val metaToString: String = ""
}

object CloudAgentKeyTagType extends TagType[CloudAgentKeyTag.type] {
  val tagName = "CLOUD_AGENT_KEY"
  def fromString(meta: String) = if (meta == "") CloudAgentKeyTag else throw InvalidMetadata
  def toString(t: CloudAgentKeyTag.type): String = s"$tagName"
}