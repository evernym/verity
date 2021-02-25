package com.evernym.verity.protocol.protocols.agentprovisioning.common

import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.protocol.Control

case class AskUserAgentCreator(forDIDPair: DidPair, agentKeyDIDPair: DidPair, endpointDetailJson: String)

case class AgentCreationCompleted() extends Control with ActorMessage
