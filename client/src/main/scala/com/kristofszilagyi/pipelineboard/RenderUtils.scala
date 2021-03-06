package com.kristofszilagyi.pipelineboard

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}

import com.kristofszilagyi.pipelineboard.shared.BuildStatus.{Aborted, Building, Created, Failed, Pending, Successful, Unstable}
import com.kristofszilagyi.pipelineboard.shared.InstantOps._
import com.kristofszilagyi.pipelineboard.shared.ZonedDateTimeOps._
import com.kristofszilagyi.pipelineboard.shared._
import japgolly.scalajs.react.vdom.PackageBase.VdomAttr
import japgolly.scalajs.react.vdom.svg_<^.{^, _}
import japgolly.scalajs.react.vdom.{SvgTagOf => _, TagMod => _, _}
import org.scalajs.dom.raw._
import org.scalajs.dom.svg.{A, G, SVG}
import slogging.LazyLogging
import TypeSafeAttributes._
import com.kristofszilagyi.pipelineboard.shared.pixel.Pixel._
import TypeSafeEqualsOps._
import cats.data.NonEmptyList
import com.kristofszilagyi.pipelineboard.shared.NonEmptyListOps.RichNonEmptyList
import japgolly.scalajs.react.vdom.all.svg

import scala.collection.immutable
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scalacss.ScalaCssReact._

@SuppressWarnings(Array(Wart.Overloading))
object RenderUtils extends LazyLogging {

  def alignmentBaseline: VdomAttr[Any] = VdomAttr("alignmentBaseline")

  def dominantBaseline: VdomAttr[Any] = VdomAttr("dominantBaseline")

  def className: VdomAttr[Any] = VdomAttr("className")

  def target: VdomAttr[Any] = VdomAttr("target")

  def a: SvgTagOf[A] = SvgTagOf[A]("a")

  def href: VdomAttr[Any] = VdomAttr("href")

  def textDecoration = VdomAttr("textDecoration")

  def animation = VdomAttr("animation")

  val textAnchorEnd = "end"
  val textAnchorStart = "start"
  val textAnchorMiddle = "middle"

  val alignmentBaselineMiddle = "middle"
  val dominantBaselineCentral = "central"

  val lineAndGroupNameColor = "darkgrey"

  final case class JobArea(width: WPixel, endTime: Instant, drawingAreaDuration: FiniteDuration) {
    def startTime: Instant = endTime - drawingAreaDuration

    def length: FiniteDuration = endTime - startTime
  }

  @SuppressWarnings(Array(Wart.DefaultArguments))
  def nestAt(x: XPixel = 0.xpx, y: YPixel = 0.ypx, elements: Seq[TagMod]): TagOf[SVG] = {
    <.svg(elements: _*).x(x).y(y)
  }

  def nestAt(x: XPixel, y: YPixel, elements: TagMod): TagOf[SVG] = {
    nestAt(x, y, List(elements))
  }

  @SuppressWarnings(Array(Wart.DefaultArguments))
  def moveTo(x: XPixel = 0.xpx, y: YPixel = 0.ypx, elements: Seq[TagMod]): TagOf[G] = {
    <.g(elements ++ List(^.transform := s"translate(${x.d.toInt}, ${y.d.toInt})"): _*)
  }

  def moveTo(x: XPixel, y: YPixel, elements: TagMod): TagOf[G] = {
    moveTo(x, y, List(elements))
  }

  def verticalLines(topOfVerticalLines: YPixel, bottomOfVerticalLines: YPixel, timestampText: YPixel,
                    jobArea: JobArea, timeZone: ZoneId): immutable.Seq[TagOf[SVGElement]] = {
    val maxHorizontalBar = 5
    (0 to maxHorizontalBar) flatMap { idx =>
      val x = (jobArea.width / maxHorizontalBar * idx).toX
      val yStart = topOfVerticalLines
      val yEnd = bottomOfVerticalLines
      val timeOnBar = jobArea.endTime.atZone(timeZone) - jobArea.drawingAreaDuration + idx.toDouble / maxHorizontalBar * jobArea.drawingAreaDuration
      List(
        <.line(
          ^.strokeWidth := "1",
          ^.stroke := lineAndGroupNameColor
        ).x1(x)
          .y1(yStart)
          .x2(x)
          .y2(yEnd),
        <.text(
          ^.textAnchor := "middle",
          timeOnBar.format(DateTimeFormatter.ofPattern("uuuu-MMM-dd HH:mm"))
        ).x(x)
          .y(timestampText)
      )
    }
  }

