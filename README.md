## Overview

This project contains an API server that clients can use to get rich responses
from LLMs to their prompts. The server gets context to the users' prompts from various sources,
e.g. GitHub, Jira & Confluence.

See details in the `docs/` folder.

## Building and running the project

This project uses sbt to build and compile the code, run tests and execute the application.
You can use the sbt shell or run the commands directly in your terminal.

```shell
sbt compile # build the project
sbt test # run the tests
sbt run # run the application (Main)
```

## Links:

* [tapir documentation](https://tapir.softwaremill.com/en/latest/)
* [tapir github](https://github.com/softwaremill/tapir)
* [bootzooka: template microservice using tapir](https://softwaremill.github.io/bootzooka/)
* [sbtx wrapper](https://github.com/dwijnand/sbt-extras#installation)
