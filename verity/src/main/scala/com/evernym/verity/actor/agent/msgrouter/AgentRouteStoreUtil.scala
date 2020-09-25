package com.evernym.verity.actor.agent.msgrouter

import java.util.UUID

import com.evernym.verity.protocol.engine.DID

import scala.util.Random


trait RoutingAgentBucketMapper {
  def versionId: String

  protected def getBucketId(forDID: DID)(implicit numberOfBuckets: Int): String

  def getBucketEntityId(forDID: DID)(implicit numberOfBuckets: Int): String =
    versionId + "-" + getBucketId(forDID)
}

object RoutingAgentBucketMapperV1 extends RoutingAgentBucketMapper {
  val versionId = "v1"

  override def getBucketId(did: DID)(implicit numberOfBuckets: Int): String = {
    val bucketId = (math.abs(did.hashCode) % numberOfBuckets).toString
    UUID.nameUUIDFromBytes(bucketId.getBytes).toString
  }
}


trait RoutingAgentUtil {

  //once number of buckets is assigned, it shouldn't be changed
  //if changed, it won't be able to find old values (as most probably it will try to find it in wrong bucket)
  implicit val numberOfBuckets: Int = 100

  val oldBucketMappers: Set[RoutingAgentBucketMapper] = Set.empty
  val latestBucketMapper: RoutingAgentBucketMapper = RoutingAgentBucketMapperV1
  val random: Random = scala.util.Random

  val allBucketMappers: Set[RoutingAgentBucketMapper] = {
    val all = oldBucketMappers ++ Set(latestBucketMapper)
    require(all.size == all.map(_.versionId).size, "different bucket mapper should have different version id")
    all
  }

  def latestBucketMapperVersionId: String = latestBucketMapper.versionId
  def oldBucketMapperVersionIds: Set[String] = oldBucketMappers.map(_.versionId)

  def getBucketEntityId(did: DID): String = latestBucketMapper.getBucketEntityId(did)

  def getBucketPersistenceId(did: DID, versionId: String): String = {
    allBucketMappers.find(_.versionId == versionId).map { bm =>
      bm.getBucketEntityId(did)
    }.getOrElse {
      throw new RuntimeException("no bucket mapper found with version id: " + versionId)
    }
  }

  def getRandomDelayInSecondsForMigrationJob: Int = random.nextInt(100) + 5

  def getRandomIntervalInSecondsForMigrationJob: Int = random.nextInt(300) + 5

}


object RoutingAgentUtil extends RoutingAgentUtil