package com.github.akreit.cache

import cats.effect.IO
import cats.effect.Ref

/** Identifies a unique tool invocation by name and a SHA-256 fingerprint of its
  * arguments.
  */
case class CacheKey(toolName: String, argsHash: String)

object CacheKey:
  /** Builds a [[CacheKey]] by hashing the canonicalized (key-sorted) `args`
    * with SHA-256.
    */
  def fromArgs(toolName: String, args: Map[String, AnyRef]): CacheKey =
    val canonical =
      args.toSeq.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString(",")
    val bytes = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(canonical.getBytes("UTF-8"))
    val digest = bytes.map("%02x".format(_)).mkString
    CacheKey(toolName, digest)

/** Per-request cache for MCP tool results, keyed by [[CacheKey]]. */
trait ToolResultCache:
  /** Returns the cached result for `key`, or [[scala.None]] on a miss. */
  def get(key: CacheKey): IO[Option[String]]

  /** Stores `result` under `key`. */
  def put(key: CacheKey, result: String): IO[Unit]

object ToolResultCache:
  /** Creates an in-memory [[ToolResultCache]] backed by a [[cats.effect.Ref]].
    */
  def inMemory: IO[ToolResultCache] =
    Ref.of[IO, Map[CacheKey, String]](Map.empty).map { ref =>
      new ToolResultCache:
        def get(key: CacheKey): IO[Option[String]] = ref.get.map(_.get(key))
        def put(key: CacheKey, result: String): IO[Unit] =
          ref.update(_ + (key -> result))
    }
