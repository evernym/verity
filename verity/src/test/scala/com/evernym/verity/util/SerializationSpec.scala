package com.evernym.verity.util

import com.evernym.verity.testkit.BasicSpec

case class Event(data:String)

class SerializationSpec extends BasicSpec {

  "Serialization" - {
      "should serialise any object into byte array and then deserialise back to same object" in {
        val event = Event("data")
        val serialisedData = Serialization.serialise(event)

        serialisedData shouldBe a[Array[_]]
        serialisedData.head shouldBe a[java.lang.Byte]

        val deserialisedData = Serialization.deserialise(serialisedData)

        deserialisedData shouldBe a[Event]
        assert(deserialisedData == event)
      }
  }

}