  def strip(jobAreaWidth: WPixel, stripHeight: HPixel, color: String, elementsInside: Seq[TagMod]): TagOf[SVG] = {

    val background = <.rect(
      ^.fill := color,
    ).height(stripHeight)
      .width(jobAreaWidth)
    <.svg(
      List(
        background
      ) ++ elementsInside: _*
    ).height(stripHeight)
      .width(jobAreaWidth)
  }

  def jobRectanges(jobState: JobDetails, jobArea: JobArea, rectangleHeight: HPixel, stripHeight: HPixel): Seq[TagOf[SVGElement]] = {
    jobState.builds.r match {
      case Left(err) =>
        List(<.text(
          ^.fill := "red",
          alignmentBaseline := alignmentBaselineMiddle,
          err.s.replaceAll("\n", " "),
          <.title(err.s)
        ).y(stripHeight.toY / 2), <.title(err.s))
      case Right(runs) =>
        if (runs.isEmpty)
          List(<.text(
            ^.fill := "red",
            alignmentBaseline := alignmentBaselineMiddle,
            "Empty"
          ).y(stripHeight.toY / 2))
        else {
          val notSlottedBuilds = runs.flatMap(either => either match {
            case Right(build) =>
              List(build)
            case Left(error) =>
              logger.warn(s"Build failed to query: ${error.s}")
              None.toList
          })

          val slottedBuilds = ParallelJobManager.slotify(ParallelJobManager.overlappingIslands(notSlottedBuilds)).map(_.builds)
          slottedBuilds.flatMap { island =>
            val numberOfSlots = island.keySet.size
            island.map {
              case (slot, buildsInSlot) =>
                buildsInSlot.map { build =>
                  val startRelativeToDrawingAreaBeginning = (build.buildStart - jobArea.startTime).max(0.seconds)
                  val endRelativeToDrawingAreaBeginning = build.maybeBuildFinish match {
                    case Some(buildFinish) => (buildFinish - jobArea.startTime).min(jobArea.length)
                    case None => jobArea.length
                  }

                  val buildRectangle = if (endRelativeToDrawingAreaBeginning.toNanos > 0 &&
                    startRelativeToDrawingAreaBeginning < jobArea.length) {

                    val relativeStartRatio = startRelativeToDrawingAreaBeginning / jobArea.length
                    val relativeEndRatio = endRelativeToDrawingAreaBeginning / jobArea.length

                    val relativeWidthRatio = relativeEndRatio - relativeStartRatio //todo assert if this is negative, also round up to >10?
                    val startPx = (jobArea.width * relativeStartRatio).toX
                    //todo this will go out of the drawing area, fix
                    val width = 4.wpx.max(jobArea.width * relativeWidthRatio) //todo display these nicely, probably not really a problem
                    //todo header, colors, hovering, zooming, horizontal lines, click

                    val style: List[TagMod] = List(MyStyles.rectange, build.buildStatus match {
                      case Created => MyStyles.created
                      case Pending => MyStyles.pending
                      case Building => MyStyles.building
                      case Failed => MyStyles.failed
                      case Successful => MyStyles.success
                      case Aborted => MyStyles.aborted
                      case Unstable => MyStyles.unstable
                    })

                    val finishString = build.maybeBuildFinish.map(time => s"Finish: $time\n").getOrElse("")
                    val nonStyle = List(
                      className := s"build_rect",
                      //todo add length
                      //todo replace this with jQuery or sg similar and make it pop up immediately not after delay and not browser dependent way
                      <.title(s"Id: ${build.buildNumber.i}\nStart: ${build.buildStart}\n${finishString}Status: ${build.buildStatus}")
                    )
                    val slottedRectangleHeight = rectangleHeight / numberOfSlots
                    val slottedRectangleY = ((stripHeight - rectangleHeight) / 2).toY + (slottedRectangleHeight * slot.i).toY
                    Some(
                      a(svg.xlinkHref := jobState.jobDescription.buildUi(build.buildNumber).rawString,
                        target := "_blank",
                        <.rect(nonStyle ++ style: _*)
                          .x(startPx)
                          .y(slottedRectangleY)
                          .width(width)
                          .height(slottedRectangleHeight.d.max(1.0).hpx)
                      )
                    )
                  } else
                    None
                  buildRectangle.toList
                }
            }
          }
        }.flatten.flatten.toList

    }
  }

