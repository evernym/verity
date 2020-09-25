package com.evernym.verity.util

import akka.actor.ActorSystem
import akka.serialization._
import com.evernym.verity.actor.{ActorMessage, ActorMessageClass, ForIdentifier, ShardUtil}
import com.evernym.verity.actor.agent.msghandler.outgoing.ProtocolSyncRespMsg
import com.evernym.verity.actor.cluster_singleton.{AddMapping, ForKeyValueMapper}
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TestMessages.{TestMessage1, TestMessage2}
import com.twitter.chill.akka.AkkaSerializer


class ActorMessageSerializerSpec extends BasicSpec {

  "AkkaSerializer" - {

    "works successfully for different objects with custom matchers" in {
      val messages: Map[Class[_], AnyRef] = Map(
        classOf[ForIdentifier]        -> ForIdentifier("123", Some("test")),
        classOf[ForKeyValueMapper]    -> ForKeyValueMapper(AddMapping("key1", "value1")),
        classOf[ProtocolSyncRespMsg]  -> ProtocolSyncRespMsg("resp", Option("id")),
        classOf[TestMessage1]         -> TestMessage1("tm-1"),
        classOf[TestMessage2]         -> TestMessage2(),
      )

      val patternMatcher: PartialFunction[Any, Any] = {
        case ForIdentifier(id, _)         => id
        case ForKeyValueMapper(cmd)       => cmd
        case ProtocolSyncRespMsg(cmd, _)  => cmd
        case TestMessage1(_)              => "test-message-1"
        case TestMessage2()               => "test-message-2"
      }
      checkSerialization(messages, patternMatcher)
    }

    "works successfully for different objects with shard extractors" in {
      val messages: Map[Class[_], AnyRef] = Map(
        classOf[ForIdentifier]        -> ForIdentifier("123", Some("test")),
      )
      checkSerialization(messages, ShardUtil.forIdentifierEntityIdExtractor)
    }
  }

  def checkSerialization(messages: Map[Class[_], AnyRef], patternMatcher: PartialFunction[Any, Any]): Unit = {
    messages.foreach { case (clazz, msg) =>
      println("message: " + msg)
      msg.isInstanceOf[ActorMessage] shouldBe true

      //confirms, correct serializer is getting selected (in our case, it should beAkkaSerializer)
      val serializer = node1Serializer.findSerializerFor(msg)
      serializer.getClass.equals(classOf[AkkaSerializer]) shouldBe true

      //message gets serialized on node1
      val matchResultPreSerialization = patternMatcher(msg)
      val serialized = node1Serializer.serialize(msg)
      serialized.isSuccess shouldBe true

      //serialized message gets deserialized on node2
      val deserialized = node2Serializer.deserialize(serialized.get, clazz)
      deserialized.isSuccess shouldBe true
      deserialized.get.equals(msg) shouldBe true
      deserialized.get.isInstanceOf[ActorMessage] shouldBe true
      val matchResultPostDeserialization = patternMatcher(deserialized.get)

      //checks if pattern matching results are same
      matchResultPreSerialization shouldBe matchResultPostDeserialization

      //checks if class loaders are same
      msg.getClass.getClassLoader shouldBe deserialized.get.getClass.getClassLoader
    }
  }

  val node1Serializer: Serialization = SerializationExtension(ActorSystem("node1"))
  val node2Serializer: Serialization = SerializationExtension(ActorSystem("node2"))
}

object TestMessages {
  case class TestMessage1(name: String) extends ActorMessageClass
  case class TestMessage2() extends ActorMessageClass
}

