package com.wayrecall.tracker.network

import zio.*
import io.netty.channel.ChannelHandlerContext
import com.wayrecall.tracker.protocol.ProtocolParser
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/**
 * Реестр активных TCP соединений
 * 
 * Позволяет:
 * - Находить соединение по IMEI для отправки команд
 * - Отслеживать количество активных подключений
 * - Управлять жизненным циклом соединений
 */
trait ConnectionRegistry:
  /**
   * Регистрирует новое соединение
   */
  def register(imei: String, ctx: ChannelHandlerContext, parser: ProtocolParser): UIO[Unit]
  
  /**
   * Удаляет соединение
   */
  def unregister(imei: String): UIO[Unit]
  
  /**
   * Находит контекст соединения по IMEI
   */
  def findByImei(imei: String): UIO[Option[ConnectionEntry]]
  
  /**
   * Возвращает все активные соединения
   */
  def getAllConnections: UIO[List[ConnectionEntry]]
  
  /**
   * Возвращает количество активных соединений
   */
  def connectionCount: UIO[Int]
  
  /**
   * Проверяет, подключен ли трекер
   */
  def isConnected(imei: String): UIO[Boolean]

/**
 * Информация о соединении
 */
final case class ConnectionEntry(
    imei: String,
    ctx: ChannelHandlerContext,
    parser: ProtocolParser,
    connectedAt: Long
)

object ConnectionRegistry:
  
  // Accessor methods
  def register(imei: String, ctx: ChannelHandlerContext, parser: ProtocolParser): URIO[ConnectionRegistry, Unit] =
    ZIO.serviceWithZIO(_.register(imei, ctx, parser))
  
  def unregister(imei: String): URIO[ConnectionRegistry, Unit] =
    ZIO.serviceWithZIO(_.unregister(imei))
  
  def findByImei(imei: String): URIO[ConnectionRegistry, Option[ConnectionEntry]] =
    ZIO.serviceWithZIO(_.findByImei(imei))
  
  def getAllConnections: URIO[ConnectionRegistry, List[ConnectionEntry]] =
    ZIO.serviceWithZIO(_.getAllConnections)
  
  def connectionCount: URIO[ConnectionRegistry, Int] =
    ZIO.serviceWithZIO(_.connectionCount)
  
  def isConnected(imei: String): URIO[ConnectionRegistry, Boolean] =
    ZIO.serviceWithZIO(_.isConnected(imei))
  
  /**
   * Live реализация с ConcurrentHashMap
   */
  final case class Live(
      connections: ConcurrentHashMap[String, ConnectionEntry]
  ) extends ConnectionRegistry:
    
    override def register(imei: String, ctx: ChannelHandlerContext, parser: ProtocolParser): UIO[Unit] =
      ZIO.succeed {
        val entry = ConnectionEntry(
          imei = imei,
          ctx = ctx,
          parser = parser,
          connectedAt = java.lang.System.currentTimeMillis()
        )
        connections.put(imei, entry)
      } *> ZIO.logInfo(s"Registered connection for IMEI: $imei, total: ${connections.size()}")
    
    override def unregister(imei: String): UIO[Unit] =
      ZIO.succeed {
        connections.remove(imei)
      } *> ZIO.logInfo(s"Unregistered connection for IMEI: $imei, total: ${connections.size()}")
    
    override def findByImei(imei: String): UIO[Option[ConnectionEntry]] =
      ZIO.succeed(Option(connections.get(imei)))
    
    override def getAllConnections: UIO[List[ConnectionEntry]] =
      ZIO.succeed(connections.values().asScala.toList)
    
    override def connectionCount: UIO[Int] =
      ZIO.succeed(connections.size())
    
    override def isConnected(imei: String): UIO[Boolean] =
      ZIO.succeed(connections.containsKey(imei))
  
  /**
   * ZIO Layer
   */
  val live: ULayer[ConnectionRegistry] =
    ZLayer.succeed(Live(new ConcurrentHashMap[String, ConnectionEntry]()))
