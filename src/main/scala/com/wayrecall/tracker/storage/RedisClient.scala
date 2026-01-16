package com.wayrecall.tracker.storage

import zio.*
import zio.json.*
import io.lettuce.core.{RedisClient => LettuceClient, RedisURI}
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import com.wayrecall.tracker.domain.{GpsPoint, ConnectionInfo, RedisError}
import com.wayrecall.tracker.config.RedisConfig
import java.util.concurrent.CompletionStage
import scala.jdk.FutureConverters.*

/**
 * Клиент для работы с Redis - чисто функциональный интерфейс
 */
trait RedisClient:
  def getVehicleId(imei: String): IO[RedisError, Option[Long]]
  def getPosition(vehicleId: Long): IO[RedisError, Option[GpsPoint]]
  def setPosition(point: GpsPoint): IO[RedisError, Unit]
  def registerConnection(info: ConnectionInfo): IO[RedisError, Unit]
  def unregisterConnection(imei: String): IO[RedisError, Unit]

object RedisClient:
  
  // Accessor методы для ZIO service pattern
  def getVehicleId(imei: String): ZIO[RedisClient, RedisError, Option[Long]] =
    ZIO.serviceWithZIO(_.getVehicleId(imei))
  
  def getPosition(vehicleId: Long): ZIO[RedisClient, RedisError, Option[GpsPoint]] =
    ZIO.serviceWithZIO(_.getPosition(vehicleId))
  
  def setPosition(point: GpsPoint): ZIO[RedisClient, RedisError, Unit] =
    ZIO.serviceWithZIO(_.setPosition(point))
  
  def registerConnection(info: ConnectionInfo): ZIO[RedisClient, RedisError, Unit] =
    ZIO.serviceWithZIO(_.registerConnection(info))
  
  def unregisterConnection(imei: String): ZIO[RedisClient, RedisError, Unit] =
    ZIO.serviceWithZIO(_.unregisterConnection(imei))
  
  /**
   * Live реализация с Lettuce
   */
  final case class Live(
      commands: RedisAsyncCommands[String, String],
      config: RedisConfig
  ) extends RedisClient:
    
    // Ключи Redis - чистые функции
    private def vehicleKey(imei: String): String = s"vehicle:$imei"
    private def positionKey(vehicleId: Long): String = s"position:$vehicleId"
    private def connectionKey(imei: String): String = s"connection:$imei"
    
    override def getVehicleId(imei: String): IO[RedisError, Option[Long]] =
      fromCompletionStage(commands.get(vehicleKey(imei)))
        .map(Option(_).flatMap(_.toLongOption))
        .mapError(e => RedisError.OperationFailed(e.getMessage))
    
    override def getPosition(vehicleId: Long): IO[RedisError, Option[GpsPoint]] =
      fromCompletionStage(commands.get(positionKey(vehicleId)))
        .map { value =>
          Option(value).flatMap(_.fromJson[GpsPoint].toOption)
        }
        .mapError(e => RedisError.OperationFailed(e.getMessage))
    
    override def setPosition(point: GpsPoint): IO[RedisError, Unit] =
      val key = positionKey(point.vehicleId)
      val value = point.toJson
      val ttlSeconds = config.positionTtlSeconds
      
      fromCompletionStage(commands.setex(key, ttlSeconds, value))
        .unit
        .mapError(e => RedisError.OperationFailed(e.getMessage))
    
    override def registerConnection(info: ConnectionInfo): IO[RedisError, Unit] =
      fromCompletionStage(commands.set(connectionKey(info.imei), info.toJson))
        .unit
        .mapError(e => RedisError.OperationFailed(e.getMessage))
    
    override def unregisterConnection(imei: String): IO[RedisError, Unit] =
      fromCompletionStage(commands.del(connectionKey(imei)))
        .unit
        .mapError(e => RedisError.OperationFailed(e.getMessage))
    
    /**
     * Конвертирует Java CompletionStage в ZIO эффект
     */
    private def fromCompletionStage[A](cs: => CompletionStage[A]): Task[A] =
      ZIO.fromFuture(_ => cs.asScala)
  
  /**
   * ZIO Layer с управлением ресурсами
   */
  val live: ZLayer[RedisConfig, Throwable, RedisClient] =
    ZLayer.scoped {
      for
        config <- ZIO.service[RedisConfig]
        
        // Создаем URI для подключения
        uri = {
          val builder = RedisURI.builder()
            .withHost(config.host)
            .withPort(config.port)
            .withDatabase(config.database)
          config.password.foreach(pwd => builder.withPassword(pwd.toCharArray))
          builder.build()
        }
        
        // Создаем клиент с автоматическим закрытием
        client <- ZIO.acquireRelease(
          ZIO.attempt(LettuceClient.create(uri))
            .tap(_ => ZIO.logInfo(s"Redis клиент создан: ${config.host}:${config.port}"))
        )(client => 
          ZIO.attempt(client.shutdown()).orDie
            .tap(_ => ZIO.logInfo("Redis клиент закрыт"))
        )
        
        // Создаем соединение с автоматическим закрытием
        connection <- ZIO.acquireRelease(
          ZIO.attempt(client.connect())
            .tap(_ => ZIO.logInfo("Redis соединение установлено"))
        )(conn => 
          ZIO.attempt(conn.close()).orDie
            .tap(_ => ZIO.logInfo("Redis соединение закрыто"))
        )
        
        commands = connection.async()
      yield Live(commands, config)
    }
