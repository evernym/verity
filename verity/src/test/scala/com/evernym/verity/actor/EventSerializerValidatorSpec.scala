package com.evernym.verity.actor

import akka.serialization.SerializerWithStringManifest
import com.evernym.verity.actor.persistence.{InvalidSerializerFound, NoSerializerFound, PersistenceSerializerValidator}
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}

class EventSerializerValidatorSpec extends BasicSpec {

  val testConfig = {
    val config = ConfigFactory
      .load()
      .withValue("akka.actor.serializers.testser", ConfigValueFactory.fromAnyRef("com.evernym.verity.actor.TestSerializer"))
      .withValue("akka.actor.serialization-bindings.\"com.evernym.verity.actor.EventWithInvalidProtoSerBinding\"", ConfigValueFactory.fromAnyRef("testser"))
    new TestAppConfig(Option(config))
  }

  "Event serialization validator" - {

    "when given an event with configured serializer as 'protoser'" - {
      "should be validated successfully" in {
        val events = List(DeprecatedEventMsg(), DeprecatedStateMsg(), PersistentMultiEventMsg())
        events.foreach { event =>
          PersistenceSerializerValidator.validate(event, testConfig)
        }
      }
    }

    "when given an event with configured serializer as NON protobuf serializer" - {
      "should throw InvalidSerializerFound exception" in {
        val ite = EventWithInvalidProtoSerBinding("test")
        intercept[InvalidSerializerFound] {
          PersistenceSerializerValidator.validate(ite, testConfig)
        }
      }
    }

    "when given an event with non configured serializer" - {
      "should throw NoSerializerFound exception" in {
        val tc = EventWithoutProtoSerBinding("test")
        intercept[NoSerializerFound] {
          PersistenceSerializerValidator.validate(tc, testConfig)
        }
      }
    }
  }

}


case class EventWithoutProtoSerBinding(n: String)
case class EventWithInvalidProtoSerBinding(n: String)

class TestSerializer extends SerializerWithStringManifest {
  override def identifier: Int = -1

  override def manifest(o: AnyRef): String = "test"

  override def toBinary(o: AnyRef): Array[Byte] = "test".getBytes()

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = "test"
}