  sealed abstract class TextAnchor(val s: String)

  object TextAnchor {

    final case object Start extends TextAnchor("start")

    final case object End extends TextAnchor("end")

  }

  def labels(groups: Seq[(GroupName, JobGroup)], anchor: TextAnchor, stripHeight: HPixel, position: XPixel): ArrangeResult = {
    val unpositionedLabels = groups.toList.flatMap { case (groupName, group) =>
      group.jobs.zipWithIndex.map { case (jobState, idxWithinGroup) =>
        val numberOfErrors = jobState.builds.r.getOrElse(Seq.empty).map(_.isLeft).count(_ ==== true)
        val warningMsg = if (numberOfErrors > 0) {
          "\u26A0 "
        } else ""

        ElementWithHeight(
          <.text(
            ^.textAnchor := anchor.s,
            dominantBaseline := dominantBaselineCentral,
            <.tspan(
              ^.fill := "red",
              <.title(s"$numberOfErrors build was not shown due to errors. Please check out the JavaScript console for details."),
              warningMsg,
            ),
            a(
              svg.xlinkHref := jobState.jobDescription.urls.userRoot.u.rawString,
              target := "_blank",
              <.tspan(
                textDecoration := "underline",
                ^.fill := "black",
                jobState.jobDescription.name.s
              )
            ),
          )
            .x(position)
            .y(stripHeight.toY / 2),
          stripHeight
        )
      }
    }

    VerticalBoxLayout.arrange(unpositionedLabels)
  }

  def groupNameLabels(groups: Seq[(GroupName, JobGroup)], anchor: TextAnchor, stripHeight: HPixel, position: XPixel): Seq[TagOf[SVGElement]] = {
    val unpositionedLabels = groups.toList.flatMap { case (groupName, group) =>
      group.jobs.zipWithIndex.map { case (jobState, idxWithinGroup) =>

        ElementWithHeight(
          <.text(
            ^.textAnchor := anchor.s,
            dominantBaseline := dominantBaselineCentral,

            <.tspan(
              ^.fill := "black",
              if (idxWithinGroup ==== 0) groupName.s
              else ""
            )
          )
            .x(position)
            .y(stripHeight.toY / 2),
        stripHeight)
      }
    }

    VerticalBoxLayout.arrange(unpositionedLabels).elements
  }

  def groupBackgrounds(groups: Seq[(GroupName, JobGroup)], left: XPixel, right: XPixel, stripHeight: HPixel): Seq[TagOf[SVGElement]] = {
    val colors = NonEmptyList.of("lightblue", "#4fabc9")
    val rectanges = groups.zipWithIndex.map { case ((groupName, group), idx) =>
      ElementWithHeight(
        <.rect(
          ^.fill := colors.choose(idx),
          <.title(groupName.s)
        )
          .x(left)
          .width((right - left).toW)
          .height(stripHeight * group.jobs.size),
        stripHeight * group.jobs.size
      )
    }

    VerticalBoxLayout.arrange(rectanges).elements

  }
}
