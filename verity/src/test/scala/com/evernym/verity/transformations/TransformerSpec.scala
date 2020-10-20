package com.evernym.verity.transformations

import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.KeyCreated
import com.evernym.verity.actor.persistence.object_code_mapper.DefaultObjectCodeMapper
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.transformations.transformers._
import com.evernym.verity.transformations.transformers.legacy._
import com.evernym.verity.transformations.transformers.v1._


class TransformerSpec extends ActorSpec with BasicSpec {

  "Transformation" - {
    "when tried to pipe multiple transformers" - {
      "should produce desired result" in {
        val intToString = BasicTransformer(
          (x: Int) => x.toString,
          (x: String) => BigDecimal(x).toInt
        )
        val stringToFloat = BasicTransformer(
          (x: String) => BigDecimal(x).toFloat,
          (x: Float) => x.toString
        )

        val composite = intToString andThen stringToFloat
        val int1: Int = 1
        val float1: Float = 1.0f

        composite.execute(int1) shouldBe float1
        composite.undo(float1) shouldBe int1
      }
    }
  }

  "Individual transformers" - {

    "DefaultLegacyEventProtoBufTransformer" - {
      "when tried to 'execute' and 'undo'" - {
        "should provide desired result" in {
          val event = KeyCreated("forDID")
          val transformed = LegacyEventProtoBufTransformer.execute(event)
          LegacyEventProtoBufTransformer.undo(transformed) shouldBe event
        }
      }
    }

    "LegacyJavaSerializationTransformer" - {
      "when tried to 'execute' and 'undo'" - {
        "should provide desired result" in {
          val event = KeyCreated("forDID")
          val input = TransParam(event, Option(DefaultObjectCodeMapper.codeFromObject(event)))
          val postSerializedMsg = LegacyJavaSerializationTransformer.execute(input)
          LegacyJavaSerializationTransformer.undo(postSerializedMsg) shouldBe input
        }
      }
    }

    "LegacyAESEncryptionTransformer" - {
      "when tried to 'execute' and 'undo'" - {
        "should provide desired result" in {
          val event = KeyCreated("forDID")
          val input = TransParam(event, Option(DefaultObjectCodeMapper.codeFromObject(event)))
          val transParam = LegacyJavaSerializationTransformer.execute(input)
          val transformer = new LegacyAESEncryptionTransformer("secret")
          val transformed = transformer.execute(transParam)
          val untransformed = transformer.undo(transformed)
          untransformed.code shouldBe transParam.code
          untransformed.msg shouldBe transParam.msg
        }
      }
    }

    "LegacyEventPersistenceTransformer" - {
      "when tried to 'execute' and 'undo'" - {
        "should provide desired result" in {
          val event = KeyCreated("forDID")
          val input = TransParam(event, Option(DefaultObjectCodeMapper.codeFromObject(event)))
          val javaSerialized = LegacyJavaSerializationTransformer.execute(input)
          val aesEncryptionTransformer = new LegacyAESEncryptionTransformer("secret")
          val transParam = aesEncryptionTransformer.execute(javaSerialized)
          val transformer = new LegacyEventPersistenceTransformer(LEGACY_PERSISTENCE_TRANSFORMATION_ID)
          val transformed = transformer.execute(transParam)
          val untransformed = transformer.undo(transformed)
          untransformed.code shouldBe transParam.code
          untransformed.msg shouldBe transParam.msg
        }
      }
    }

    "DefaultProtoBufTransformer" - {
      "when tried to 'execute' and 'undo'" - {
        "should provide desired result" in {
          val event = KeyCreated("forDID")
          val transformed = DefaultProtoBufTransformerV1.execute(event)
          val untransformed = DefaultProtoBufTransformerV1.undo(transformed)
          untransformed shouldBe event
        }
      }
    }
  }

  "Composite Transformers" - {

    "LegacyEventCompositeTransformer" - {
      "should work properly" in {

        val compositeTransformer = createLegacyEventTransformer("secret")

        val event = KeyCreated("forDID")
        val transformed = compositeTransformer.execute(event)
        val untransformed = compositeTransformer.undo(transformed)

        untransformed shouldBe event
      }
    }

    "persistence transformer v1" - {
      val compositeTransformer = createPersistenceTransformerV1("secret", new IdentityTransformer)

      val event = KeyCreated("forDID")
      val transformed = compositeTransformer.execute(event)
      val untransformed = compositeTransformer.undo(transformed)

      untransformed shouldBe event
    }
  }

}

case class NestedTestClass(i: Int, name: String)
case class TestClass(age: Int, nested: NestedTestClass, colors: List[String] )
