package com.evernym.verity.protocol.engine.asyncapi.endorser

import akka.actor.ActorRef
import akka.pattern.extended.ask
import akka.actor.typed.scaladsl.adapter._
import com.evernym.verity.actor.cluster_singleton.ForEndorserRegistry
import com.evernym.verity.endorser_registry.EndorserRegistry.Commands.GetEndorsers
import com.evernym.verity.endorser_registry.EndorserRegistry.Replies.LedgerEndorsers
import com.evernym.verity.eventing.event_handlers.{DATA_FIELD_LEDGER_PREFIX, EVENT_ENDORSEMENT_REQ_V1, TOPIC_REQUEST_ENDORSEMENT}
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

  val bucketName: String = appConfig.config.getString("verity.endorsement.request.txn-store.bucket-name")

  val blobStorageUtil = new BlobStorageUtil(bucketName, storageAPI)
  val eventPublisherUtil = new EventPublisherUtil(routingContext, producerPort)

  override def withCurrentEndorser(ledgerPrefix: String)(handler: Try[Option[Endorser]] => Unit): Unit = {

    asyncOpRunner.withFutureOpRunner(
      singletonParentProxy
        .ask{ ref: ActorRef => ForEndorserRegistry(GetEndorsers(ledgerPrefix, ref))}
        .mapTo[LedgerEndorsers]
        //NOTE:
        // With FQ endorser identifier, indy ledger was throwing this error on endorser side
        //    Unable to publish transaction -- for 'did:indy:sovrin:builder' -- client request invalid:
        //      InvalidClientRequest("validation error [SafeRequest]: should not contain the following chars [':', 'l']
        //      (endorser=sovrin:builder:8anW9B9wfzmAsMjEWJihWQ)",)
        // So, until ledger (indy etc) starts supporting fully qualified DIDs,
        // it needs to be truncated and then used (see next line)
        .map(r => r.latestEndorser.map(e => Endorser(e.did.substring(e.did.lastIndexOf(":")+1))))
      ,
      handler
    )
  }

  override def endorseTxn(payload: String, ledgerPrefix: String)(handler: Try[Unit] => Unit): Unit = {
    asyncOpRunner.withFutureOpRunner(
      blobStorageUtil.saveInBlobStore(payload.getBytes(), dataRetentionPolicy)
        .flatMap { storageInfo =>
          val jsonPayload =
            s"""{
               |"$CLOUD_EVENT_DATA_FIELD_TXN_REF": "${storageInfo.endpoint}",
               |"$DATA_FIELD_LEDGER_PREFIX": "$ledgerPrefix"
               |}""".stripMargin
          eventPublisherUtil.publishToEventBus(jsonPayload, EVENT_ENDORSEMENT_REQ_V1, TOPIC_REQUEST_ENDORSEMENT)
        },
      handler
    )
  }

  val CLOUD_EVENT_DATA_FIELD_TXN_REF = "txnref"
  val CLOUD_EVENT_DATA_FIELD_ENDORSER = "endorserdid"
}

