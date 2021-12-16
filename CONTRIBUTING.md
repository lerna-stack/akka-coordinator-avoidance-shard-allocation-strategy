# Contributing

## Requirements
- [Java 8](https://www.oracle.com/java/technologies/javase/javase-jdk8-downloads.html)
- [sbt](https://www.scala-sbt.org/index.html)

## Workflow
1. Create your fork of this repository
2. Create a local branch based on `main`  
3. Work in the branch
4. Push the branch into your repository
5. Create a Pull Request to the `main` branch of this repository

## Code Style
We use [Scalafmt](https://scalameta.org/scalafmt/) to format the source code.  
We recommend you set up your editor as documented [here](https://scalameta.org/scalafmt/docs/installation.html).

We also use [EditorConfig](https://editorconfig.org/) to format files.  
We recommend you install the EditorConfig plugin.  
Note that you might need nothing since some editors provide native support for EditorConfig.

## Run Unit Tests
```shell
sbt test
```

## Take test coverage
```shell
sbt testCoverage
```
The command will generates a test coverage in the directory `target/scala-2.13/scoverage-report`.

## Build Scaladoc
```shell
sbt doc
```
The command will generates a Scaladoc in the directory `target/scala-2.13/api`.
