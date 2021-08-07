package com.evernym.verity.actor.agent.msgrouter

import java.util.UUID

import com.evernym.verity.did.DidStr

import scala.util.Random


trait RoutingAgentBucketMapper {
  def versionId: String

  protected def bucketIdByRouteDID(did: DidStr)(implicit numberOfBuckets: Int): Int

  final def entityIdByRouteDID(did: DidStr)(implicit numberOfBuckets: Int): String =
    entityIdByBucketId(bucketIdByRouteDID(did))

  final def entityIdByBucketId(bucketId: Int): String =
    versionId + "-" + UUID.nameUUIDFromBytes(bucketId.toString.getBytes).toString
}

object RoutingAgentBucketMapperV1 extends RoutingAgentBucketMapper {
  val versionId = "v1"

  override def bucketIdByRouteDID(did: DidStr)(implicit numberOfBuckets: Int): Int = {
    // this logic may have a bug if 'did.hashCode' is equal to Int.MinValue
    // as in that case the math.abs(Int.MinValue) is still Int.MinValue
    // and it will generate negative number (which wasn't the original intention).

    // the recommended way is to first do 'modulo' operation and then take 'absolute' value
    // but since this logic is already used and routing DIDs are sharded accordingly
    // we can't change this now without making sure how we wants to handle backward compatibility

    //NOTE: don't change this logic as it won't be backward compatible
    math.abs(did.hashCode) % numberOfBuckets
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

  def getBucketEntityId(did: DidStr): String = latestBucketMapper.entityIdByRouteDID(did)

  def getBucketPersistenceId(did: DidStr, versionId: String): String = {
    allBucketMappers.find(_.versionId == versionId).map { bm =>
      bm.entityIdByRouteDID(did)
    }.getOrElse {
      throw new RuntimeException("no bucket mapper found with version id: " + versionId)
    }
  }

  def getRandomIntervalInSecondsForMigrationJob: Int = random.nextInt(300) + 5

}


object RoutingAgentUtil extends RoutingAgentUtil