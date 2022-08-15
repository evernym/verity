package com.evernym.verity.integration.base.endorser_svc_provider

import akka.Done
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.did.DidStr
import com.evernym.verity.eventing.adapters.basic.consumer.BasicConsumerAdapter
import com.evernym.verity.eventing.adapters.basic.producer.BasicProducerAdapter
import com.evernym.verity.eventing.event_handlers.EndorserMessageHandler._
import com.evernym.verity.eventing.event_handlers._
import com.evernym.verity.eventing.ports.producer.ProducerPort
import com.evernym.verity.integration.base.PortProvider
import com.evernym.verity.vdr.LedgerPrefix
import com.evernym.verity.config.ConfigConstants.VDR_UNQUALIFIED_LEDGER_PREFIX
import com.evernym.verity.eventing.event_handlers.EndorsementMessageHandler._
import com.evernym.verity.eventing.ports.consumer.{Message, MessageHandler}
import com.evernym.verity.integration.base.verity_provider.VerityEnv
import com.evernym.verity.protocol.engine.asyncapi.endorser.ENDORSEMENT_RESULT_SUCCESS_CODE
import com.typesafe.config.{Config, ConfigFactory}
import io.cloudevents.core.builder.CloudEventBuilder
import io.cloudevents.core.provider.EventFormatProvider
import io.cloudevents.jackson.JsonFormat
import org.json.JSONObject

import java.net.URI
import java.time.ZoneId
import java.util.UUID
import java.time.OffsetDateTime.now
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._


object MockEndorserServiceProvider {
  def apply(vasVerityEnv: VerityEnv): MockEndorserServiceProvider = {
    new MockEndorserServiceProvider(vasVerityEnv.headVerityLocalNode.portProfile.basicEventStorePort, vasVerityEnv.executionContext)
  }
}
//mock implementation of endorser service functionality
class MockEndorserServiceProvider(vasBasicEventStorePort: Int,
                                  executionContext: ExecutionContext) {

  //endorser and verity should share the same event storage
  // hence it is using `vasBasicEventStorePort`
  val eventingConfig: Config =
    ConfigFactory.parseString(
      s"""
         |verity.eventing.basic-source.id = "endorser"
         |verity.eventing.basic-store.http-listener.port = $vasBasicEventStorePort
         |verity.eventing.basic-source.http-listener.port = ${PortProvider.getFreePort}
         |verity.eventing.basic-source.topics = ["$TOPIC_REQUEST_ENDORSEMENT"]
         |""".stripMargin
    )

  //this basic producer will send events to the basic event store (listening on VAS service)
  // which sends those events to any consumer registered to it
  val eventProducer: BasicProducerAdapter = {
    val actorSystem = ActorSystemVanilla("endorser-event-producer", eventingConfig)
    new BasicProducerAdapter(new TestAppConfig(Option(eventingConfig), clearValidators = true))(
      actorSystem, executionContext)
  }

  //this will register to the basic event store (listening on VAS service)
  // so whenever that basic event store will receive any published events, it will send to this consumer
  val eventConsumer: BasicConsumerAdapter = {
    val testAppConfig = new TestAppConfig(Option(eventingConfig), clearValidators = true)
    val actorSystem = ActorSystemVanilla("endorser-event-consumer", eventingConfig)
    new BasicConsumerAdapter(testAppConfig,
      new MockEndorserEventConsumer(testAppConfig.config, eventProducer))(actorSystem, executionContext)
  }

  Await.result(eventConsumer.start(), 10.seconds)


  def publishEndorserActivatedEvent(endorserDid: DidStr,
                                    ledgerPrefix: LedgerPrefix): Future[Done] = {
    val jsonObject = new JSONObject()
    jsonObject.put(DATA_FIELD_ENDORSER_DID, endorserDid)
    jsonObject.put(DATA_FIELD_LEDGER_PREFIX, ledgerPrefix)

    val event = CloudEventBuilder.v1()
      .withId(UUID.randomUUID().toString)
      .withType(EVENT_ENDORSER_ACTIVATED_V1)
      .withSource(URI.create("event-source://v1:ssi:endorser"))
      .withData("application/json", jsonObject.toString().getBytes())
      .withTime(now(ZoneId.of("UTC")))
      .build()

    val payload = EventFormatProvider
      .getInstance
      .resolveFormat(JsonFormat.CONTENT_TYPE)
      .serialize(event)

    eventProducer.send(TOPIC_SSI_ENDORSER, payload)
  }

  def publishEndorserDeactivatedEvent(endorserDid: DidStr,
                                      ledgerPrefix: LedgerPrefix): Future[Done] = {
    val jsonObject = new JSONObject()
    jsonObject.put(DATA_FIELD_ENDORSER_DID, endorserDid)
    jsonObject.put(DATA_FIELD_LEDGER_PREFIX, ledgerPrefix)

    val event = CloudEventBuilder.v1()
      .withId(UUID.randomUUID().toString)
      .withType(EVENT_ENDORSER_DEACTIVATED_V1)
      .withSource(URI.create("event-source://v1:ssi:endorser"))
      .withData("application/json", jsonObject.toString().getBytes())
      .withTime(now(ZoneId.of("UTC")))
      .build()

    val payload = EventFormatProvider
      .getInstance
      .resolveFormat(JsonFormat.CONTENT_TYPE)
      .serialize(event)

    eventProducer.send(TOPIC_SSI_ENDORSER, payload)
  }
}


