package com.evernym.verity.actor.msg_tracer

import akka.actor.ActorRef
import com.evernym.verity.constants.ActorNameConstants.{MSG_PROGRESS_TRACKER_REGION_ACTOR_NAME, MSG_TRACER_REGION_ACTOR_NAME}
import com.evernym.verity.actor.Platform
import com.evernym.verity.actor.msg_tracer.resp_time_tracker.MsgTracer
import com.evernym.verity.actor.msg_tracer.progress_tracker.MsgProgressTracker

trait MsgTracingRegionActors { this: Platform =>

  //message tracing region actor
  val msgTracerRegion: ActorRef = createNonPersistentRegion(
    MSG_TRACER_REGION_ACTOR_NAME,
    MsgTracer.props(agentActorContext.appConfig))

  //message progress tracking region actor (this is not a feature code, only related to troubleshooting)
  val msgProgressTrackerRegion: ActorRef = createNonPersistentRegion(
    MSG_PROGRESS_TRACKER_REGION_ACTOR_NAME,
    MsgProgressTracker.props(agentActorContext.appConfig, this.executionContextProvider.futureExecutionContext))

}
