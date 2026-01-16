package com.wayrecall.tracker.network

import zio.*
import zio.json.*
import com.wayrecall.tracker.domain.*
import com.wayrecall.tracker.storage.{RedisClient, KafkaProducer}
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Сервис для управления командами на GPS трекеры
 * 
 * Workflow:
 * 1. Backend API → Redis PUBLISH commands:{imei}
 * 2. CommandService (слушает Redis) → находит соединение → отправляет на трекер
 * 3. Трекер отвечает → CommandService → Redis PUBLISH command-results:{imei}
 */
trait CommandService:
  /**
   * Отправить команду на трекер
   */
  def sendCommand(command: Command): Task[CommandResult]
  
  /**
   * Запустить слушатель команд из Redis
   */
  def startCommandListener: Task[Unit]
  
  /**
   * Обработать ответ от трекера
   */
  def handleCommandResponse(imei: String, response: Array[Byte]): Task[Unit]

object CommandService:
  
  // Accessor methods
  def sendCommand(command: Command): RIO[CommandService, CommandResult] =
    ZIO.serviceWithZIO(_.sendCommand(command))
  
  def startCommandListener: RIO[CommandService, Unit] =
    ZIO.serviceWithZIO(_.startCommandListener)
  
  def handleCommandResponse(imei: String, response: Array[Byte]): RIO[CommandService, Unit] =
    ZIO.serviceWithZIO(_.handleCommandResponse(imei, response))
  
  /**
   * Live реализация
   */
  final case class Live(
      redisClient: RedisClient,
      connectionRegistry: ConnectionRegistry,
      pendingCommands: ConcurrentHashMap[String, Promise[Throwable, CommandResult]],
      commandTimeout: Duration
  ) extends CommandService:
    
    private val COMMANDS_CHANNEL = "commands:*"
    private val RESULTS_CHANNEL_PREFIX = "command-results:"
    
    override def sendCommand(command: Command): Task[CommandResult] =
      for
        // 1. Найти соединение для этого IMEI
        entryOpt <- connectionRegistry.findByImei(command.imei)
        entry <- ZIO.fromOption(entryOpt)
                   .mapError(_ => new RuntimeException(s"Tracker ${command.imei} not connected"))
        
        // 2. Кодировать команду
        buffer <- entry.parser.encodeCommand(command)
        
        // 3. Создать Promise для ожидания ответа
        promise <- Promise.make[Throwable, CommandResult]
        _ <- ZIO.succeed(pendingCommands.put(command.commandId, promise))
        
        // 4. Отправить на трекер
        _ <- ZIO.attempt(entry.ctx.writeAndFlush(buffer))
        
        // 5. Опубликовать статус "Sent"
        sentResult = CommandResult(
          commandId = command.commandId,
          imei = command.imei,
          status = CommandStatus.Sent,
          message = None,
          timestamp = Instant.now()
        )
        _ <- redisClient.publish(
          s"$RESULTS_CHANNEL_PREFIX${command.imei}",
          sentResult.toJson
        )
        
        _ <- ZIO.logInfo(s"Command ${command.commandId} sent to ${command.imei}")
        
        // 6. Ждать ответа с таймаутом
        result <- promise.await
          .timeout(commandTimeout)
          .flatMap {
            case Some(r) => ZIO.succeed(r)
            case None =>
              val timeoutResult = CommandResult(
                commandId = command.commandId,
                imei = command.imei,
                status = CommandStatus.Timeout,
                message = Some(s"Tracker did not respond within ${commandTimeout.toSeconds}s"),
                timestamp = Instant.now()
              )
              // Публикуем результат таймаута
              redisClient.publish(
                s"$RESULTS_CHANNEL_PREFIX${command.imei}",
                timeoutResult.toJson
              ).as(timeoutResult)
          }
          .ensuring(ZIO.succeed(pendingCommands.remove(command.commandId)))
        
      yield result
    
    override def startCommandListener: Task[Unit] =
      redisClient.psubscribe(COMMANDS_CHANNEL) { (channel, message) =>
        val imei = channel.stripPrefix("commands:")
        
        (for
          command <- ZIO.fromEither(message.fromJson[Command])
                       .mapError(e => new RuntimeException(s"Failed to parse command: $e"))
          
          _ <- ZIO.logInfo(s"Received command ${command.commandId} for $imei from Redis")
          
          // Проверяем, подключен ли трекер
          connected <- connectionRegistry.isConnected(command.imei)
          
          _ <- (if connected then
                  sendCommand(command)
                    .catchAll(e => ZIO.logError(s"Failed to send command: ${e.getMessage}"))
                else
                  ZIO.suspend {
                    // Трекер не подключен - публикуем ошибку
                    val failedResult = CommandResult(
                      commandId = command.commandId,
                      imei = command.imei,
                      status = CommandStatus.Failed,
                      message = Some(s"Tracker ${command.imei} is not connected"),
                      timestamp = Instant.now()
                    )
                    redisClient.publish(
                      s"$RESULTS_CHANNEL_PREFIX${command.imei}",
                      failedResult.toJson
                    ) *> ZIO.logWarning(s"Tracker ${command.imei} not connected, command ${command.commandId} failed")
                  }
               )
        yield ()).catchAll(e => ZIO.logError(s"Error processing command: ${e.getMessage}"))
      }
    
    override def handleCommandResponse(imei: String, response: Array[Byte]): Task[Unit] =
      ZIO.attempt {
        // Простая логика: ищем pending команду для этого IMEI
        // В реальности нужно парсить commandId из response
        
        // Находим первую pending команду для этого IMEI
        val pendingForImei = pendingCommands.entrySet().iterator()
        var found = false
        
        while pendingForImei.hasNext && !found do
          val entry = pendingForImei.next()
          // Если команда для этого IMEI (нужна дополнительная логика маппинга)
          val promise = entry.getValue
          val commandId = entry.getKey
          
          val result = CommandResult(
            commandId = commandId,
            imei = imei,
            status = CommandStatus.Acked,
            message = Some(new String(response, "UTF-8").take(100)),
            timestamp = Instant.now()
          )
          
          // Unsafe run для callback из Netty
          Unsafe.unsafe { implicit unsafe =>
            Runtime.default.unsafe.run(
              promise.succeed(result) *>
              redisClient.publish(s"$RESULTS_CHANNEL_PREFIX$imei", result.toJson)
            )
          }
          
          found = true
      }
  
  /**
   * ZIO Layer
   */
  val live: ZLayer[RedisClient & ConnectionRegistry, Nothing, CommandService] =
    ZLayer {
      for
        redis <- ZIO.service[RedisClient]
        registry <- ZIO.service[ConnectionRegistry]
        pendingCommands = new ConcurrentHashMap[String, Promise[Throwable, CommandResult]]()
      yield Live(redis, registry, pendingCommands, 30.seconds)
    }
