package com.wayrecall.tracker

import zio.*
import zio.logging.backend.SLF4J
import com.wayrecall.tracker.config.*
import com.wayrecall.tracker.network.{TcpServer, ConnectionHandler, GpsProcessingService}
import com.wayrecall.tracker.protocol.{ProtocolParser, TeltonikaParser}
import com.wayrecall.tracker.storage.{RedisClient, KafkaProducer}
import com.wayrecall.tracker.filter.{DeadReckoningFilter, StationaryFilter}

/**
 * Точка входа Connection Manager Service
 * 
 * Использует ZIO Layer для композиции всех зависимостей
 */
object Main extends ZIOAppDefault:
  
  // Настройка логирования через SLF4J
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j
  
  /**
   * Основная программа - чисто декларативная
   */
  val program: ZIO[AppConfig & TcpServer & GpsProcessingService & ProtocolParser, Throwable, Unit] =
    for
      config <- ZIO.service[AppConfig]
      server <- ZIO.service[TcpServer]
      service <- ZIO.service[GpsProcessingService]
      parser <- ZIO.service[ProtocolParser]
      runtime <- ZIO.runtime[Any]
      
      _ <- ZIO.logInfo("=== Connection Manager Service ===")
      _ <- ZIO.logInfo(s"Teltonika: порт ${config.tcp.teltonika.port} (enabled: ${config.tcp.teltonika.enabled})")
      _ <- ZIO.logInfo(s"Wialon: порт ${config.tcp.wialon.port} (enabled: ${config.tcp.wialon.enabled})")
      _ <- ZIO.logInfo(s"Redis: ${config.redis.host}:${config.redis.port}")
      _ <- ZIO.logInfo(s"Kafka: ${config.kafka.bootstrapServers}")
      
      // Создаем фабрику обработчиков
      handlerFactory = ConnectionHandler.factory(service, parser, runtime)
      
      // Запускаем серверы параллельно
      _ <- ZIO.collectAllParDiscard(
        List(
          startServerIfEnabled("Teltonika", config.tcp.teltonika, server, handlerFactory),
          startServerIfEnabled("Wialon", config.tcp.wialon, server, handlerFactory),
          startServerIfEnabled("NavTelecom", config.tcp.navtelecom, server, handlerFactory)
        )
      )
      
      _ <- ZIO.logInfo("=== Все серверы запущены ===")
      _ <- ZIO.logInfo("Нажмите Ctrl+C для остановки")
      
      // Ожидаем бесконечно (graceful shutdown при SIGTERM)
      _ <- ZIO.never
    yield ()
  
  /**
   * Запускает сервер если он включен в конфигурации
   */
  private def startServerIfEnabled(
    name: String,
    config: TcpProtocolConfig,
    server: TcpServer,
    handlerFactory: () => ConnectionHandler
  ): Task[Unit] =
    if config.enabled then
      server.start(config.port, handlerFactory)
        .tap(_ => ZIO.logInfo(s"✓ $name сервер запущен на порту ${config.port}"))
        .unit
    else
      ZIO.logInfo(s"✗ $name сервер отключен")
  
  /**
   * Композиция всех слоёв приложения
   */
  val appLayer: ZLayer[Any, Throwable, AppConfig & TcpServer & GpsProcessingService & ProtocolParser] =
    // Базовые слои
    val configLayer = AppConfig.live
    
    // Слой конфигурации для подкомпонентов
    val tcpConfigLayer = configLayer.project(_.tcp)
    val redisConfigLayer = configLayer.project(_.redis)
    val kafkaConfigLayer = configLayer.project(_.kafka)
    val deadReckoningConfigLayer = configLayer.project(_.filters.deadReckoning)
    val stationaryConfigLayer = configLayer.project(_.filters.stationary)
    
    // Инфраструктурные слои
    val tcpServerLayer = tcpConfigLayer >>> TcpServer.live
    val redisLayer = redisConfigLayer >>> RedisClient.live
    val kafkaLayer = kafkaConfigLayer >>> KafkaProducer.live
    
    // Слои фильтров
    val deadReckoningLayer = deadReckoningConfigLayer >>> DeadReckoningFilter.live
    val stationaryLayer = stationaryConfigLayer >>> StationaryFilter.live
    
    // Слой парсера
    val parserLayer = TeltonikaParser.live
    
    // Слой сервиса обработки GPS
    val processingServiceLayer = 
      (parserLayer ++ redisLayer ++ kafkaLayer ++ deadReckoningLayer ++ stationaryLayer) >>> 
        GpsProcessingService.live
    
    // Финальная композиция
    configLayer ++ tcpServerLayer ++ processingServiceLayer ++ parserLayer
  
  override def run: ZIO[Any, Any, Any] =
    program
      .provideSome[Any](appLayer)
      .tapError(e => ZIO.logError(s"Критическая ошибка: ${e.getMessage}"))
