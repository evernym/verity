package com.evernym.verity.storageAPI

import com.evernym.verity.testkit.BasicSpec
import akka.actor.ActorSystem
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.storage_services.leveldb.LeveldbAPI
import com.typesafe.config.{Config, ConfigValueFactory}


class LoadStorageAPISpec extends BasicSpec {

  private def unknownStorageApiConfig(): Config = (new TestAppConfig)
    .config
    .withValue("verity.blob-store.storage-service", ConfigValueFactory.fromAnyRef("cannot find"))

  private def storageApiConfig(): Config = (new TestAppConfig)
    .config
    .withValue("verity.blob-store.storage-service", ConfigValueFactory.fromAnyRef("com.evernym.verity.storage_services.leveldb.LeveldbAPI"))
    .withValue("verity.blob-store.local-store-path", ConfigValueFactory.fromAnyRef("/tmp/verity/leveldb"))

  val appConfig = new TestAppConfig

  lazy implicit val system: ActorSystem = ActorSystem("storage-api-test-system", appConfig.config)


  "StorageAPI ClassLoader" - {

    "when loading a storage API " - {
      "should succeed in loading" in {
        StorageAPI.loadFromConfig(new TestAppConfig(Some(storageApiConfig()))).asInstanceOf[LeveldbAPI]
      }

      "should fail loading unknown API" in {
        intercept[ClassNotFoundException] {
          StorageAPI.loadFromConfig(new TestAppConfig(Some(unknownStorageApiConfig())))
        }
      }
    }
  }
}
