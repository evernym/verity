package com.evernym.verity.urlshortener

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentType, ContentTypes}
import com.evernym.verity.actor.StorageInfo
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.testkit.BasicAsyncSpec
import com.evernym.verity.util.Base64Util
import com.evernym.verity.util2.ExecutionContextProvider
import com.typesafe.config.{Config, ConfigFactory}
import org.mockito.ArgumentCaptor
import org.mockito.scalatest.MockitoSugar
import org.scalatest.concurrent.ScalaFutures

import java.nio.charset.StandardCharsets
import scala.concurrent.Future

class S3ShortenerSvcSpec
  extends ActorSpec
    with BasicAsyncSpec
    with MockitoSugar
    with ScalaFutures {

  override def executionContextProvider: ExecutionContextProvider = new ExecutionContextProvider(appConfig)

  class TestS3ShortenerSvc(mock: StorageAPI) extends S3ShortenerSvc(appConfig, executionContext) {
    override def getStorageAPI(implicit as: ActorSystem): StorageAPI = mock
  }

  val shortUrlPrefix: String = "https://sho.rt/"
  val bucketName: String = "bucket"
  val idLength: Int = 8
  val retryCount: Int = 3

  val inviteJson = """{"label": "Name"}"""
  val invitationData: Array[Byte] = inviteJson.getBytes(StandardCharsets.UTF_8)
  val longUrlCon: String = "http://long.url/agency/msg?c_i=" + Base64Util.getBase64UrlEncoded(invitationData)
  val longUrlOob: String = "http://long.url/agency/msg?oob=" + Base64Util.getBase64UrlEncoded(invitationData)

  "S3 shortener service provider" - {
    "when S3 respond with success" - {
      "connection invitation should succeed" in {
        val mockApi = mock[StorageAPI]
        val service = new TestS3ShortenerSvc(mockApi)
        val getBucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val getIdCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val bucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val idCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val dataCaptor: ArgumentCaptor[Array[Byte]] = ArgumentCaptor.forClass(classOf[Array[Byte]])
        val contentTypeCaptor: ArgumentCaptor[ContentType] = ArgumentCaptor.forClass(classOf[ContentType])

        when(mockApi.get(any[String], any[String])).thenReturn (
          Future.successful(None)
        )
        when(mockApi.put(any[String], any[String], any[Array[Byte]], any[ContentType])).thenReturn (
          Future.successful(StorageInfo())
        )

        service.shortenURL(UrlInfo(longUrlCon))(system) map { shortUrl =>
          verify(mockApi, times(1))
            .get(getBucketCaptor.capture(), getIdCaptor.capture())
          verify(mockApi, times(1))
            .put(bucketCaptor.capture(), idCaptor.capture(), dataCaptor.capture(), contentTypeCaptor.capture())

          val id = idCaptor.getValue
          getBucketCaptor.getValue shouldBe bucketName
          getIdCaptor.getValue shouldBe id
          bucketCaptor.getValue shouldBe bucketName
          dataCaptor.getValue shouldBe invitationData
          contentTypeCaptor.getValue shouldBe ContentTypes.`application/json`
          shortUrl shouldBe shortUrlPrefix + id
        }
      }

      "out-of-band invitation should succeed" in {
        val mockApi = mock[StorageAPI]
        val service = new TestS3ShortenerSvc(mockApi)
        val getBucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val getIdCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val bucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val idCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val dataCaptor: ArgumentCaptor[Array[Byte]] = ArgumentCaptor.forClass(classOf[Array[Byte]])
        val contentTypeCaptor: ArgumentCaptor[ContentType] = ArgumentCaptor.forClass(classOf[ContentType])

        when(mockApi.get(any[String], any[String])).thenReturn (
          Future.successful(None)
        )
        when(mockApi.put(any[String], any[String], any[Array[Byte]], any[ContentType])).thenReturn (
          Future.successful(StorageInfo())
        )

        service.shortenURL(UrlInfo(longUrlCon))(system) map { shortUrl =>
          verify(mockApi, times(1))
            .get(getBucketCaptor.capture(), getIdCaptor.capture())
          verify(mockApi, times(1))
            .put(bucketCaptor.capture(), idCaptor.capture(), dataCaptor.capture(), contentTypeCaptor.capture())

          val id = idCaptor.getValue
          getBucketCaptor.getValue shouldBe bucketName
          getIdCaptor.getValue shouldBe id
          bucketCaptor.getValue shouldBe bucketName
          dataCaptor.getValue shouldBe invitationData
          contentTypeCaptor.getValue shouldBe ContentTypes.`application/json`
          shortUrl shouldBe shortUrlPrefix + id
        }
      }
    }

    "when S3 item id already exists at first then succeed fully" - {
      "shortener should retry" in {
        val mockApi = mock[StorageAPI]
        val service = new TestS3ShortenerSvc(mockApi)
        val getBucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val getIdCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val bucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val idCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val dataCaptor: ArgumentCaptor[Array[Byte]] = ArgumentCaptor.forClass(classOf[Array[Byte]])
        val contentTypeCaptor: ArgumentCaptor[ContentType] = ArgumentCaptor.forClass(classOf[ContentType])

        when(mockApi.get(any[String], any[String])).thenReturn (
          Future.successful(Some("some".getBytes())),
          Future.successful(None)
        )
        when(mockApi.put(any[String], any[String], any[Array[Byte]], any[ContentType])).thenReturn (
          Future.successful(StorageInfo())
        )

        service.shortenURL(UrlInfo(longUrlCon))(system) map { shortUrl =>
          verify(mockApi, times(2))
            .get(getBucketCaptor.capture(), getIdCaptor.capture())
          verify(mockApi, times(1))
            .put(bucketCaptor.capture(), idCaptor.capture(), dataCaptor.capture(), contentTypeCaptor.capture())

          val id = idCaptor.getValue
          val getIds = getIdCaptor.getAllValues.toArray
          // last id should be the same for GET and PUT
          getIds.last shouldBe id
          // ... and ids should be changed between invocations
          getIds.distinct.length shouldBe 2
          // bucket should be the same every time
          getBucketCaptor.getAllValues.toArray.distinct shouldBe Array(bucketName)

          bucketCaptor.getValue shouldBe bucketName
          dataCaptor.getValue shouldBe invitationData
          contentTypeCaptor.getValue shouldBe ContentTypes.`application/json`
          shortUrl shouldBe shortUrlPrefix + id
        }
      }
    }

    "when S3 respond with failure for GET at first then succeed fully" - {
      "shortener should retry" in {
        val mockApi = mock[StorageAPI]
        val service = new TestS3ShortenerSvc(mockApi)
        val getBucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val getIdCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val bucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val idCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val dataCaptor: ArgumentCaptor[Array[Byte]] = ArgumentCaptor.forClass(classOf[Array[Byte]])
        val contentTypeCaptor: ArgumentCaptor[ContentType] = ArgumentCaptor.forClass(classOf[ContentType])

        when(mockApi.get(any[String], any[String])).thenReturn (
          Future.failed(new RuntimeException("Some exception")),
          Future.successful(None)
        )
        when(mockApi.put(any[String], any[String], any[Array[Byte]], any[ContentType])).thenReturn (
          Future.successful(StorageInfo())
        )

        service.shortenURL(UrlInfo(longUrlCon))(system) map { shortUrl =>
          verify(mockApi, times(2))
            .get(getBucketCaptor.capture(), getIdCaptor.capture())
          verify(mockApi, times(1))
            .put(bucketCaptor.capture(), idCaptor.capture(), dataCaptor.capture(), contentTypeCaptor.capture())

          val id = idCaptor.getValue
          val getIds = getIdCaptor.getAllValues.toArray
          // last id should be the same for GET and PUT
          getIds.last shouldBe id
          // ... and ids should be changed between invocations
          getIds.distinct.length shouldBe 2
          // bucket should be the same every time
          getBucketCaptor.getAllValues.toArray.distinct shouldBe Array(bucketName)

          bucketCaptor.getValue shouldBe bucketName
          dataCaptor.getValue shouldBe invitationData
          contentTypeCaptor.getValue shouldBe ContentTypes.`application/json`
          shortUrl shouldBe shortUrlPrefix + id
        }
      }
    }

    "when S3 respond with failure for PUT at first then succeed fully" - {
      "shortener should retry" in {
        val mockApi = mock[StorageAPI]
        val service = new TestS3ShortenerSvc(mockApi)
        val getBucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val getIdCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val bucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val idCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val dataCaptor: ArgumentCaptor[Array[Byte]] = ArgumentCaptor.forClass(classOf[Array[Byte]])
        val contentTypeCaptor: ArgumentCaptor[ContentType] = ArgumentCaptor.forClass(classOf[ContentType])

        when(mockApi.get(any[String], any[String])).thenReturn (
          Future.successful(None)
        )
        when(mockApi.put(any[String], any[String], any[Array[Byte]], any[ContentType])).thenReturn (
          Future( throw new Exception("Failed first time")),
          Future( throw new Exception("Failed second time")),
          Future.successful(StorageInfo()) // The Third Time Lucky
        )

        service.shortenURL(UrlInfo(longUrlCon))(system) map { shortUrl =>
          verify(mockApi, times(3))
            .get(getBucketCaptor.capture(), getIdCaptor.capture())
          verify(mockApi, times(3))
            .put(bucketCaptor.capture(), idCaptor.capture(), dataCaptor.capture(), contentTypeCaptor.capture())

          val ids = idCaptor.getAllValues.toArray
          val getIds = getIdCaptor.getAllValues.toArray
          // ids from GET and PUT should match
          ids shouldBe getIds
          // bucket, data and contentType should be the same every time
          getBucketCaptor.getAllValues.toArray.distinct shouldBe Array(bucketName)
          bucketCaptor.getAllValues.toArray.distinct shouldBe Array(bucketName)
          dataCaptor.getAllValues.toArray.distinct shouldBe Array(invitationData)
          contentTypeCaptor.getAllValues.toArray.distinct shouldBe Array(ContentTypes.`application/json`)
          // ids should be different every time
          ids.distinct.length shouldBe 3
          // id length should be id length
          ids.map(_.asInstanceOf[String].length).distinct shouldBe Array(idLength)
          // short url should be based on successful (last) id
          shortUrl shouldBe shortUrlPrefix + ids.last
        }
      }
    }

    "when S3 mixed failures occur at first then succeed eventually" - {
      "shortener should retry" in {
        val mockApi = mock[StorageAPI]
        val service = new TestS3ShortenerSvc(mockApi)
        val getBucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val getIdCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val bucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val idCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val dataCaptor: ArgumentCaptor[Array[Byte]] = ArgumentCaptor.forClass(classOf[Array[Byte]])
        val contentTypeCaptor: ArgumentCaptor[ContentType] = ArgumentCaptor.forClass(classOf[ContentType])

        when(mockApi.get(any[String], any[String])).thenReturn (
          Future.successful(None), // first unique id
          Future.successful(Some("some".getBytes())), // than id is not unique
          Future.successful(None), // rest is OK (for 3rd and 4th id)
        )
        when(mockApi.put(any[String], any[String], any[Array[Byte]], any[ContentType])).thenReturn (
          Future( throw new Exception("Failed first time")),
          Future( throw new Exception("Failed second time")), // this will be for 3rd id, because 2nd is not unique so no PUT for it.
          Future.successful(StorageInfo()) // The Third Time Lucky (4th id)
        )

        service.shortenURL(UrlInfo(longUrlCon))(system) map { shortUrl =>
          verify(mockApi, times(4))
            .get(getBucketCaptor.capture(), getIdCaptor.capture())
          verify(mockApi, times(3))
            .put(bucketCaptor.capture(), idCaptor.capture(), dataCaptor.capture(), contentTypeCaptor.capture())

          val ids = idCaptor.getAllValues.toArray
          val getIds = getIdCaptor.getAllValues.toArray
          // ids from GET and PUT should match
          ids(0) shouldBe getIds(0)
          // no match for getIds(1), because it wasn't unique, so PUT is not called.
          ids(1) shouldBe getIds(2)
          ids(2) shouldBe getIds(3)
          // bucket, data and contentType should be the same every time
          getBucketCaptor.getAllValues.toArray.distinct shouldBe Array(bucketName)
          bucketCaptor.getAllValues.toArray.distinct shouldBe Array(bucketName)
          dataCaptor.getAllValues.toArray.distinct shouldBe Array(invitationData)
          contentTypeCaptor.getAllValues.toArray.distinct shouldBe Array(ContentTypes.`application/json`)
          // ids should be different every time
          getIds.distinct.length shouldBe 4
          ids.distinct.length shouldBe 3
          // id length should be id length
          getIds.map(_.asInstanceOf[String].length).distinct shouldBe Array(idLength)
          ids.map(_.asInstanceOf[String].length).distinct shouldBe Array(idLength)
          // short url should be based on successful (last) id
          shortUrl shouldBe shortUrlPrefix + ids.last
        }
      }
    }

    "when S3 respond with item exist for GET more than retry times" - {
      "shortener should retry but fail eventually" in {
        val mockApi = mock[StorageAPI]
        val service = new TestS3ShortenerSvc(mockApi)
        val getBucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val getIdCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val bucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val idCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val dataCaptor: ArgumentCaptor[Array[Byte]] = ArgumentCaptor.forClass(classOf[Array[Byte]])
        val contentTypeCaptor: ArgumentCaptor[ContentType] = ArgumentCaptor.forClass(classOf[ContentType])

        when(mockApi.get(any[String], any[String])).thenReturn (
          Future.successful(Some("some".getBytes())), // not unique
        )
        when(mockApi.put(any[String], any[String], any[Array[Byte]], any[ContentType])).thenReturn (
          Future( throw new Exception("Failed every time")) // should not be called anyway
        )

        service.shortenURL(UrlInfo(longUrlCon))(system) map { _ =>
          fail("Error: shortenURL should have failed")
        } recover {
          case e: Throwable =>
            verify(mockApi, times(retryCount + 1))
              .get(getBucketCaptor.capture(), getIdCaptor.capture())
            verify(mockApi, times(0))
              .put(bucketCaptor.capture(), idCaptor.capture(), dataCaptor.capture(), contentTypeCaptor.capture())

            val getIds = getIdCaptor.getAllValues.toArray
            // bucket, data and contentType should be the same every time
            getBucketCaptor.getAllValues.toArray.distinct shouldBe Array(bucketName)
            // ids should be different every time
            getIds.distinct.length shouldBe retryCount + 1
            // id length should be id length
            getIds.map(_.asInstanceOf[String].length).distinct shouldBe Array(idLength)
        }
      }
    }
    "when S3 respond with failure for GET more than retry times" - {
      "shortener should retry but fail eventually" in {
        val mockApi = mock[StorageAPI]
        val service = new TestS3ShortenerSvc(mockApi)
        val getBucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val getIdCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val bucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val idCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val dataCaptor: ArgumentCaptor[Array[Byte]] = ArgumentCaptor.forClass(classOf[Array[Byte]])
        val contentTypeCaptor: ArgumentCaptor[ContentType] = ArgumentCaptor.forClass(classOf[ContentType])

        when(mockApi.get(any[String], any[String])).thenReturn (
          Future.failed(new Exception("Some exception"))
        )
        when(mockApi.put(any[String], any[String], any[Array[Byte]], any[ContentType])).thenReturn (
          Future(throw new Exception("Failed every time")) // should not be called anyway
        )

        service.shortenURL(UrlInfo(longUrlCon))(system) map { _ =>
          fail("Error: shortenURL should have failed")
        } recover {
          case e: Throwable =>
            verify(mockApi, times(retryCount + 1))
              .get(getBucketCaptor.capture(), getIdCaptor.capture())
            verify(mockApi, times(0))
              .put(bucketCaptor.capture(), idCaptor.capture(), dataCaptor.capture(), contentTypeCaptor.capture())

            val getIds = getIdCaptor.getAllValues.toArray
            // bucket, data and contentType should be the same every time
            getBucketCaptor.getAllValues.toArray.distinct shouldBe Array(bucketName)
            // ids should be different every time
            getIds.distinct.length shouldBe retryCount + 1
            // id length should be id length
            getIds.map(_.asInstanceOf[String].length).distinct shouldBe Array(idLength)
        }
      }
    }
    "when S3 respond with failure for PUT more than retry times" - {
      "shortener should retry but fail eventually" in {
        val mockApi = mock[StorageAPI]
        val service = new TestS3ShortenerSvc(mockApi)
        val getBucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val getIdCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val bucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val idCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val dataCaptor: ArgumentCaptor[Array[Byte]] = ArgumentCaptor.forClass(classOf[Array[Byte]])
        val contentTypeCaptor: ArgumentCaptor[ContentType] = ArgumentCaptor.forClass(classOf[ContentType])

        when(mockApi.get(any[String], any[String])).thenReturn (
          Future.successful(None)
        )
        when(mockApi.put(any[String], any[String], any[Array[Byte]], any[ContentType])).thenReturn (
          Future.failed(new Exception("Failed every time"))
        )

        service.shortenURL(UrlInfo(longUrlCon))(system) map { _ =>
          fail("Error: shortenURL should have failed")
        } recover {
          case e: Throwable =>
            verify(mockApi, times(retryCount + 1))
              .get(getBucketCaptor.capture(), getIdCaptor.capture())
            verify(mockApi, times(retryCount + 1))
              .put(bucketCaptor.capture(), idCaptor.capture(), dataCaptor.capture(), contentTypeCaptor.capture())

            val ids = idCaptor.getAllValues.toArray
            val getIds = getIdCaptor.getAllValues.toArray
            // ids should match between GET and PUT
            ids shouldBe getIds
            // bucket, data and contentType should be the same every time
            getBucketCaptor.getAllValues.toArray.distinct shouldBe Array(bucketName)
            bucketCaptor.getAllValues.toArray.distinct shouldBe Array(bucketName)
            dataCaptor.getAllValues.toArray.distinct shouldBe Array(invitationData)
            contentTypeCaptor.getAllValues.toArray.distinct shouldBe Array(ContentTypes.`application/json`)
            // ids should be different every time
            ids.distinct.length shouldBe retryCount + 1
            // id length should be id length
            ids.map(_.asInstanceOf[String].length).distinct shouldBe Array(idLength)
        }
      }
    }
    "when S3 respond with mixed failures more than retry times" - {
      "shortener should retry but fail eventually" in {
        val mockApi = mock[StorageAPI]
        val service = new TestS3ShortenerSvc(mockApi)
        val getBucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val getIdCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val bucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val idCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val dataCaptor: ArgumentCaptor[Array[Byte]] = ArgumentCaptor.forClass(classOf[Array[Byte]])
        val contentTypeCaptor: ArgumentCaptor[ContentType] = ArgumentCaptor.forClass(classOf[ContentType])

        when(mockApi.get(any[String], any[String])).thenReturn (
          Future.successful(Some("some".getBytes())), // not unique
          Future.failed(new Exception("Some exception")),
          Future.successful(None) // success and unique at the end
        )
        when(mockApi.put(any[String], any[String], any[Array[Byte]], any[ContentType])).thenReturn (
          Future( throw new Exception("Failed every time"))
        )

        service.shortenURL(UrlInfo(longUrlCon))(system) map { _ =>
          fail("Error: shortenURL should have failed")
        } recover {
          case e: Throwable =>
            verify(mockApi, times(retryCount + 1))
              .get(getBucketCaptor.capture(), getIdCaptor.capture())
            verify(mockApi, times(retryCount + 1 - 2)) // first 2 ids are failed in GET, so no PUT called
              .put(bucketCaptor.capture(), idCaptor.capture(), dataCaptor.capture(), contentTypeCaptor.capture())

            val ids = idCaptor.getAllValues.toArray
            val getIds = getIdCaptor.getAllValues.toArray

            // bucket, data and contentType should be the same every time
            getBucketCaptor.getAllValues.toArray.distinct shouldBe Array(bucketName)
            bucketCaptor.getAllValues.toArray.distinct shouldBe Array(bucketName)
            dataCaptor.getAllValues.toArray.distinct shouldBe Array(invitationData)
            contentTypeCaptor.getAllValues.toArray.distinct shouldBe Array(ContentTypes.`application/json`)
            // ids should be different every time
            getIds.distinct.length shouldBe retryCount + 1
            // ids should match for GET and PUT (except first 2 which should not call PUT)
            getIds.slice(2, retryCount + 1) shouldBe ids
            // ids length should be id length
            getIds.map(_.asInstanceOf[String].length).distinct shouldBe Array(idLength)
        }
      }
    }

  }

  override def overrideConfig: Option[Config] = Option { ConfigFactory.parseString (
    """
       verity {
         services.url-shortener-service {
           s3-shortener {
             url-prefix = "https://sho.rt/"
             bucket-name = "bucket"
             id-length = 8
             retry-count = 3
           }
         }
       }
    """
  )}
}
