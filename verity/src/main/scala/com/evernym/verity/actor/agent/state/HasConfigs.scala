//package com.evernym.verity.actor.agent.state
//
//import com.evernym.verity.actor.agent.user.AgentConfig
//
///**
// * A trait meant to be mixed into the state object of an agent
// *
// * contains agent configurations (name value pair)
// * these are set by the agent owner by sending specific agent message
// * current use case is to store 'name' and 'logo-url' of the owner (mostly for issuer side)
// */
//
//trait Configs {
//
//  type ConfigName = String
//  private var _configs: Map[ConfigName, AgentConfig] = Map.empty
//  def configs: Map[ConfigName, AgentConfig] = _configs
//  def addConfig(name: ConfigName, ac: AgentConfig): Unit = _configs = _configs.updated(name, ac)
//  def removeConfig(name: ConfigName): Unit = _configs = _configs.filterKeys(_ == name)
//
//  def isConfigExists(name: ConfigName): Boolean = _configs.contains(name)
//  def isConfigExists(name: ConfigName, value: String): Boolean = {
//    _configs.exists(c => c._1 == name && c._2.value == value)
//  }
//
//  def filterConfigsByNames(names: Set[ConfigName]): Map[ConfigName, AgentConfig] =
//    _configs.filter(c => names.contains(c._1))
//}
//
//trait HasConfigs {
//  type StateType <: Configs
//  def state: StateType
//}