//mocks the endorser service related functionality to mimic the events consumed/handled by the endorser service
class MockEndorserEventConsumer(config: Config, eventProducer: ProducerPort) extends MessageHandler {

  lazy val indyLedgerPrefix: String = config.getString(VDR_UNQUALIFIED_LEDGER_PREFIX)

  override def handleMessage(message: Message): Future[Done] = {
    message.metadata.topic match {
      case TOPIC_REQUEST_ENDORSEMENT =>
        val jsonObject = message.cloudEvent
        jsonObject.get(CLOUD_EVENT_TYPE) match {
          case EVENT_ENDORSEMENT_REQ_V1 => processEndorsementReq(jsonObject)
          case other => Future.successful(Done)
        }
      case other => Future.successful(Done)
    }
  }

  private def processEndorsementReq(receivedEvent: JSONObject): Future[Done] = {

    val respJsonObject = new JSONObject()
    respJsonObject.put(DATA_FIELD_ENDORSEMENT_ID, UUID.randomUUID().toString)
    respJsonObject.put(DATA_FIELD_LEDGER_PREFIX, indyLedgerPrefix)
    respJsonObject.put(DATA_FIELD_REQUEST_SOURCE, receivedEvent.getString("source"))
    respJsonObject.put(DATA_FIELD_SUBMITTER_DID, "submitterdid")
    val resultJSONObject = new JSONObject()
    resultJSONObject.put(DATA_FIELD_RESULT_CODE, ENDORSEMENT_RESULT_SUCCESS_CODE)
    resultJSONObject.put(DATA_FIELD_RESULT_DESCR, "Transaction Written to VDR (normally a ledger)")
    respJsonObject.put(DATA_FIELD_RESULT, resultJSONObject)

    val event = CloudEventBuilder.v1()
      .withId(UUID.randomUUID().toString)
      .withType(EVENT_ENDORSEMENT_COMPLETE_V1)
      .withSource(URI.create("event-source://v1:ssi:endorser"))
      .withData("application/json", respJsonObject.toString().getBytes())
      .withTime(now(ZoneId.of("UTC")))
      .build()

    val payload = EventFormatProvider
      .getInstance
      .resolveFormat(JsonFormat.CONTENT_TYPE)
      .serialize(event)

    eventProducer.send(TOPIC_SSI_ENDORSEMENT, payload)
  }

}