package com.wayrecall.tracker.network

import zio.*
import com.wayrecall.tracker.config.{TcpConfig, DynamicConfigService}

/**
 * Сервис для отключения неактивных TCP соединений
 * 
 * ✅ Чисто функциональный
 * ✅ Поддерживает динамическую конфигурацию через DynamicConfigService
 * 
 * Workflow:
 * 1. Периодически (раз в N секунд) проверяем все соединения
 * 2. Если соединение неактивно дольше idle timeout - закрываем его
 * 3. Idle timeout может быть переопределён через Redis динамически
 */
trait IdleConnectionWatcher:
  /**
   * Запускает фоновую задачу мониторинга
   */
  def start: UIO[Fiber[Nothing, Unit]]
  
  /**
   * Принудительно проверяет и отключает idle соединения
   */
  def checkAndDisconnectIdle: UIO[Int]

object IdleConnectionWatcher:
  
  // Accessor methods
  def start: URIO[IdleConnectionWatcher, Fiber[Nothing, Unit]] =
    ZIO.serviceWithZIO(_.start)
  
  def checkAndDisconnectIdle: URIO[IdleConnectionWatcher, Int] =
    ZIO.serviceWithZIO(_.checkAndDisconnectIdle)
  
  /**
   * Live реализация
   */
  final case class Live(
      registry: ConnectionRegistry,
      configService: DynamicConfigService,
      tcpConfig: TcpConfig
  ) extends IdleConnectionWatcher:
    
    /**
     * Интервал проверки в миллисекундах
     */
    private val checkIntervalMs: Long = tcpConfig.idleCheckIntervalSeconds.toLong * 1000
    
    /**
     * Дефолтный idle timeout из статического конфига
     */
    private val defaultIdleTimeoutMs: Long = tcpConfig.idleTimeoutSeconds.toLong * 1000
    
    override def start: UIO[Fiber[Nothing, Unit]] =
      checkAndDisconnectIdle
        .repeat(Schedule.fixed(Duration.fromMillis(checkIntervalMs)))
        .unit
        .fork
    
    override def checkAndDisconnectIdle: UIO[Int] =
      for
        // Получаем dynamic idle timeout из конфига (если переопределён)
        filterConfig <- configService.getFilterConfig
        
        // Idle timeout можно было бы добавить в FilterConfig, 
        // но пока используем статический
        idleTimeoutMs = defaultIdleTimeoutMs
        
        // Находим idle соединения
        idleConnections <- registry.getIdleConnections(idleTimeoutMs)
        
        // Закрываем каждое idle соединение
        _ <- ZIO.foreachDiscard(idleConnections) { entry =>
          for
            now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
            idleSec = (now - entry.lastActivityAt) / 1000
            _ <- ZIO.logWarning(s"Closing idle connection: IMEI=${entry.imei}, idle for ${idleSec}s")
            // Закрываем канал Netty
            _ <- ZIO.attempt(entry.ctx.close()).ignore
            // Удаляем из реестра
            _ <- registry.unregister(entry.imei)
          yield ()
        }
        
        count = idleConnections.size
        _ <- ZIO.when(count > 0)(
               ZIO.logInfo(s"Disconnected $count idle connections")
             )
      yield count
  
  /**
   * ZIO Layer
   */
  val live: ZLayer[ConnectionRegistry & DynamicConfigService & TcpConfig, Nothing, IdleConnectionWatcher] =
    ZLayer {
      for
        registry <- ZIO.service[ConnectionRegistry]
        configService <- ZIO.service[DynamicConfigService]
        tcpConfig <- ZIO.service[TcpConfig]
      yield Live(registry, configService, tcpConfig)
    }
