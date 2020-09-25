package com.evernym.verity

import akka.actor.Props
import com.evernym.verity.config.AppConfig

package object actor {
  trait HasProps {
    def props(implicit conf: AppConfig): Props
  }
}
