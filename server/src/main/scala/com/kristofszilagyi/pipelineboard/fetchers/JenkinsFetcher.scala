package com.kristofszilagyi.pipelineboard.fetchers

import java.time.Instant

import akka.typed._
import akka.typed.scaladsl.Actor
import com.kristofszilagyi.pipelineboard.fetchers.JenkinsFetcher._
import com.kristofszilagyi.pipelineboard.shared._
import play.api.libs.ws._
import com.kristofszilagyi.pipelineboard.utils.Utopia
import com.kristofszilagyi.pipelineboard.utils.Utopia.RichFuture
import io.circe._
import io.circe.generic.JsonCodec
import slogging.LazyLogging
import TypeSafeEqualsOps._
import com.kristofszilagyi.pipelineboard.FetcherResult
import com.kristofszilagyi.pipelineboard.actors.{FetchedJobBuilds}
import com.kristofszilagyi.pipelineboard.controllers.{JenkinsAccessToken, JenkinsUser}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


final case class JenkinsCredentials(user: JenkinsUser, token: JenkinsAccessToken)

final case class JenkinsJob(common: Job, maybeCredentials: Option[JenkinsCredentials]) {

  def authenticatedRestRequest(uri: RawUrl, ws: WSClient): WSRequest = {
    val baseRequest = ws.url(uri.rawString)
    maybeCredentials match {
      case Some(creds) =>
        baseRequest.withAuth(creds.user.s, creds.token.s, WSAuthScheme.BASIC)
      case None =>
        baseRequest
    }
  }
}

object JenkinsFetcher {
  @SuppressWarnings(Array(Wart.Public))
  private object JenkinsJson { //this object is only here because @JsonCodec has the public wart :(
    //todo probably we should have a custom deserializer instead of having an option and do a getOrElse on it
    @JsonCodec final case class PartialDetailedBuildInfo(result: Option[JenkinsBuildStatus], timestamp: Long, duration: Int)

    @JsonCodec final case class PartialBuildInfo(number: Int)
    @JsonCodec final case class PartialJobInfo(builds: Seq[PartialBuildInfo])
  }


  final case class JobInfoWithoutBuildInfo(job: JenkinsJob, jobNumbers: Seq[BuildNumber])

  sealed trait JenkinsFetcherIncoming

  final case class JobsInfoWithoutBuildInfo(replyTo: ActorRef[FetcherResult],
                                                    results: Seq[Either[FetcherResult, JenkinsFetcher.JobInfoWithoutBuildInfo]]) extends JenkinsFetcherIncoming

  //todo move out from here
  final case class Fetch(replyTo: ActorRef[FetcherResult]) extends JenkinsFetcherIncoming


}

//todo add caching
//todo replyto should be here if possible
final class JenkinsFetcher(ws: WSClient, jobToFetch: JenkinsJob)(implicit ec: ExecutionContext) extends LazyLogging with Fetcher {
  import JenkinsJson.{PartialDetailedBuildInfo, PartialJobInfo}

  private def fetchDetailedInfo(replyTo: ActorRef[FetcherResult], job: Either[FetcherResult, JenkinsFetcher.JobInfoWithoutBuildInfo]) {
    def fetchBuildResults(job: JenkinsJob, buildNumbers: Seq[BuildNumber]) = {
      val buildInfoFutures = buildNumbers.map { buildNumber =>
        val destination = job.common.buildInfo(buildNumber)
        job.authenticatedRestRequest(destination, ws).get.map(result => safeRead[PartialDetailedBuildInfo](destination, result)
          .map { buildInfo =>
            val buildStatus = buildInfo.result.getOrElse(JenkinsBuildStatus.Building).toBuildStatus
            val startTime = Instant.ofEpochMilli(buildInfo.timestamp)
            val endTime = if (buildStatus !=== BuildStatus.Building) Some(startTime.plusMillis(buildInfo.duration.toLong))
            else None
            BuildInfo(buildStatus,
              startTime, endTime, buildNumber)
          }
        ).lift noThrowingMap  {
          case Failure(exception) => buildNumber -> Left(ResponseError.failedToConnect(destination, exception))
          case Success(value) => buildNumber-> value
        }
      }

      Utopia.sequence(buildInfoFutures) noThrowingMap { buildInfo =>
        FetcherResult(job.common, FetchedJobBuilds(Right(buildInfo.toMap), Instant.now()))
      }
    }
    val futureResults = job match {
      case Left(fetchResult) => Utopia.finished(fetchResult)
      case Right(JobInfoWithoutBuildInfo(j, buildNumbers)) =>
        fetchBuildResults(j, buildNumbers)
    }
    futureResults onComplete {
      replyTo ! _
    }
  }

  @SuppressWarnings(Array(Wart.Null, Wart.Public)) //I think these are false positive
  val behaviour: Actor.Immutable[Fetch] = Actor.immutable[Fetch] { (ctx, msg) =>
    msg match {
      case Fetch(replyTo) =>
        def fetchJobDetails(job: JenkinsJob) = {
          val jobUrl = job.common.jobInfo
          job.authenticatedRestRequest(jobUrl, ws).get.map(safeRead[PartialJobInfo](jobUrl, _)).lift.noThrowingMap{
            case Success(maybePartialJenkinsJobInfo) => maybePartialJenkinsJobInfo match {
              case Left(error) => Left(FetcherResult(job.common, FetchedJobBuilds(Left(error), Instant.now())))
              case Right(jenkinsJobInfo) => Right(JobInfoWithoutBuildInfo(
                job,
                jenkinsJobInfo.builds.map(partialBuildInfo => BuildNumber(partialBuildInfo.number))
              ))
            }
            case Failure(t) => Left(FetcherResult(job.common, FetchedJobBuilds(Left(ResponseError.failedToConnect(jobUrl, t)), Instant.now())))
          }
        }

        val future = fetchJobDetails(jobToFetch)

        future onComplete { jobWithoutDetailedInfo =>
          fetchDetailedInfo(replyTo, jobWithoutDetailedInfo)
        }

        Actor.same
    }
  }

  def name: String = {
    val encodedName = Fetcher.encodeForActorName(jobToFetch.common.name.s)
    s"jenkins-$encodedName"
  }
}


