package com.evernym.verity.http

import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.{as, entity, extractRequest, provide}
import com.evernym.verity.agentmsg.DefaultMsgCodec

import scala.reflect.ClassTag

object HttpUtil {

  def optionalEntityAs[T: ClassTag]: Directive1[Option[T]] = {
    extractRequest.flatMap { req =>
      if (req.entity.contentLengthOption.contains(0L)) {
        provide(Option.empty[T])
      } else {
        entity(as[String]).flatMap { e =>
          val nativeMsg = DefaultMsgCodec.fromJson(e)
          provide(Some(nativeMsg))
        }
      }
    }
  }

  def entityAs[T: ClassTag]: Directive1[T] = {
    optionalEntityAs[T].map(_.getOrElse(throw new RuntimeException("entity not found")))
  }
}