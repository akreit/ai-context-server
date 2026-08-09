## Overview

A gateway server that allows clients to interact with LLMs, include additional context, track token usage etc.

```
                                 ┌────────────┐
                                 │    Jira    │
                                 └─────┬──────┘
                                       │
                                 ┌─────┴──────┐
                                 │ Confluence │
                                 └─────┬──────┘
                                       │
                                 ┌─────┴──────┐
                                 │   GitHub   │
                                 └─────┬──────┘
                                       │
                                       │ fetch context
                                       │
┌────────┐   POST /v1/context   ┌──────┴──────────┐   user msg + context   ┌─────────────┐
│        │   /completions       │                 │                        │             │
│ Client ├─────────────────────►│  AI Context     ├───────────────────────►│  Claude LLM │
│        │◄─────────────────────┤  Server         │◄───────────────────────┤             │
│        │   CompletionResponse │                 │   LLM completion       │             │
└────────┘                      └─────────────────┘                        └─────────────┘
```

See details in the `docs/` folder.

## Motivation

Integrating LLMs into applications can be complex for multiple reasons:

* **additional context**: when working in a business context, it often makes sense to enrich prompts with additional context e.g. from tools like Jira, Confluence or GitHub.
    Seting up these integrations should not be done by every client application and developer.
* **efficient usage**: LLMs can be expensive to use, and it is important to optimize the usage by caching results, batching requests and reusing context when possible.
* **standardized interface**: providing a standardized interface for clients to interact with LLMs, e.g. to deal with corporate proxies and other policies
* **LLM agnostic**: the server should be able to work with different LLM providers, and switch between them without affecting the clients.
* **cost transparency**: the server can provide insights into the costs of using LLMs, e.g. by tracking usage and providing cost estimates.

These points can be addressed by providing a dedicated API server that handles the interactions with LLMs and the integration with other tools, and provides a standardized interface for clients to use.

## Building and running the project

This project comes with a devcontainer setup powered by [sandcat](https://github.com/VirtusLab/sandcat).
To start your development environment, simply run:

```shell
sandcat run
```

Once the devcontainer has started, you can use sbt to build and compile the code, run tests and execute the application:

```shell
sbt compile # build the project
sbt test # run the tests
sbt run # run the application (Main)
```

In addition, the devcontainer comes with:
* the [scala metals language server](https://github.com/scalameta/metals) which also includes a standalone mcp server.
* [cellar](https://github.com/VirtusLab/cellar) → a cli for looking up type signatures, packages, members etc.

Both tools improve the effectiveness of working on this project with coding agents such as Claude Code.

You can now test the API server e.g. by opening the Swagger docs at `http://localhost:8080/docs/`.

## Local development ports

By default, the API server listens on `8080`. Make sure this port is free before running `sbt run`.

## Links:

* [tapir documentation](https://tapir.softwaremill.com/en/latest/)
* [tapir github](https://github.com/softwaremill/tapir)
* [bootzooka: template microservice using tapir](https://softwaremill.github.io/bootzooka/)
* [sbtx wrapper](https://github.com/dwijnand/sbt-extras#installation)
