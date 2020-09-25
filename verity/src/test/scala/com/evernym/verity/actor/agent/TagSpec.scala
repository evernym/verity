package com.evernym.verity.actor.agent

import com.evernym.verity.testkit.BasicSpec

import com.evernym.verity.actor.agent.relationship.{InvalidMetadata, Tag, TagType}

class TagSpec extends BasicSpec {

  "A Tag" - {
    "can be simple (no-metadata)" in {
      case object RecoveryTag extends Tag {
        val metaToString: String = ""
      }

      object RecoveryTagType extends TagType[RecoveryTag.type] {
        val tagName = "RECOVERY"
        def fromString(meta: String) = if (meta == "") RecoveryTag else throw InvalidMetadata
        def toString(t: RecoveryTag.type): String = s"$tagName"
      }

      val t1 = RecoveryTag
      val t1_ = RecoveryTagType.fromString("")
    }
    "can have a single String value" in {

      case class RecoveryTag(group: String) extends Tag {
        def metaToString: String = group
      }

      object RecoveryTagType extends TagType[RecoveryTag] {
        val tagName = "RECOVERY"
        def fromString(meta: String): RecoveryTag = RecoveryTag(meta)
        def toString(t: RecoveryTag): String = s"$tagName(${t.metaToString})"
      }

      val t1 = RecoveryTag("A")
      val t2 = RecoveryTag("B")
      val t1_ = RecoveryTagType.fromString("A")
      val t2_ = RecoveryTagType.fromString("B")

      t1 should be (t1_)
      t2 should be (t2_)
    }
    "can have a single enum value" in {

      object Group extends Enumeration {
        protected case class Val(str: String) extends super.Val {  }
        import scala.language.implicitConversions
        implicit def valueToVal(x: Value): Val = x.asInstanceOf[Val]

        def fromStr(s: String): Val = {
          values
            .find(_.str == s)
            .map(valueToVal)
            .getOrElse(throw new RuntimeException("invalid group"))
        }

        val A = Val("A")
        val B = Val("B")
      }
      import Group._

      case class RecoveryTag(group: Group.Value) extends Tag {
        def metaToString = group.str
      }

      object RecoveryTagType extends TagType[RecoveryTag] {
        val tagName = "RECOVERY"
        def fromString(meta: String): RecoveryTag = RecoveryTag(Group.fromStr(meta))
        def toString(t: RecoveryTag): String = s"$tagName(${t.metaToString})"
      }

      val t1 = RecoveryTag(A)
      val t2 = RecoveryTag(B)
      val t1_ = RecoveryTagType.fromString("A")
      val t2_ = RecoveryTagType.fromString("B")

      t1 should be (t1_)
      t2 should be (t2_)
    }
  }
}
