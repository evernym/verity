package com.evernym.verity.http.route_handlers.restricted.models

import com.evernym.verity.actor.appStateManager.AppStateConstants.STATUS_LISTENING

case class UpdateAppStatus(newStatus: String = STATUS_LISTENING, context: Option[String] = None, reason: Option[String] = None)
