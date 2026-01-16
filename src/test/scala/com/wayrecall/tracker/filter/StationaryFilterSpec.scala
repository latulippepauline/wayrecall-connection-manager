package com.wayrecall.tracker.filter

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.wayrecall.tracker.domain.GpsPoint
import com.wayrecall.tracker.config.StationaryFilterConfig

/**
 * Тесты для StationaryFilter
 */
object StationaryFilterSpec extends ZIOSpecDefault:
  
  // Тестовая конфигурация
  val testConfig = StationaryFilterConfig(
    minDistanceMeters = 20,
    minSpeedKmh = 2
  )
  
  val filter = StationaryFilter.Live(testConfig)
  
  // Базовая точка для тестов (Москва)
  def basePoint(vehicleId: Long = 1L, speed: Int = 0): GpsPoint = GpsPoint(
    vehicleId = vehicleId,
    latitude = 55.7539,
    longitude = 37.6208,
    altitude = 150,
    speed = speed,
    angle = 0,
    satellites = 10,
    timestamp = java.lang.System.currentTimeMillis()
  )
  
  def spec = suite("StationaryFilter")(
    
    test("публикует первую точку когда prev = None") {
      val point = basePoint()
      assertTrue(filter.shouldPublish(point, None))
    },
    
    test("не публикует если стоим на месте (малое расстояние + низкая скорость)") {
      val prev = basePoint()
      // ~10м от предыдущей точки
      val point = prev.copy(
        latitude = prev.latitude + 0.00009,
        speed = 0
      )
      assertTrue(!filter.shouldPublish(point, Some(prev)))
    },
    
    test("публикует если расстояние >= минимального") {
      val prev = basePoint()
      // ~50м от предыдущей точки
      val point = prev.copy(
        latitude = prev.latitude + 0.00045,
        speed = 0
      )
      assertTrue(filter.shouldPublish(point, Some(prev)))
    },
    
    test("публикует если скорость >= минимальной") {
      val prev = basePoint()
      // Та же позиция, но скорость > 2 км/ч
      val point = prev.copy(speed = 5)
      assertTrue(filter.shouldPublish(point, Some(prev)))
    },
    
    test("не публикует при малом расстоянии И низкой скорости") {
      val prev = basePoint(speed = 1)
      // ~5м от предыдущей, скорость 1 км/ч
      val point = prev.copy(
        latitude = prev.latitude + 0.00005,
        speed = 1
      )
      assertTrue(!filter.shouldPublish(point, Some(prev)))
    },
    
    test("публикует при большом расстоянии даже с нулевой скоростью") {
      val prev = basePoint()
      // ~100м от предыдущей
      val point = prev.copy(
        latitude = prev.latitude + 0.0009,
        speed = 0
      )
      assertTrue(filter.shouldPublish(point, Some(prev)))
    }
  )
