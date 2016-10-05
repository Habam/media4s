package org.matthicks.media4s.video.transcode

import java.io.File

import com.outr.scribe.Logging
import org.matthicks.media4s.video.{Preset, VideoProfile, VideoUtil}
import org.matthicks.media4s.video.codec.{AudioCodec, VideoCodec}
import org.matthicks.media4s.video.filter.VideoFilter
import org.powerscala.concurrent.{Elapsed, Time}
import org.powerscala.concurrent.Time._

import scala.sys.process.ProcessLogger
import scala.sys.process._

class FFMPEGTranscoder(args: List[FFMPEGArgument]) extends Logging {
  private val ProgressRegex = """frame=\s*(\d+) fps=\s*([0-9.]+) q=([-0-9.]+) (L?)size=\s*(\d+)kB time=(\d{2}):(\d{2}):(\d{2})[.](\d+) bitrate=\s*([0-9.]+)kbits/s.*""".r

  lazy val command: List[String] = {
    "ffmpeg" :: args.flatMap(_.args.map(_.toString))
  }

  def withArgs(arguments: Any*): FFMPEGTranscoder = {
    new FFMPEGTranscoder(args ::: List(FFMPEGArgument(arguments.toList)))
  }
  def findArg(param: String): Option[FFMPEGArgument] = {
    args.find(_.args.head == param)
  }

  /******** Convenience Command-Line Args ***********/
  def input(file: File) = i(file)
  def output(file: File) = o(file)
  def inputDelay(seconds: Double) = itsoffset(seconds)
  def videoFrames(frameCount: Int) = vframes(frameCount)
  def videoCodec(c: VideoCodec) = codec.v(c)
  def videoProfile(p: VideoProfile) = profile.v(p)
  def videoBitRate(bitRate: Long) = b.v(bitRate)
  def maxRate(bitRate: Long) = maxrate(bitRate)
  def bufferSize(size: Long) = bufsize(size)
  def audioCodec(c: AudioCodec) = codec.a(c)
  def audioBitRate(bitRate: Long) = b.a(bitRate)
  def audioQuality(quality: Int) = qscale.a(quality)
  def forceFormat(format: String) = f(format)
  def disableAudio() = an()
  def videoFilters(filters: List[VideoFilter]) = vf(filters)
  def start(seconds: Double) = ss(seconds)
  def duration(seconds: Double) = t(seconds)

  /******** Standard Command-Line Args ***********/

  def an() = withArgs("-an")
  def bufsize(size: Long) = withArgs("-bufsize", size)
  def f(format: String) = withArgs("-f", format)
  def i(file: File) = withArgs("-i", FFMPEGFile(file))
  def id3v2_version(version: Int) = withArgs("-id3v2_version", version)
  def itsoffset(seconds: Double) = withArgs("-itsoffset", math.round(seconds))
  def map(id: Int) = withArgs("-map", id)
  def map(inId: Int, outId: Int) = withArgs("-map", s"$inId:$outId")
  def map_metadata(id: Int) = withArgs("-map_metadata", id)
  def maxrate(maxRate: Long) = withArgs("-maxrate", maxRate)
  def o(file: File) = withArgs(FFMPEGFile(file))
  def pass(pass: Int) = withArgs("-pass", pass)
  def preset(preset: Preset) = withArgs("-preset", preset.value)
  def ss(seconds: Double) = withArgs("-ss", FFMPEGTime(seconds))
  def t(seconds: Double) = withArgs("-t", FFMPEGTime(seconds))
  def threads(threadCount: Int) = withArgs("-threads", threadCount)
  def vf(filters: List[VideoFilter]) = withArgs("-vf", filters.map(_.value).mkString(","))
  def vframes(frameCount: Int) = withArgs("-vframes", frameCount)
  def overwrite() = withArgs("-y")
  def noOverwrite() = withArgs("-n")
  object b {
    def a(bitRate: Long) = withArgs("-b:a", bitRate)
    def v(bitRate: Long) = withArgs("-b:v", bitRate)
  }
  object codec {
    def copy() = withArgs("-c", "copy")
    def a(codec: AudioCodec) = withArgs("-codec:a", codec.value)
    def v(codec: VideoCodec) = withArgs("-codec:v", codec.value)
  }
  object profile {
    def v(profile: VideoProfile) = withArgs("-profile:v", profile.value)
  }
  object qscale {
    def a(quality: Int) = withArgs("-qscale:a", quality)
  }

  def execute(monitor: Option[TranscodeListener] = None, nicePriority: Option[Int] = Some(10)): Unit = {
    val inputFile = findArg("-i").getOrElse(throw new RuntimeException(s"No input file defined for ${command.mkString(" ")}")).args.tail.head.asInstanceOf[FFMPEGFile].file
    val info = VideoUtil.info(inputFile)
    val duration = args.find(_.args.head == "-t").map(_.args.tail.head.asInstanceOf[FFMPEGTime].seconds).getOrElse(0.0)

    logger.debug(command.mkString(" "))
    val start = System.currentTimeMillis()
    var lastLine: String = null
    val log = (line: String) => line match {
      case ProgressRegex(frame, fps, q, last, size, hours, minutes, seconds, millis, bitRate) => {
        val timeProcessed = seconds.toDouble + (minutes.toDouble * 60.0) + (hours.toDouble * 60.0 * 60.0)
        val percentage = if (duration != 0.0) {
          timeProcessed / duration
        } else {
          timeProcessed / info.duration
        }
        val time = hours.toDouble.hours + minutes.toDouble.minutes + seconds.toDouble.seconds + (millis.toDouble / 100.0)
        val elapsed = Time.fromMillis(System.currentTimeMillis() - start)
        val finished = last == "L"
        monitor.foreach(_.progress(percentage, frame.toInt, fps.toDouble, q.toDouble, math.round(size.toDouble * 1000), time, math.round(bitRate.toDouble * 1000), elapsed, finished))
      }
      case _ => {
        logger.debug(line)
        monitor.foreach(_.log(line))
        lastLine = line
      }
    }
    val niceCommand = nicePriority match {
      case Some(priority) => List("nice", s"--adjustment=$priority") ::: command
      case None => command
    }
    logger.debug(s"Command: ${niceCommand.mkString(" ")}")
    val result = niceCommand ! ProcessLogger(log)
    if (result != 0) {
      throw new TranscodeFailedException(s"Failed transcoding (${command.mkString(" ")}). Received result: $result. $lastLine")
    }
  }
}

case class FFMPEGFile(file: File) {
  override def toString: String = file.getAbsolutePath
}

case class FFMPEGTime(seconds: Double) {
  override def toString: String = {
    val e = Elapsed(seconds)
    f"${e.hours}%02d:${e.minutes}%02d:${e.seconds}%02d"
  }
}

case class FFMPEGArgument(args: List[Any])

object FFMPEGTranscoder {
  private val base = new FFMPEGTranscoder(Nil)

  def apply(): FFMPEGTranscoder = base
}
