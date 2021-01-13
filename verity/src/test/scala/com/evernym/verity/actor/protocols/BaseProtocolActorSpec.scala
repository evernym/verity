package com.evernym.verity.actor.protocols

import akka.actor.ActorRef
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.actor.agent.ThreadContextDetail
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.actor.{ForIdentifier, ShardUtil}
import com.evernym.verity.protocol.engine.{DID, PinstIdPair, ProtoDef}
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

trait BaseProtocolActorSpec
  extends PersistentActorSpec
    with BasicSpec
    with ShardUtil {

  /**
   * this is temporary to make the mock controller flow working
   * we should refactor it and not required to override below config
   *
   * @return
   */
  override def overrideConfig: Option[Config] = Option {
    ConfigFactory parseString {
      s"akka.actor.serialize-messages = off"
    }
  }

  /**
   *
   * @param myDID my DID
   * @param theirDIDOpt their DID, needed if protocol is executed between two different domains
   * @param protoDef protocol def
   * @param threadContextDetailOpt optional thread context detail
   */
  def buildSetupController(myDID: DID,
                           theirDIDOpt: Option[DID],
                           protoDef: ProtoDef,
                           threadContextDetailOpt: Option[ThreadContextDetail]=None): SetupController = {
    SetupController(
      ControllerData(
        myDID,
        theirDIDOpt,
        agentActorContext,
        PinstIdPair(myDID, protoDef),
        threadContextDetailOpt))
  }

  //checks if the message is coming from correct controller actor
  //not a perfect way may be, so we can refactor this if we don't like or it doesn't work in all the cases
  def expectMsgTypeFrom[T](id: String, max: Option[FiniteDuration] = None)(implicit t: ClassTag[T]): T = {
    val m = max.map(expectMsgType[T](_)).getOrElse(expectMsgType[T])
    assert(lastSender.toString().contains(id), s"msg received from different controller")
    m
  }

  val MOCK_CONTROLLER_REGION_NAME = "MockIssueCredControllerActor"

  lazy val mockControllerRegion: ActorRef = {
    ClusterSharding(system).shardRegion(MOCK_CONTROLLER_REGION_NAME)
  }

  def sendToMockController(id: String, cmd: Any): Unit = {
    mockControllerRegion ! ForIdentifier(id, cmd)
  }
}