package com.evernym.verity.transformer

import com.evernym.verity.testkit.BasicSpec

case class TestEvent(data:String)

class EventDataTransformerSpec extends BasicSpec {

  val testEvent = TestEvent("dummyData")
  var encryptedEvent: TransformedData = _

  "EventDataTransformer" - {

    "when asked to pack an event" - {
      "should be able to encrypt event" in {
        encryptedEvent = EventDataTransformer.pack(testEvent, "secretKey")
        encryptedEvent shouldBe a[TransformedData]
      }
    }

    "when asked to apply unpack transformation" - {
      "should be able to decrypt event" in {
        val decryptedEvent = EventDataTransformer.unpack(encryptedEvent, "secretKey")
        decryptedEvent shouldBe a[TestEvent]
        assert(decryptedEvent == testEvent)
      }
    }

  }

}
