package com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher

import akka.actor.typed.Behavior

trait AccessTokenRefreshers {
  type Version = String
  def refreshers: Map[Version, Behavior[OAuthAccessTokenRefresher.Cmd]]
}
