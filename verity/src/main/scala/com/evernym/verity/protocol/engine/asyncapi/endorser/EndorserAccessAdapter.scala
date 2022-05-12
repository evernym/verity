package com.evernym.verity.protocol.engine.asyncapi.endorser

import akka.actor.ActorRef
import akka.pattern.extended.ask
import akka.actor.typed.scaladsl.adapter._
import com.evernym.verity.actor.cluster_singleton.ForEndorserRegistry
import com.evernym.verity.endorser_registry.EndorserRegistry.Commands.GetEndorsers
import com.evernym.verity.endorser_registry.EndorserRegistry.Replies.LedgerEndorsers
import com.evernym.verity.eventing.event_handlers.{EVENT_ENDORSEMENT_REQ_V1, TOPIC_REQUEST_ENDORSEMENT}
import com.evernym.verity.eventing.ports.producer.ProducerPort
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.container.asyncapis.BaseAsyncAccessImpl
import com.evernym.verity.protocol.engine.asyncapi.{AsyncOpRunner, BlobStorageUtil, EventPublisherUtil, RoutingContext}
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.util2.RetentionPolicy
import com.evernym.verity.vault.operation_executor.FutureConverter

import scala.concurrent.ExecutionContext
import scala.util.Try


class EndorserAccessAdapter(routingContext: RoutingContext,
                            producerPort: ProducerPort,
                            storageAPI: StorageAPI,
                            singletonParentProxy: ActorRef,
                            dataRetentionPolicy: Option[RetentionPolicy])
                           (implicit val ec: ExecutionContext,
                            val asyncOpRunner: AsyncOpRunner,
                            val asyncAPIContext: AsyncAPIContext)
  extends EndorserAccess
    with BaseAsyncAccessImpl
    with FutureConverter {

  val blobStorageUtil = new BlobStorageUtil(appConfig, storageAPI)
  val eventPublisherUtil = new EventPublisherUtil(routingContext, producerPort)

  override def withCurrentEndorser(ledger: String)(handler: Try[Option[Endorser]] => Unit): Unit = {

    asyncOpRunner.withFutureOpRunner(
      {singletonParentProxy
        .ask{ ref: ActorRef => ForEndorserRegistry(GetEndorsers(ledger, ref))}
        .mapTo[LedgerEndorsers]
        .map(r => r.latestEndorser.map(e => Endorser(e.did)))
      },
      handler
    )
  }

  override def endorseTxn(payload: String, endorser: String, vdr: String, vdrType: String)(handler: Try[Unit] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      blobStorageUtil.saveInBlobStore(payload.getBytes(), dataRetentionPolicy)
        .flatMap { storageInfo =>
          val jsonPayload =
            s"""{
               |"$CLOUD_EVENT_DATA_FIELD_TXN_REF": "${storageInfo.endpoint}",
               |"$CLOUD_EVENT_DATA_FIELD_ENDORSER": "$endorser",
               |"$CLOUD_EVENT_DATA_FIELD_VDR": "$vdr",
               |"$CLOUD_EVENT_DATA_FIELD_VDR_TYPE": "$vdrType"
               |}""".stripMargin
          eventPublisherUtil.publishToEventBus(jsonPayload, EVENT_ENDORSEMENT_REQ_V1, TOPIC_REQUEST_ENDORSEMENT)
        },
      handler
    )
  }

  val CLOUD_EVENT_DATA_FIELD_TXN_REF = "txnref"
  val CLOUD_EVENT_DATA_FIELD_ENDORSER = "endorserdid"
  val CLOUD_EVENT_DATA_FIELD_VDR = "vdr"
  val CLOUD_EVENT_DATA_FIELD_VDR_TYPE = "vdr_type"
}

