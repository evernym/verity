package com.evernym.verity.agentmsg.msgcodec

import java.time.ZonedDateTime

import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.agentmsg.msgfamily.BundledMsg_MFV_0_5
import com.evernym.verity.agentmsg.msgfamily.routing.FwdReqMsg_MFV_1_0
import com.evernym.verity.util.MessagePackUtil._
import com.evernym.verity.util.TimeZoneUtil.UTCZoneId
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import org.json.JSONObject
import com.evernym.verity.testkit.BasicSpec

class MsgCodecSpec
  extends BasicSpec {

  def assertSerialized(expectedJson: String, serializedJson: String, checkFieldOrder: Boolean=true): Unit = {
    if (checkFieldOrder) {
      serializedJson shouldBe expectedJson
    } else {
      new JSONObject(serializedJson) equals new JSONObject(expectedJson)
    }
  }

  "MsgCodecs" - {

    "when serializing native object with NON binary data to json" - {
      "should be able to produce same json with different serializers" in {
        val person = Employee(1, "Alice", "address-1", Option(123456l), Option(111))
        val expectedJson = """{"id":1,"name":"Alice","address":"address-1","ssn":123456,"phoneNumber":111}"""
        val jacksonSerializedJson = JacksonMsgCodec.toJson(person)
        assertSerialized(expectedJson, jacksonSerializedJson)
      }
    }

    "when deserializing json with NON binary data to native object" - {
      "should be able to produce same native object with different deserializers" in {
        val json = """{"id":"1","name":"Alice", "address":"address-1","ssn":123456,"phoneNumber":111}"""
        val person = Employee(1, "Alice", "address-1", Option(123456l), Option(111))
        val jacksonDeserializedMsg1 = JacksonMsgCodec.fromJson[Employee](json)
        jacksonDeserializedMsg1 shouldBe person

        jacksonDeserializedMsg1.ssn match {
          case Some(sn: Long) => sn shouldBe 123456
          case x              => throw new RuntimeException("invalid deserialized value: " + x)
        }
        jacksonDeserializedMsg1.phoneNumber match {
          case Some(ph: Integer)  => ph shouldBe 111
          case x              => throw new RuntimeException("invalid deserialized value: " + x)
        }
      }
    }

    "when serializing native object with binary data to json" - {
      "should be able to produce same json with different serializers" in {
        val personIdentity = EmployeeBiometric("photo".getBytes, Array(1,2,3, -126, -127, -128))
        val expectedJson = """{"photo":[112,104,111,116,111],"fingerPrint":[1,2,3,-126,-127,-128]}"""

        val jacksonSerializedJson = JacksonMsgCodec.toJson(personIdentity)
        assertSerialized(expectedJson, jacksonSerializedJson)
      }
    }

    "when deserializing json with binary data to native object" - {
      "should be able to produce same native object with different deserializers" in {
        val json = """{"photo":[112,104,111,116,111],"fingerPrint":[1,2,3,-126,-127,-128]}"""
        val expectedNativeMsg = EmployeeBiometric("photo".getBytes, Array(1,2,3, -126, -127, -128))

        val jacksonDeserializedMsg = JacksonMsgCodec.fromJson[EmployeeBiometric](json)
        jacksonDeserializedMsg.canEqual(expectedNativeMsg) shouldBe true
      }
    }

    "when serializing native object with small double number" - {
      "should be able to produce same json with different serializers" in {
        val employeePackage = EmployeePackage(9999999.99999999)
        val expectedJson = """{"salary":9999999.99999999}"""
        val jacksonSerializedJson = JacksonMsgCodec.toJson(employeePackage)
        assertSerialized(expectedJson, jacksonSerializedJson)
      }
    }

    "when serializing native object with large double number" - {
      "should be able to produce same json with different serializers" in {
        val employeePackage = EmployeePackage(1573564455.000000016)
        val expectedJson = """{"salary":1.573564455E9}"""   //expected json is as per jackson's implementation
        val jacksonSerializedJson = JacksonMsgCodec.toJson(employeePackage)
        assertSerialized(expectedJson, jacksonSerializedJson)
      }
    }

    "when deserializing json with large double number to native object" - {
      "should be able to produce same native object with different deserializers" in {
        val json = """{"salary":1.573564455E9}"""
        val expectedNativeMsg = EmployeePackage(1573564455.000000016)
        val jacksonSerializedJson = JacksonMsgCodec.fromJson[EmployeePackage](json)
        jacksonSerializedJson shouldBe expectedNativeMsg
      }
    }

    "when serializing native object with java time data types" - {
      "should be able to produce same json with different serializers" in {
        val employeeRoster = EmployeeRoster(employeeJoiningDate)
        val expectedJson = """{"joiningDate":"2019-11-12T13:14:15.000000016Z"}"""
        val jacksonSerializedJson = JacksonMsgCodec.toJson(employeeRoster)
        assertSerialized(expectedJson, jacksonSerializedJson)
      }
    }

    "when deserializing json to native object with java time data types" - {
      "should be able to produce same native object with different deserializers" in {
        val json = """{"joiningDate":1573564455.000000016}"""
        val expectedNativeMsg = EmployeeRoster(employeeJoiningDate)
        val jacksonDeserializedMsg = JacksonMsgCodec.fromJson[EmployeeRoster](json)
        jacksonDeserializedMsg shouldBe expectedNativeMsg
      }
    }

    "when deserializing json to a map" - {
      "should be able to produce same map with different serializers" in {
        val json = """{"name":"Alice", "address":"address-1"}"""
        val map = Map("name" -> "Alice", "address" -> "address-1")

        val jacksonDeserializedMsg1 = JacksonMsgCodec.fromJson[Map[String, String]](json)
        jacksonDeserializedMsg1 shouldBe map
      }
    }

    "when serializing native object with json object" - {
      "should be able to produce same json with different serializers" in {
        val packedMsgOutput = """{"k1":"v1"}""".getBytes
        val packedMsgString = new String(packedMsgOutput)
        val fwdMsgWithJSONObject = FwdReqMsg_MFV_1_0("fwd-type", "route", new JSONObject(packedMsgString), Some("some-type"))
        val expectedJson = """{"@type":"fwd-type","@fwd":"route","@msg":{"k1":"v1"},"fwdMsgType":"some-type"}"""
        val jacksonSerializedJson = JacksonMsgCodec.toJson(fwdMsgWithJSONObject)
        assertSerialized(expectedJson, jacksonSerializedJson)
      }
    }

    "when serializing 'message packed' bundled msg to json" - {
      "should be able to produce same json with different serializers" in {
        val msgs = List(Employee(1, "Alice", "address-1", None, None),
          EmployeeBiometric("photo".getBytes, Array(1,2,3, -126, -127, -128)))
        val messagePackedMsgs = msgs.map(convertNativeMsgToPackedMsg)
        val bundledMsg = BundledMsg_MFV_0_5(messagePackedMsgs)
        val expectedJson = """{"bundled":[[-125,-94,105,100,1,-92,110,97,109,101,-91,65,108,105,99,101,-89,97,100,100,114,101,115,115,-87,97,100,100,114,101,115,115,45,49],[-126,-91,112,104,111,116,111,-107,112,104,111,116,111,-85,102,105,110,103,101,114,80,114,105,110,116,-106,1,2,3,-48,-126,-48,-127,-48,-128]]}"""

        val jacksonSerializedJson = JacksonMsgCodec.toJson(bundledMsg)
        assertSerialized(expectedJson, jacksonSerializedJson)
      }
    }
  }

  "when deserializing json with missing optional value to native" - {
    "should be able to deserialize successfully" in {
      val json = """{"attr1":"value1","attr2":5,"attr3":[4,5]}""" //testing missing attr4
      val expectedNativeMsg = TestMsg("value1", 5, Array(4, 5), None)
      val jacksonDeserializedMsg = JacksonMsgCodec.fromJson[TestMsg](json)
      jacksonDeserializedMsg.canEqual(expectedNativeMsg) shouldBe true
    }
  }

  //TODO: if we set "FAIL_ON_MISSING_CREATOR_PROPERTIES" as true for JacksonMsgCodec,
  // then this test fails.
  "when deserializing json with field names with special characters" - {
    "should be able to deserialize successfully" in {
      val json = """{"@type":"fwd-type","@fwd":"route","@msg":{"k1":"v1"}}"""
      val expectedNativeMsg = FwdReqMsg_MFV_1_0("fwd-type", "route", new JSONObject("""{"k1":"v1"}"""), Some("some-type"))
      val jacksonDeserializedMsg = JacksonMsgCodec.fromJson[FwdReqMsg_MFV_1_0](json)
      jacksonDeserializedMsg.canEqual(expectedNativeMsg) shouldBe true
    }
  }

  "when deserializing json with missing required value of a type Array to a class with corresponding default value" - {
    "should be able to deserialize successfully" in {
      val json = """{"attr1":"value1","attr2":5}"""     //testing missing attr3
      val expectedNativeMsg = TestMsg("value1", 5, Array.empty, None)
      val jacksonDeserializedMsg = JacksonMsgCodec.fromJson[TestMsg](json)
      jacksonDeserializedMsg equals expectedNativeMsg
    }
  }

  "when deserializing json with missing required value of a type int to a class with corresponding default value" - {
    "should be able to deserialize successfully" in {
      val json = """{"attr1":"value1"}"""               //testing missing attr2
      val expectedNativeMsg = TestMsg("value1", -1, Array.empty, None)
      val jacksonDeserializedMsg = JacksonMsgCodec.fromJson[TestMsg](json)
      jacksonDeserializedMsg.canEqual(expectedNativeMsg) shouldBe true
    }
  }

  "when deserializing json with missing required value of a type string to a class with corresponding default value" - {
    "should be able to deserialize successfully" in {
      val json = """{}"""                                //testing missing attr1
      val expectedNativeMsg = TestMsg("default", -1, Array.empty, None)
      val jacksonDeserializedMsg = JacksonMsgCodec.fromJson[TestMsg](json)
      jacksonDeserializedMsg.canEqual(expectedNativeMsg) shouldBe true
    }
  }

  "when deserializing json with null value of a type string to a class with corresponding default value" - {
    "should be able to deserialize successfully" in {
      val json = """{"attr":null}"""
      val expectedNativeMsg = TestMsg("default", -1, Array.empty, None)
      val jacksonDeserializedMsg = JacksonMsgCodec.fromJson[TestMsg](json)
      jacksonDeserializedMsg.canEqual(expectedNativeMsg) shouldBe true
    }
  }

  val employeeJoiningDate = ZonedDateTime.of(
    2019,
    11,
    12,
    13,
    14,
    15,
    16,
    UTCZoneId)

}

case class Employee(id: Int,
                    name: String,
                    address: String,
                    @JsonDeserialize(contentAs = classOf[Long]) ssn: Option[Long],
                    @JsonDeserialize(contentAs = classOf[Integer]) phoneNumber: Option[Integer])

case class EmployeeBiometric(photo: Array[Byte], fingerPrint: Array[Byte])
case class EmployeeRoster(joiningDate: ZonedDateTime)
case class EmployeePackage(salary: Double)
case class FwdMsg(`@type`: String, `@fwd`: String, `@msg`: JSONObject)
case class TestMsg(attr1: String="default", attr2: Int = -1, attr3: Array[Byte]=Array.empty, attr4: Option[Vector[String]])