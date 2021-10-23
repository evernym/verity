package com.evernym.verity.urlshortener

import akka.actor.ActorSystem
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
    "when url shortener respond with success" - {
      "connection invitation should succeed" in {
        val mockApi = mock[StorageAPI]
        val service = new TestS3ShortenerSvc(mockApi)
        val bucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val idCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val dataCaptor: ArgumentCaptor[Array[Byte]] = ArgumentCaptor.forClass(classOf[Array[Byte]])

        when(mockApi.put(any[String], any[String], any[Array[Byte]])) thenReturn Future.successful(StorageInfo())

        service.shortenURL(UrlInfo(longUrlCon))(system) map { shortUrl =>
          verify(mockApi, times(1)).put(bucketCaptor.capture(), idCaptor.capture(), dataCaptor.capture())

          val id = idCaptor.getValue
          bucketCaptor.getValue shouldBe bucketName
          dataCaptor.getValue shouldBe invitationData
          shortUrl shouldBe shortUrlPrefix + id
        }
      }

      "out-of-band invitation should succeed" in {
        val mockApi = mock[StorageAPI]
        val service = new TestS3ShortenerSvc(mockApi)
        val bucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val idCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val dataCaptor: ArgumentCaptor[Array[Byte]] = ArgumentCaptor.forClass(classOf[Array[Byte]])

        when(mockApi.put(any[String], any[String], any[Array[Byte]])) thenReturn Future.successful(StorageInfo())

        service.shortenURL(UrlInfo(longUrlCon))(system) map { shortUrl =>
          verify(mockApi, times(1)).put(bucketCaptor.capture(), idCaptor.capture(), dataCaptor.capture())

          val id = idCaptor.getValue
          bucketCaptor.getValue shouldBe bucketName
          dataCaptor.getValue shouldBe invitationData
          shortUrl shouldBe shortUrlPrefix + id
        }
      }
    }

    "when url shortener respond with failure at first then succeed" - {
      "shortener should retry" in {
        val mockApi = mock[StorageAPI]
        val service = new TestS3ShortenerSvc(mockApi)
        val bucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val idCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val dataCaptor: ArgumentCaptor[Array[Byte]] = ArgumentCaptor.forClass(classOf[Array[Byte]])

        when(mockApi.put(any[String], any[String], any[Array[Byte]])).thenReturn (
          Future( throw new Exception("Failed first time")),
          Future( throw new Exception("Failed second time")),
          Future.successful(StorageInfo()) // The Third Time Lucky
        )

        service.shortenURL(UrlInfo(longUrlCon))(system) map { shortUrl =>
          verify(mockApi, times(3)).put(bucketCaptor.capture(), idCaptor.capture(), dataCaptor.capture())

          val ids = idCaptor.getAllValues.toArray
          // bucket and data should be the same every time
          bucketCaptor.getAllValues.toArray.distinct shouldBe Array(bucketName)
          dataCaptor.getAllValues.toArray.distinct shouldBe Array(invitationData)
          // ids should be different every time
          ids.distinct.length shouldBe 3
          // id length should be id length
          ids.map(_.asInstanceOf[String].length).distinct shouldBe Array(idLength)
          // short url should be based on successful (last) id
          shortUrl shouldBe shortUrlPrefix + ids.last
        }
      }
    }

    "when url shortener respond with failure more than retry times" - {
      "shortener should retry but fail eventually" in {
        val mockApi = mock[StorageAPI]
        val service = new TestS3ShortenerSvc(mockApi)
        val bucketCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val idCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        val dataCaptor: ArgumentCaptor[Array[Byte]] = ArgumentCaptor.forClass(classOf[Array[Byte]])

        when(mockApi.put(any[String], any[String], any[Array[Byte]])).thenReturn (
          Future( throw new Exception("Failed every time"))
        )

        service.shortenURL(UrlInfo(longUrlCon))(system) map { _ =>
          fail("Error: it should have failed")
        } recover {
          case e: Throwable =>
            println(e)
            verify(mockApi, times(retryCount + 1)).put(bucketCaptor.capture(), idCaptor.capture(), dataCaptor.capture())

            val ids = idCaptor.getAllValues.toArray
            // bucket and data should be the same every time
            bucketCaptor.getAllValues.toArray.distinct shouldBe Array(bucketName)
            dataCaptor.getAllValues.toArray.distinct shouldBe Array(invitationData)
            // ids should be different every time
            ids.distinct.length shouldBe retryCount + 1
            // id length should be id length
            ids.map(_.asInstanceOf[String].length).distinct shouldBe Array(idLength)
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
