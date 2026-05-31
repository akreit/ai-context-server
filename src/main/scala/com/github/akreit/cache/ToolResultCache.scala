package com.github.akreit.cache

import cats.effect.IO
import cats.effect.Ref

case class CacheKey(toolName: String, argsHash: String)

object CacheKey:
  def fromArgs(toolName: String, args: Map[String, AnyRef]): CacheKey =
    val canonical =
      args.toSeq.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString(",")
    val bytes = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(canonical.getBytes("UTF-8"))
    val digest = bytes.map("%02x".format(_)).mkString
    CacheKey(toolName, digest)

trait ToolResultCache:
  def get(key: CacheKey): IO[Option[String]]
  def put(key: CacheKey, result: String): IO[Unit]

object ToolResultCache:
  def inMemory: IO[ToolResultCache] =
    Ref.of[IO, Map[CacheKey, String]](Map.empty).map { ref =>
      new ToolResultCache:
        def get(key: CacheKey): IO[Option[String]] = ref.get.map(_.get(key))
        def put(key: CacheKey, result: String): IO[Unit] =
          ref.update(_ + (key -> result))
    }
