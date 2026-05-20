package com.github.akreit.model

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.CodecMakerConfig
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.tapir.Schema

/** Enumeration representing the possible sources of additional context that the
  * user can request to be included in the response. These sources can be used
  * by the server to fetch relevant information and include it in the response
  * to the user's query.
  */
enum ContextSource(val name: String):
  case Jira extends ContextSource("jira")
  case Confluence extends ContextSource("confluence")
  case GitHub extends ContextSource("github")
  case ProjectDocumentation extends ContextSource("projectdocumentation")

object ContextSource:

  // see: https://github.com/plokhotnyuk/jsoniter-scala/issues/1268
  // by default, jsoniter-scala encodes enums as objects with a "type" discriminator field, e.g. {"type": "Github"}
  // we want to encode them as plain strings, e.g. "GitHub", so we need to configure the codec accordingly
  given JsonValueCodec[ContextSource] = JsonCodecMaker.make(
    CodecMakerConfig
      .withDiscriminatorFieldName(None)
      // TODO: this is not working yet, allow "jira" instead of "Jira", etc.
      .withFieldNameMapper { case s => s.toLowerCase }
  )

  // tapir schemas and codecs for ContextSource, also configured to use plain strings instead of objects
  given Schema[ContextSource] = Schema.derivedEnumeration.defaultStringBased
