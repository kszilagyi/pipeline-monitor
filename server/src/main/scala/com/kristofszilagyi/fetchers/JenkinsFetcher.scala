package com.kristofszilagyi.fetchers

import java.time.Instant

import akka.typed._
import akka.typed.scaladsl.Actor
import com.kristofszilagyi.fetchers.JenkinsFetcher._
import com.kristofszilagyi.shared._
import com.netaporter.uri.dsl._
import play.api.libs.ws._
import com.kristofszilagyi.utils.Utopia
import com.kristofszilagyi.utils.Utopia.RichFuture
import io.circe._
import io.circe.generic.JsonCodec
import shapeless.{:+:, CNil, |∨|}
import slogging.LazyLogging
import TypeSafeEqualsOps._
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object JenkinsFetcher {
  @SuppressWarnings(Array(Wart.Public))
  private object JenkinsJson { //this object is only here because @JsonCodec has the public wart :(
    //todo probably we should have a custom deserializer instead of having an option and do a getOrElse on it
    @JsonCodec final case class PartialDetailedBuildInfo(result: Option[JenkinsBuildStatus], timestamp: Long, duration: Int)

    @JsonCodec final case class PartialBuildInfo(number: Int)
    @JsonCodec final case class PartialJobInfo(builds: Seq[PartialBuildInfo])
  }


  final case class JobInfoWithoutBuildInfo(job: Job, jobNumbers: Seq[BuildNumber])

  sealed trait JenkinsFetcherIncoming

  final case class JobsInfoWithoutBuildInfo(replyTo: ActorRef[FetcherResult],
                                                    results: Seq[Either[JobDetails, JenkinsFetcher.JobInfoWithoutBuildInfo]]) extends JenkinsFetcherIncoming

  final case class Fetch(replyTo: ActorRef[FetcherResult]) extends JenkinsFetcherIncoming


}
trait Fetcher {
  def name: String
  def behaviour: Behavior[Fetch]
}

//todo add caching
//todo replyto should be here if possible
final class JenkinsFetcher (ws: WSClient,
                      jobsToFetch: Seq[Job])(implicit ec: ExecutionContext) extends LazyLogging with Fetcher {
  import JenkinsJson.{PartialDetailedBuildInfo, PartialJobInfo}

  private def fetchDetailedInfo(replyTo: ActorRef[FetcherResult], jobs: Seq[Either[JobDetails, JenkinsFetcher.JobInfoWithoutBuildInfo]]) {
    def fetchBuildResults(job: Job, buildNumbers: Seq[BuildNumber]) = {
      val buildInfoFutures = buildNumbers.map { buildNumber =>
        val destination = job.buildInfo(buildNumber)
        ws.url(destination.rawString).get.map(result => safeRead[PartialDetailedBuildInfo](destination, result)
          .map { buildInfo =>
            val buildStatus = buildInfo.result.getOrElse(JenkinsBuildStatus.Building).toBuildStatus
            val startTime = Instant.ofEpochMilli(buildInfo.timestamp)
            val endTime = if (buildStatus !=== BuildStatus.Building) Some(startTime.plusMillis(buildInfo.duration.toLong))
            else None
            BuildInfo(buildStatus,
              startTime, endTime, buildNumber)
          }
        ).lift noThrowingMap  {
          case Failure(exception) => Left(ResponseError.failedToConnect(destination, exception))
          case Success(value) => value
        }
      }

      Utopia.sequence(buildInfoFutures) noThrowingMap { buildInfo =>
        JobDetails(job, Right(buildInfo))
      }
    }
    val futureResults = jobs.map {
      case Left(fetchResult) => Utopia.finished(fetchResult)
      case Right(JobInfoWithoutBuildInfo(job, buildNumbers)) =>
        fetchBuildResults(job, buildNumbers)
    }
    Utopia.sequence(futureResults) onComplete {
      replyTo ! FetcherResult(_)
    }
  }

  @SuppressWarnings(Array(Wart.Null, Wart.Public)) //I think these are false positive
  val behaviour: Actor.Immutable[Fetch] = Actor.immutable[Fetch] { (ctx, msg) =>
    msg match {
      case Fetch(replyTo) =>
        def fetchJobDetails(job: Job) = {
          val jobUrl = job.jobInfo
          ws.url(jobUrl.rawString).get.map(safeRead[PartialJobInfo](jobUrl, _)).lift.noThrowingMap{
            case Success(maybePartialJenkinsJobInfo) => maybePartialJenkinsJobInfo match {
              case Left(error) => Left(JobDetails(job, Left(error)))
              case Right(jenkinsJobInfo) => Right(JobInfoWithoutBuildInfo(
                job,
                jenkinsJobInfo.builds.map(partialBuildInfo => BuildNumber(partialBuildInfo.number))
              ))
            }
            case Failure(t) => Left(JobDetails(job, Left(ResponseError.failedToConnect(jobUrl, t))))
          }
        }

        val future = Utopia.sequence(jobsToFetch.map { job =>
          fetchJobDetails(job)
        })
        future onComplete { jobsWithoutDetailedInfo =>
          fetchDetailedInfo(replyTo, jobsWithoutDetailedInfo)
        }

        Actor.same
    }
  }

  def name: String = "jenkins"
}


