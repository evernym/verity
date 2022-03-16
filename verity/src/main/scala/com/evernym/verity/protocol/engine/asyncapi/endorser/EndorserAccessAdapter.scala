package com.evernym.verity.protocol.engine.asyncapi.endorser

import akka.actor.ActorRef
import akka.pattern.extended.ask
import akka.actor.typed.scaladsl.adapter._
import com.evernym.verity.actor.cluster_singleton.ForEndorserRegistry
import com.evernym.verity.endorser_registry.EndorserRegistry.Commands.GetEndorsers
import com.evernym.verity.endorser_registry.EndorserRegistry.Replies.LedgerEndorsers
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.container.asyncapis.BaseAsyncAccessImpl
import com.evernym.verity.protocol.engine.asyncapi.AsyncOpRunner
import com.evernym.verity.vault.operation_executor.FutureConverter

import scala.concurrent.ExecutionContext
import scala.util.Try


class EndorserAccessAdapter(singletonParentProxy: ActorRef)
                           (implicit val ec: ExecutionContext,
                            implicit val asyncOpRunner: AsyncOpRunner,
                            val asyncAPIContext: AsyncAPIContext)
  extends EndorserAccess
    with BaseAsyncAccessImpl
    with FutureConverter {

  override def withCurrentEndorser(ledger: String)(handler: Try[Option[Endorser]] => Unit): Unit = {

    asyncOpRunner.withFutureOpRunner(
      {singletonParentProxy
        .ask{ ref: ActorRef => ForEndorserRegistry(GetEndorsers(ledger, ref))}
        .mapTo[LedgerEndorsers]
        .map(r => r.latestEndorser.map(e => Endorser(e.did, e.verKey)))
      },
      handler
    )
  }

}
