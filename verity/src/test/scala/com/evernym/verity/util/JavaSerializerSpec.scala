package com.evernym.verity.util

import com.evernym.verity.actor.serializers.JavaSerializer
import com.evernym.verity.testkit.BasicSpec

case class Event(data:String)

class JavaSerializerSpec extends BasicSpec {

  "JavaSerializer" - {
      "should serialise any object into byte array and then deserialize back to same object" in {
        val event = Event("data")
        val serialisedData = JavaSerializer.serialise(event)

        serialisedData shouldBe a[Array[_]]
        serialisedData.head shouldBe a[java.lang.Byte]

        val deserialisedData = JavaSerializer.deserialise(serialisedData)

        deserialisedData shouldBe a[Event]
        assert(deserialisedData == event)
      }
  }

}
