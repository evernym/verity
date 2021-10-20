package com.evernym.verity.util.healthcheck

import akka.actor.{ActorRef, ActorSystem}
import com.evernym.verity.actor.agent.AgentActorContext

import scala.concurrent.{ExecutionContext, Future}

case class ApiStatus(status: Boolean, msg: String)


trait HealthChecker {

  private var _isReady = true

  def isReady: Boolean = _isReady

  def updateReadinessStatus(status: Boolean): Unit = _isReady = status

  def checkAkkaEventStorageStatus: Future[ApiStatus]

  def checkWalletStorageStatus: Future[ApiStatus]

  def checkStorageAPIStatus: Future[ApiStatus]

  def checkLedgerPoolStatus: Future[ApiStatus]

  def checkLiveness: Future[Unit]
}

object HealthChecker {
  def apply(agentActorContext: AgentActorContext,
            appStateManager: ActorRef,
            actorSystem: ActorSystem,
            futureExecutionContext: ExecutionContext): HealthChecker = {
    new HealthCheckerImpl(agentActorContext, appStateManager, actorSystem, futureExecutionContext)
  }
}