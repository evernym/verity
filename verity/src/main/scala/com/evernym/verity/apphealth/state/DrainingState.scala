package com.evernym.verity.apphealth.state

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.cluster.MemberStatus.{Down, Removed}
import com.evernym.verity.constants.LogKeyConstants.LOG_KEY_ERR_MSG
import com.evernym.verity.apphealth.AppStateConstants.{CONTEXT_AGENT_SERVICE_SHUTDOWN, STATUS_DRAINING}
import com.evernym.verity.apphealth.{AppStateManagerBase, CauseDetail, DrainingStarted, EventParam, MildSystemError, Recovered, RecoveredFromCause, SeriousSystemError, Shutdown, SuccessEventParam}
import com.evernym.verity.config.CommonConfig.{APP_STATE_MANAGER_STATE_DRAINING_DELAY_BEFORE_LEAVING_CLUSTER_IN_SECONDS, APP_STATE_MANAGER_STATE_DRAINING_DELAY_BETWEEN_STATUS_CHECKS_IN_SECONDS, APP_STATE_MANAGER_STATE_DRAINING_MAX_STATUS_CHECK_COUNT}
import com.evernym.verity.config.AppConfigWrapper

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import scala.concurrent.Future
import scala.util.{Failure, Success}


object DrainingState extends AppState {

  override val name: String = STATUS_DRAINING

  /**
   * When app state is 'DrainingState', it handles below events apart from
   * what is handled in 'commonEventHandler' in AppState
   *
   * @param param event parameter
   * @param appStateManager app state manager base instance
   */
  override def handleEvent(param: EventParam)(implicit appStateManager: AppStateManagerBase): Unit = {
    import appStateManager._
    param.event match {
      case RecoveredFromCause | Recovered | MildSystemError | SeriousSystemError
                                => reportAndStay(param)
      case DrainingStarted      => logger.info(s"received $DrainingStarted while draining is already in progress")
      case Shutdown             => performTransition(ShutdownState, param)
      case x                    => throwEventNotSupported(x)
    }
  }

  /**
   * This function gets executed when app state transitions from any other state
   * to this state (DrainingState)
   *
   * @param param event parameter
   * @param appStateManager app state manager base instance
   */
  override def postTransition(param: EventParam)(implicit appStateManager: AppStateManagerBase): Unit = {
    import appStateManager._
    // TODO: consider checking if param.system is None BEFORE anything else. Doing so prevents the state transition,
    //       because the node cannot 'leave' the cluster without a reference to the ActorSystem.
    //       If param.system is guaranteed to be a valid ActorSystem reference, performServiceDrain does not need
    //       to make the check for None.
    //       Doing it the current way ensures traffic stops being routed from the load balancer to this node, but
    //       does not ensure the akka node gracefully leaves the cluster (migrate singletons, shards, etc.)
    performAction(param.actionHandler)
    sysServiceNotifier.setStatus(name)
    performServiceDrain(param.system)
  }

  private def performServiceDrain(system: Option[ActorSystem]=None)(implicit appStateManager: AppStateManagerBase): Unit = {
    import appStateManager._
    try {
      system match {
        case Some(system) =>
          // TODO: Start a CoordinatedShutdown (rely on the ClusterDaemon addTask here) OR do the following?
          val cluster = Cluster(system)
          val f: Future[Boolean] = Future {
            lazy val delayBeforeLeavingCluster = AppConfigWrapper.getConfigIntOption(
              APP_STATE_MANAGER_STATE_DRAINING_DELAY_BEFORE_LEAVING_CLUSTER_IN_SECONDS).getOrElse(10)
            lazy val delayBetweenStatusChecks = AppConfigWrapper.getConfigIntOption(
              APP_STATE_MANAGER_STATE_DRAINING_DELAY_BETWEEN_STATUS_CHECKS_IN_SECONDS).getOrElse(1)
            lazy val maxStatusCheckCount = AppConfigWrapper.getConfigIntOption(
              APP_STATE_MANAGER_STATE_DRAINING_MAX_STATUS_CHECK_COUNT).getOrElse(20)
            logger.info(s"""Will remain in draining state for at least $delayBeforeLeavingCluster seconds before
                            starting the Coordinated Shutdown...""")
            // Sleep a while to give the load balancers time to get a sufficient number of non-200 http response codes
            // from agency/heartbeat AFTER application state transition to 'Draining'.
            Thread.sleep(delayBeforeLeavingCluster * 1000) // seconds converted to milliseconds
            logger.info(s"Akka node ${cluster.selfAddress} is leaving the cluster...")
            // NOTE: Tasks to gracefully leave an Akka cluster include graceful shutdown of Cluster Singletons and Cluster
            //       Sharding.
            cluster.leave(cluster.selfAddress)

            def checkIfNodeHasLeftCluster(delay: Int, tries: Int): Boolean = {
              if (tries <= 0) return false
              logger.debug(s"Check if cluster is terminated or status is set to Removed or Down...")
              if (cluster.isTerminated || cluster.selfMember.status == Removed || cluster.selfMember.status == Down) {
                return true
              }
              logger.debug(s"""Cluster is NOT terminated AND status is NOT set to Removed or Down. Sleep $delay
                               milliseconds and try again up to $tries more time(s).""")
              Thread.sleep(delay * 1000) // sleep one second and check again
              checkIfNodeHasLeftCluster(delay, tries - 1)
            }
            checkIfNodeHasLeftCluster(delayBetweenStatusChecks, maxStatusCheckCount)
          }

          f onComplete {
            case Success(_) =>
              logger.info(
                "Akka node has left the cluster"
              )

              logger.info(s"""Akka node ${cluster.selfAddress} is being marked as 'down' as well in the event of network
                              failures while 'leaving' the cluster...""")
              // NOTE: In case of network failures during this process (leaving) it might still be necessary to set the
              //       node’s status to Down in order to complete the removal.
              cluster.down(cluster.selfAddress)

              // Begin shutting down the node. The transition to "Shutdown" will stop the sysServiceNotifier and
              // perform a service shutdown (system exit).
              <<(SuccessEventParam(Shutdown, CONTEXT_AGENT_SERVICE_SHUTDOWN,
                causeDetail = CauseDetail(
                  "agent-service-shutdown", "agent-service-shutdown-successfully"
                ),
                msg = Option("Akka node is about to shutdown."),
                system = Option(system)
              ))

            case Failure(error) =>
              logger.error(
                "node encountered a failure while attempting to 'leave' the cluster.", (LOG_KEY_ERR_MSG, error)
              )
          }
        case None =>
          logger.error(
            "could not signal the node to 'leave' the cluster.", (LOG_KEY_ERR_MSG, "ActorSystem not provided")
          )
      }
    } catch {
      case e: Exception => logger.error(
        s"Failed to Drain the akka node. Reason: ${e.toString}"
      )
    }
  }
}
