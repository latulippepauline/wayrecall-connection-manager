package com.wayrecall.tracker.domain

import zio.json.*

/**
 * GPS точка с координатами, высотой, скоростью и углом наклона
 */
case class GpsPoint(
    vehicleId: Long,
    latitude: Double,      // градусы (-90..90)
    longitude: Double,     // градусы (-180..180)
    altitude: Int,         // метры
    speed: Int,            // км/ч
    angle: Int,            // градусы (0-360)
    satellites: Int,       // количество спутников
    timestamp: Long        // миллисекунды
) derives JsonCodec {
  
  /**
   * Вычисляет расстояние между этой точкой и другой в метрах
   * Использует формулу Хаверсина
   */
  def distanceTo(other: GpsPoint): Double = {
    val earthRadiusKm = 6371.0
    val latRad1 = Math.toRadians(latitude)
    val latRad2 = Math.toRadians(other.latitude)
    val deltaLatRad = Math.toRadians(other.latitude - latitude)
    val deltaLonRad = Math.toRadians(other.longitude - longitude)
    
    val a = Math.sin(deltaLatRad / 2) * Math.sin(deltaLatRad / 2) +
            Math.cos(latRad1) * Math.cos(latRad2) *
            Math.sin(deltaLonRad / 2) * Math.sin(deltaLonRad / 2)
    
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    val distance = earthRadiusKm * c * 1000 // в метрах
    
    distance
  }
}

/**
 * Сырые GPS данные парсеные из протокола
 */
case class GpsRawPoint(
    imei: String,
    latitude: Double,
    longitude: Double,
    altitude: Int,
    speed: Int,
    angle: Int,
    satellites: Int,
    timestamp: Long
) {
  /**
   * Преобразует сырую точку в валидную GpsPoint с vehicleId
   */
  def toValidated(vehicleId: Long): GpsPoint =
    GpsPoint(
      vehicleId = vehicleId,
      latitude = latitude,
      longitude = longitude,
      altitude = altitude,
      speed = speed,
      angle = angle,
      satellites = satellites,
      timestamp = timestamp
    )
}

/**
 * Информация о подключении трекера
 */
case class ConnectionInfo(
    imei: String,
    connectedAt: Long,
    remoteAddress: String,
    port: Int
) derives JsonCodec

/**
 * Информация о транспортном средстве
 */
case class Vehicle(
    id: Long,
    imei: String,
    name: String,
    deviceType: String
) derives JsonCodec

/**
 * Статус устройства
 */
case class DeviceStatus(
    imei: String,
    vehicleId: Long,
    isOnline: Boolean,
    lastSeen: Long,
    lastLatitude: Option[Double] = None,
    lastLongitude: Option[Double] = None
) derives JsonCodec
