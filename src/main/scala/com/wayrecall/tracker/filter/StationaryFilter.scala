package com.wayrecall.tracker.filter

import zio.*
import com.wayrecall.tracker.domain.GpsPoint
import com.wayrecall.tracker.config.StationaryFilterConfig

/**
 * Фильтр стоянок - определяет, нужно ли публиковать точку в Kafka
 * Чисто функциональный - без побочных эффектов
 */
trait StationaryFilter:
  /**
   * Определяет, нужно ли публиковать точку
   * true = движение (публикуем), false = стоянка (не публикуем)
   */
  def shouldPublish(point: GpsPoint, prev: Option[GpsPoint]): Boolean

object StationaryFilter:
  
  // Accessor метод
  def shouldPublish(point: GpsPoint, prev: Option[GpsPoint]): URIO[StationaryFilter, Boolean] =
    ZIO.serviceWith(_.shouldPublish(point, prev))
  
  /**
   * Live реализация
   */
  final case class Live(config: StationaryFilterConfig) extends StationaryFilter:
    
    override def shouldPublish(point: GpsPoint, prev: Option[GpsPoint]): Boolean =
      prev match
        case None => 
          // Первая точка - всегда публикуем
          true
        case Some(prevPoint) =>
          val distance = point.distanceTo(prevPoint)
          val isMoving = distance >= config.minDistanceMeters || point.speed >= config.minSpeedKmh
          isMoving
  
  /**
   * ZIO Layer
   */
  val live: ZLayer[StationaryFilterConfig, Nothing, StationaryFilter] =
    ZLayer.fromFunction(Live(_))
