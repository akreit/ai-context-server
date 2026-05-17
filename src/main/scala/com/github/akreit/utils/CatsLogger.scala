package com.github.akreit.utils

import cats.effect.IO
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jFactory

/** Mixin trait to provide a Cats Effect logger
  * @see
  *   https://tinyurl.com/3k5spz3m
  */
trait CatsLogger {

  // taken from: https://tinyurl.com/3k5spz3m
  val logger: SelfAwareStructuredLogger[IO] = Slf4jFactory.create[IO].getLogger
}
