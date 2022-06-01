package com.evernym.verity.protocol.engine.asyncapi

import com.evernym.verity.actor.StorageInfo
import com.evernym.verity.storage_services.{BucketLifeCycleUtil, StorageAPI}
import com.evernym.verity.util2.RetentionPolicy

import java.util.UUID
import scala.concurrent.Future


class BlobStorageUtil(bucketName: String, storageAPI: StorageAPI) {

  //NOTE: expectation is that the payload is not encrypted as it will be ready by a different service (endorser service)
  def saveInBlobStore(payload: Array[Byte], dataRetentionPolicy: Option[RetentionPolicy]): Future[StorageInfo] = {
    val segmentAddress = UUID.randomUUID().toString
    val lifecycleAddress = BucketLifeCycleUtil.lifeCycleAddress(dataRetentionPolicy.map(_.elements.expiryDaysStr), segmentAddress)
    storageAPI.put(bucketName, lifecycleAddress, payload)
  }

}
