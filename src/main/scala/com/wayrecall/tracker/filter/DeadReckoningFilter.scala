package com.wayrecall.tracker.filter

import zio.*
import com.wayrecall.tracker.domain.{GpsRawPoint, GpsPoint, FilterError}
import com.wayrecall.tracker.config.DeadReckoningFilterConfig

/**
 * Фильтр Dead Reckoning - валидация GPS координат
 * Чисто функциональный интерфейс
 */
trait DeadReckoningFilter:
  def validate(point: GpsRawPoint): IO[FilterError, Unit]
  def validateWithPrev(point: GpsRawPoint, prev: Option[GpsPoint]): IO[FilterError, Unit]

object DeadReckoningFilter:
  
  // Accessor методы
  def validate(point: GpsRawPoint): ZIO[DeadReckoningFilter, FilterError, Unit] =
    ZIO.serviceWithZIO(_.validate(point))
  
  def validateWithPrev(point: GpsRawPoint, prev: Option[GpsPoint]): ZIO[DeadReckoningFilter, FilterError, Unit] =
    ZIO.serviceWithZIO(_.validateWithPrev(point, prev))
  
  /**
   * Live реализация
   */
  final case class Live(config: DeadReckoningFilterConfig) extends DeadReckoningFilter:
    
    // Максимальное время из будущего (5 минут в миллисекундах)
    private val maxFutureMs = 5L * 60L * 1000L
    
    override def validate(point: GpsRawPoint): IO[FilterError, Unit] =
      validateSpeed(point) *>
      validateCoordinates(point) *>
      validateTimestamp(point)
    
    override def validateWithPrev(point: GpsRawPoint, prev: Option[GpsPoint]): IO[FilterError, Unit] =
      validate(point) *>
      ZIO.foreach(prev)(validateNoTeleportation(point, _)).unit
    
    private def validateSpeed(point: GpsRawPoint): IO[FilterError, Unit] =
      ZIO.fail(FilterError.ExcessiveSpeed(point.speed, config.maxSpeedKmh))
        .when(point.speed > config.maxSpeedKmh)
        .unit
    
    private def validateCoordinates(point: GpsRawPoint): IO[FilterError, Unit] =
      val validLat = point.latitude >= -90 && point.latitude <= 90
      val validLon = point.longitude >= -180 && point.longitude <= 180
      
      ZIO.fail(FilterError.InvalidCoordinates(point.latitude, point.longitude))
        .unless(validLat && validLon)
        .unit
    
    private def validateTimestamp(point: GpsRawPoint): IO[FilterError, Unit] =
      Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).flatMap { now =>
        ZIO.fail(FilterError.FutureTimestamp(point.timestamp, now))
          .when(point.timestamp > now + maxFutureMs)
          .unit
      }
    
    private def validateNoTeleportation(point: GpsRawPoint, prev: GpsPoint): IO[FilterError, Unit] =
      val distance = calculateDistance(
        point.latitude, point.longitude,
        prev.latitude, prev.longitude
      )
      val timeDiff = Math.abs(point.timestamp - prev.timestamp) / 1000.0
      val effectiveTime = if timeDiff < 1 then 1.0 else timeDiff
      val maxDistance = config.maxJumpMeters * effectiveTime / config.maxJumpSeconds
      
      ZIO.fail(FilterError.Teleportation(distance, maxDistance))
        .when(distance > maxDistance)
        .unit
    
    /**
     * Формула Хаверсина для расчета расстояния между двумя точками
     */
    private def calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
      val earthRadiusKm = 6371.0
      val latRad1 = Math.toRadians(lat1)
      val latRad2 = Math.toRadians(lat2)
      val deltaLatRad = Math.toRadians(lat2 - lat1)
      val deltaLonRad = Math.toRadians(lon2 - lon1)
      
      val a = Math.sin(deltaLatRad / 2) * Math.sin(deltaLatRad / 2) +
              Math.cos(latRad1) * Math.cos(latRad2) *
              Math.sin(deltaLonRad / 2) * Math.sin(deltaLonRad / 2)
      
      val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
      earthRadiusKm * c * 1000 // метры
  
  /**
   * ZIO Layer
   */
  val live: ZLayer[DeadReckoningFilterConfig, Nothing, DeadReckoningFilter] =
    ZLayer.fromFunction(Live(_))
