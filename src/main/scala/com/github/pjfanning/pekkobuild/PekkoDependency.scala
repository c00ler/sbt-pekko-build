/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package com.github.pjfanning.pekkobuild


import sbt._
import sbt.Keys._

import scala.util.matching.Regex.Groups
import com.github.pjfanning.pekkobuild.PekkoBuildPlugin.autoImport.*

object PekkoDependency {

  sealed trait Pekko {
    def version: String
    // The version to use in api/japi/docs links,
    // so 'x.y', 'x.y.z', 'current' or 'snapshot'
    def link: String
  }
  case class Artifact(version: String, isSnapshot: Boolean) extends Pekko {
    override def link = VersionNumber(version) match { case VersionNumber(Seq(x, y, _*), _, _) => s"$x.$y" }
  }
  object Artifact {
    def apply(version: String): Artifact = {
      val isSnap = version.endsWith("-SNAPSHOT")
      new Artifact(version, isSnap)
    }
  }
  case class Sources(uri: String, link: String = "current") extends Pekko {
    def version = link
  }

  def pekkoDependency(defaultVersion: String): Pekko = {
    Option(System.getProperty("pekko.sources")) match {
      case Some(pekkoSources) =>
        Sources(pekkoSources)
      case None =>
        Option(System.getProperty("pekko.build.pekko.version")) match {
          case Some("main")           => mainSnapshot
          case Some("default") | None => Artifact(defaultVersion)
          case Some(other)            => Artifact(other, true)
        }
    }
  }

  val default = Def.setting {
    pekkoDependency(defaultVersion = (ThisBuild / pekkoMinVersion).value)
  }

  lazy val mainSnapshot = Artifact(determineLatestSnapshot(), true)

  val pekkoVersion: Def.Initialize[String] = Def.setting {
    default.value match {
      case Artifact(version, _) => version
      case Sources(uri, _)      => uri
    }
  }

  implicit class RichProject(project: Project) {

    /** Adds either a source or a binary dependency, depending on whether the above settings are set */
    def addPekkoModuleDependency(module: String,
        config: String = "",
        pekko: Pekko): Project =
      pekko match {
        case Sources(sources, _) =>
          // as a little hacky side effect also disable aggregation of samples
          System.setProperty("pekko.build.aggregateSamples", "false")

          val moduleRef = ProjectRef(uri(sources), module)
          val withConfig: ClasspathDependency =
            if (config == "") moduleRef
            else moduleRef % config

          project.dependsOn(withConfig)
        case Artifact(pekkoVersion, pekkoSnapshot) =>
          project.settings(
            libraryDependencies += {
              if (config == "")
                "org.apache.pekko" %% module % pekkoVersion
              else
                "org.apache.pekko" %% module % pekkoVersion % config
            },
            resolvers ++= (if (pekkoSnapshot)
                             Seq(Resolver.ApacheMavenSnapshotsRepo)
                           else Nil))
      }
  }

  private def determineLatestSnapshot(prefix: String = ""): String = {
    import gigahorse.GigahorseSupport.url
    import sbt.librarymanagement.Http.http

    import scala.concurrent.Await
    import scala.concurrent.duration.*

    val snapshotVersionR = """href=".*/((\d+)\.(\d+)\.(\d+)(-(M|RC)(\d+))?\+(\d+)-[0-9a-f]+-SNAPSHOT)/"""".r

    // pekko-cluster-sharding-typed_2.13 seems to be the last nightly published by `pekko-publish-nightly` so if that's there then it's likely the rest also made it
    val body = Await.result(http.run(url(
        s"${Resolver.ApacheMavenSnapshotsRepo.root}org/apache/pekko/pekko-cluster-sharding-typed_2.13/")),
      10.seconds).bodyAsString

    val allVersions =
      snapshotVersionR.findAllMatchIn(body)
        .map {
          case Groups(full, ep, maj, min, _, _, tagNumber, offset) =>
            (
              ep.toInt,
              maj.toInt,
              min.toInt,
              Option(tagNumber).map(_.toInt),
              offset.toInt) -> full
        }
        .filter(_._2.startsWith(prefix))
        .toVector.sortBy(_._1)
    allVersions.last._2
  }
}
