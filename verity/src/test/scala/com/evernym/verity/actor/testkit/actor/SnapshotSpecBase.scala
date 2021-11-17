package com.evernym.verity.actor.testkit.actor

import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.evernym.verity.actor.PersistentMsg
import com.evernym.verity.actor.persistence.stdPersistenceId
import com.evernym.verity.actor.persistence.transformer_registry.HasTransformationRegistry
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.SALT_EVENT_ENCRYPTION
import com.evernym.verity.observability.metrics.CustomMetrics.AS_ACTOR_AGENT_STATE_SIZE
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.transformations.transformers.<=>
import com.evernym.verity.transformations.transformers.v1.createPersistenceTransformerV1
import com.evernym.verity.util.Util
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}


trait SnapshotSpecBase
  extends HasTransformationRegistry
    with Eventually {
  this: PersistentActorSpec with BasicSpec =>

  def checkStateSizeMetrics(actorClass: String, expectSize: Double): Unit = {
    eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
      val stateSizeMetrics =
        testMetricsBackend.filterHistogramMetrics(AS_ACTOR_AGENT_STATE_SIZE)
          .filter(_._1.tags.exists(_ == ("actor_class", actorClass)))

      stateSizeMetrics.size shouldBe 1 //histogram metrics
      checkStateSizeSum(stateSizeMetrics.head._2.sum, expectSize)
    }
  }

  def checkStateSizeSum(actualStateSizeSum: Double, expectedStateSizeSum: Double): Unit = {
    val diff = expectedStateSizeSum - actualStateSizeSum
    val average = (expectedStateSizeSum + actualStateSizeSum) / 2
    val perDiff = (diff / average) * 100

    //this is because of 1% error margin as explained here: https://github.com/kamon-io/Kamon/blob/master/core/kamon-core/src/main/scala/kamon/metric/Histogram.scala#L33
    perDiff <= 1 shouldBe true
  }

  def fetchEvent[T](): T = {
    val rawEvent = persTestKit.expectNextPersistedType[PersistentMsg](persId)
    eventTransformation.undo(rawEvent).asInstanceOf[T]
  }

  def totalEvents: Int = {
    persTestKit.persistedInStorage(persId).size
  }

  def totalSnapshots: Int = {
    snapTestKit.persistedInStorage(persId).size
  }

  def latestSnapshot: Option[StateType] = {
    snapTestKit.persistedInStorage(persId).map(_._2).lastOption.map { ps =>
      transformer.undo(ps.asInstanceOf[PersistentMsg]).asInstanceOf[StateType]
    }
  }

  type StateType

  def appConfig: AppConfig
  protected def regionActorName: String
  protected def actorEntityId: String
  protected def transformer: Any <=> PersistentMsg = createPersistenceTransformerV1(encrKey, appConfig.getStringReq(SALT_EVENT_ENCRYPTION))
  protected def persId: String = stdPersistenceId(regionActorName, actorEntityId)
  private def encrKey: String = {
    val secret = Util.saltedHashedName(actorEntityId + "actor-wallet", appConfig)
    Util.getEventEncKey(secret, appConfig)
  }

  lazy val eventTransformation = persistenceTransformerV1
  lazy val snapshotTransformation = persistenceTransformerV1
  override lazy val persistenceEncryptionKey: String = encrKey

  final override def overrideConfig: Option[Config] = Option(
    specificConfig.getOrElse(ConfigFactory.empty).withFallback(baseSnapshotConfig)
  )

  private def baseSnapshotConfig: Config = {
    ConfigFactory.empty
      .withFallback(EventSourcedBehaviorTestKit.config)
      .withFallback(PersistenceTestKitSnapshotPlugin.config)
  }

  def specificConfig: Option[Config] = None
}
