package com.github.akreit.model

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

enum ContextSource {
  case Jira, Confluence, Github, ProjectDocumentation
}

object ContextSource {
  given JsonValueCodec[ContextSource] = JsonCodecMaker.make
}
