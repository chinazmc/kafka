/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.network

import java.io.IOException
import java.net._
import java.nio.channels._
import java.nio.channels.{Selector => NSelector}
import java.util
import java.util.Optional
import java.util.concurrent._
import java.util.concurrent.atomic._
import java.util.function.Supplier

import kafka.cluster.{BrokerEndPoint, EndPoint}
import kafka.metrics.KafkaMetricsGroup
import kafka.network.RequestChannel.{CloseConnectionResponse, EndThrottlingResponse, NoOpResponse, SendResponse, StartThrottlingResponse}
import kafka.network.Processor._
import kafka.network.SocketServer._
import kafka.security.CredentialProvider
import kafka.server.{BrokerReconfigurable, KafkaConfig}
import kafka.utils._
import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.common.{Endpoint, KafkaException, Reconfigurable}
import org.apache.kafka.common.memory.{MemoryPool, SimpleMemoryPool}
import org.apache.kafka.common.metrics._
import org.apache.kafka.common.metrics.stats.{CumulativeSum, Meter}
import org.apache.kafka.common.network.ClientInformation
import org.apache.kafka.common.network.KafkaChannel.ChannelMuteEvent
import org.apache.kafka.common.network.{ChannelBuilder, ChannelBuilders, KafkaChannel, ListenerName, ListenerReconfigurable, Selectable, Send, Selector => KSelector}
import org.apache.kafka.common.protocol.ApiKeys
import org.apache.kafka.common.requests.ApiVersionsRequest
import org.apache.kafka.common.requests.{RequestContext, RequestHeader}
import org.apache.kafka.common.security.auth.SecurityProtocol
import org.apache.kafka.common.utils.{KafkaThread, LogContext, Time}
import org.slf4j.event.Level

import scala.collection._
import JavaConverters._
import scala.collection.mutable.{ArrayBuffer, Buffer}
import scala.util.control.ControlThrowable

/**
 * Handles new connections, requests and responses to and from broker.
 * Kafka supports two types of request planes :
 *  - data-plane :
 *    - Handles requests from clients and other brokers in the cluster.
 *    - The threading model is
 *      1 Acceptor thread per listener, that handles new connections.
 *      It is possible to configure multiple data-planes by specifying multiple "," separated endpoints for "listeners" in KafkaConfig.
 *      Acceptor has N Processor threads that each have their own selector and read requests from sockets
 *      M Handler threads that handle requests and produce responses back to the processor threads for writing.
 *  - control-plane :
 *    - Handles requests from controller. This is optional and can be configured by specifying "control.plane.listener.name".
 *      If not configured, the controller requests are handled by the data-plane.
 *    - The threading model is
 *      1 Acceptor thread that handles new connections
 *      Acceptor has 1 Processor thread that has its own selector and read requests from the socket.
 *      1 Handler thread that handles requests and produce responses back to the processor thread for writing.
 */
class SocketServer(val config: KafkaConfig,
                   val metrics: Metrics,
                   val time: Time,
                   val credentialProvider: CredentialProvider)
  extends Logging with KafkaMetricsGroup with BrokerReconfigurable {
  // SocketServer实现BrokerReconfigurable trait表明SocketServer的一些参数配置是允许动态修改的
  // 即在Broker不停机的情况下修改它们
  // SocketServer的请求队列长度，由Broker端参数queued.max.requests值而定，默认值是500
  private val maxQueuedRequests = config.queuedMaxRequests

  private val logContext = new LogContext(s"[SocketServer brokerId=${config.brokerId}] ")
  this.logIdent = logContext.logPrefix

  private val memoryPoolSensor = metrics.sensor("MemoryPoolUtilization")
  private val memoryPoolDepletedPercentMetricName = metrics.metricName("MemoryPoolAvgDepletedPercent", MetricsGroup)
  private val memoryPoolDepletedTimeMetricName = metrics.metricName("MemoryPoolDepletedTimeTotal", MetricsGroup)
  memoryPoolSensor.add(new Meter(TimeUnit.MILLISECONDS, memoryPoolDepletedPercentMetricName, memoryPoolDepletedTimeMetricName))
  private val memoryPool = if (config.queuedMaxBytes > 0) new SimpleMemoryPool(config.queuedMaxBytes, config.socketRequestMaxBytes, false, memoryPoolSensor) else MemoryPool.NONE
  // data-plane
  private val dataPlaneProcessors = new ConcurrentHashMap[Int, Processor]() // 处理数据类请求的Processor线程池
  // 处理数据类请求的Acceptor线程池，每套监听器对应一个Acceptor线程
  private[network] val dataPlaneAcceptors = new ConcurrentHashMap[EndPoint, Acceptor]()
  // 处理数据类请求专属的RequestChannel对象
  val dataPlaneRequestChannel = new RequestChannel(maxQueuedRequests, DataPlaneMetricPrefix)
  // control-plane
  // 用于处理控制类请求的Processor线程
  // 注意：目前定义了专属的Processor线程而非线程池处理控制类请求
  private var controlPlaneProcessorOpt : Option[Processor] = None
  private[network] var controlPlaneAcceptorOpt : Option[Acceptor] = None
  // 处理数据类请求专属的RequestChannel对象
  val controlPlaneRequestChannelOpt: Option[RequestChannel] = config.controlPlaneListenerName.map(_ => new RequestChannel(20, ControlPlaneMetricPrefix))

  private var nextProcessorId = 0
  private var connectionQuotas: ConnectionQuotas = _
  private var stoppedProcessingRequests = false

  /**
   * Start the socket server. Acceptors for all the listeners are started. Processors
   * are started if `startupProcessors` is true. If not, processors are only started when
   * [[kafka.network.SocketServer#startDataPlaneProcessors()]] or
   * [[kafka.network.SocketServer#startControlPlaneProcessor()]] is invoked. Delayed starting of processors
   * is used to delay processing client connections until server is fully initialized, e.g.
   * to ensure that all credentials have been loaded before authentications are performed.
   * Acceptors are always started during `startup` so that the bound port is known when this
   * method completes even when ephemeral ports are used. Incoming connections on this server
   * are processed when processors start up and invoke [[org.apache.kafka.common.network.Selector#poll]].
   *
   * @param startupProcessors Flag indicating whether `Processor`s must be started.
   */
  def startup(startupProcessors: Boolean = true): Unit = {
    this.synchronized {
      // 初始化控制每个IP上的最大连接数的对象，底层是Map
      connectionQuotas = new ConnectionQuotas(config, time)
      createControlPlaneAcceptorAndProcessor(config.controlPlaneListener)
      // 创建接收器和处理器
      createDataPlaneAcceptorsAndProcessors(config.numNetworkThreads, config.dataPlaneListeners)
      // 判断是否需要启动处理器，在KafkaServer初始化代用SocketServer.startup方法时，startipProcessors传
      // 的是false，原因请看上面介绍KafkaServer的源码
      if (startupProcessors) {
        //启动处理器
        startControlPlaneProcessor(Map.empty)
        startDataPlaneProcessors(Map.empty)
      }
    }

    newGauge(s"${DataPlaneMetricPrefix}NetworkProcessorAvgIdlePercent", () => SocketServer.this.synchronized {
      val ioWaitRatioMetricNames = dataPlaneProcessors.values.asScala.iterator.map { p =>
        metrics.metricName("io-wait-ratio", MetricsGroup, p.metricTags)
      }
      ioWaitRatioMetricNames.map { metricName =>
        Option(metrics.metric(metricName)).fold(0.0)(m => Math.min(m.metricValue.asInstanceOf[Double], 1.0))
      }.sum / dataPlaneProcessors.size
    })
    newGauge(s"${ControlPlaneMetricPrefix}NetworkProcessorAvgIdlePercent", () => SocketServer.this.synchronized {
      val ioWaitRatioMetricName = controlPlaneProcessorOpt.map { p =>
        metrics.metricName("io-wait-ratio", "socket-server-metrics", p.metricTags)
      }
      ioWaitRatioMetricName.map { metricName =>
        Option(metrics.metric(metricName)).fold(0.0)(m => Math.min(m.metricValue.asInstanceOf[Double], 1.0))
      }.getOrElse(Double.NaN)
    })
    newGauge("MemoryPoolAvailable", () => memoryPool.availableMemory)
    newGauge("MemoryPoolUsed", () => memoryPool.size() - memoryPool.availableMemory)
    newGauge(s"${DataPlaneMetricPrefix}ExpiredConnectionsKilledCount", () => SocketServer.this.synchronized {
      val expiredConnectionsKilledCountMetricNames = dataPlaneProcessors.values.asScala.iterator.map { p =>
        metrics.metricName("expired-connections-killed-count", "socket-server-metrics", p.metricTags)
      }
      expiredConnectionsKilledCountMetricNames.map { metricName =>
        Option(metrics.metric(metricName)).fold(0.0)(m => m.metricValue.asInstanceOf[Double])
      }.sum
    })
    newGauge(s"${ControlPlaneMetricPrefix}ExpiredConnectionsKilledCount", () => SocketServer.this.synchronized {
      val expiredConnectionsKilledCountMetricNames = controlPlaneProcessorOpt.map { p =>
        metrics.metricName("expired-connections-killed-count", "socket-server-metrics", p.metricTags)
      }
      expiredConnectionsKilledCountMetricNames.map { metricName =>
        Option(metrics.metric(metricName)).fold(0.0)(m => m.metricValue.asInstanceOf[Double])
      }.getOrElse(0.0)
    })
    info(s"Started ${dataPlaneAcceptors.size} acceptor threads for data-plane")
    if (controlPlaneAcceptorOpt.isDefined)
      info("Started control-plane acceptor thread")
  }

  /**
   * Starts processors of all the data-plane acceptors of this server if they have not already been started.
   * This method is used for delayed starting of data-plane processors if [[kafka.network.SocketServer#startup]]
   * was invoked with `startupProcessors=false`.
   *
   * Before starting processors for each endpoint, we ensure that authorizer has all the metadata
   * to authorize requests on that endpoint by waiting on the provided future. We start inter-broker listener
   * before other listeners. This allows authorization metadata for other listeners to be stored in Kafka topics
   * in this cluster.
   */
  def startDataPlaneProcessors(authorizerFutures: Map[Endpoint, CompletableFuture[Void]] = Map.empty): Unit = synchronized {
    val interBrokerListener = dataPlaneAcceptors.asScala.keySet
      .find(_.listenerName == config.interBrokerListenerName)
      .getOrElse(throw new IllegalStateException(s"Inter-broker listener ${config.interBrokerListenerName} not found, endpoints=${dataPlaneAcceptors.keySet}"))
    val orderedAcceptors = List(dataPlaneAcceptors.get(interBrokerListener)) ++
      dataPlaneAcceptors.asScala.filterKeys(_ != interBrokerListener).values
    orderedAcceptors.foreach { acceptor =>
      val endpoint = acceptor.endPoint
      debug(s"Wait for authorizer to complete start up on listener ${endpoint.listenerName}")
      waitForAuthorizerFuture(acceptor, authorizerFutures)
      debug(s"Start processors on listener ${endpoint.listenerName}")
      acceptor.startProcessors(DataPlaneThreadPrefix)
    }
    info(s"Started data-plane processors for ${dataPlaneAcceptors.size} acceptors")
  }

  /**
   * Start the processor of control-plane acceptor of this server if it has not already been started.
   * This method is used for delayed starting of control-plane processor if [[kafka.network.SocketServer#startup]]
   * was invoked with `startupProcessors=false`.
   */
  def startControlPlaneProcessor(authorizerFutures: Map[Endpoint, CompletableFuture[Void]] = Map.empty): Unit = synchronized {
    controlPlaneAcceptorOpt.foreach { controlPlaneAcceptor =>
      waitForAuthorizerFuture(controlPlaneAcceptor, authorizerFutures)
      controlPlaneAcceptor.startProcessors(ControlPlaneThreadPrefix)
      info(s"Started control-plane processor for the control-plane acceptor")
    }
  }

  private def endpoints = config.listeners.map(l => l.listenerName -> l).toMap
  // 创建接收器和处理器，同时并启动接收器
  private def createDataPlaneAcceptorsAndProcessors(dataProcessorsPerListener: Int,
                                                    endpoints: Seq[EndPoint]): Unit = synchronized {
    // 遍历broker节点，为每个broker节点都创建接收器
    // 遍历监听器集合
    endpoints.foreach { endpoint =>
      // 将监听器纳入到连接配额管理之下
      connectionQuotas.addListener(config, endpoint.listenerName)
      // 创建接收器
      // 为监听器创建对应的Acceptor线程
      val dataPlaneAcceptor = createAcceptor(endpoint, DataPlaneMetricPrefix)
      // 创建处理器，并把处理器添加到RequestChannel和Acceptor的处理器列表中
      // 为监听器创建多个Processor线程。具体数目由num.network.threads决定
      addDataPlaneProcessors(dataPlaneAcceptor, endpoint, dataProcessorsPerListener)
      // 启动接收器线程
      KafkaThread.nonDaemon(s"data-plane-kafka-socket-acceptor-${endpoint.listenerName}-${endpoint.securityProtocol}-${endpoint.port}", dataPlaneAcceptor).start()
      // 等待启动完成
      dataPlaneAcceptor.awaitStartup()
      // 把启动好的处理器添加到map中，并和broker做好了映射
      // 将<监听器，Acceptor线程>对保存起来统一管理
      dataPlaneAcceptors.put(endpoint, dataPlaneAcceptor)
      info(s"Created data-plane acceptor and processors for endpoint : $endpoint")
    }
  }

  private def createControlPlaneAcceptorAndProcessor(endpointOpt: Option[EndPoint]): Unit = synchronized {
    // 如果为Control plane配置了监听器
    endpointOpt.foreach { endpoint =>
      // 将监听器纳入到连接配额管理之下
      connectionQuotas.addListener(config, endpoint.listenerName)
      // 为监听器创建对应的Acceptor线程
      val controlPlaneAcceptor = createAcceptor(endpoint, ControlPlaneMetricPrefix)
      // 为监听器创建对应的Processor线程
      val controlPlaneProcessor = newProcessor(nextProcessorId, controlPlaneRequestChannelOpt.get, connectionQuotas, endpoint.listenerName, endpoint.securityProtocol, memoryPool)
      controlPlaneAcceptorOpt = Some(controlPlaneAcceptor)
      controlPlaneProcessorOpt = Some(controlPlaneProcessor)
      val listenerProcessors = new ArrayBuffer[Processor]()
      listenerProcessors += controlPlaneProcessor
      // 将Processor线程添加到控制类请求专属RequestChannel中
      // 即添加到RequestChannel实例保存的Processor线程池中
      controlPlaneRequestChannelOpt.foreach(_.addProcessor(controlPlaneProcessor))
      nextProcessorId += 1
      // 把Processor对象也添加到Acceptor线程管理的Processor线程池中
      controlPlaneAcceptor.addProcessors(listenerProcessors, ControlPlaneThreadPrefix)
      KafkaThread.nonDaemon(s"${ControlPlaneThreadPrefix}-kafka-socket-acceptor-${endpoint.listenerName}-${endpoint.securityProtocol}-${endpoint.port}", controlPlaneAcceptor).start()
      controlPlaneAcceptor.awaitStartup()
      info(s"Created control-plane acceptor and processor for endpoint : $endpoint")
    }
  }

  private def createAcceptor(endPoint: EndPoint, metricPrefix: String) : Acceptor = synchronized {
    val sendBufferSize = config.socketSendBufferBytes
    val recvBufferSize = config.socketReceiveBufferBytes
    val brokerId = config.brokerId
    new Acceptor(endPoint, sendBufferSize, recvBufferSize, brokerId, connectionQuotas, metricPrefix)
  }

  private def addDataPlaneProcessors(acceptor: Acceptor, endpoint: EndPoint, newProcessorsPerListener: Int): Unit = synchronized {
    val listenerName = endpoint.listenerName
    val securityProtocol = endpoint.securityProtocol
    val listenerProcessors = new ArrayBuffer[Processor]()
    for (_ <- 0 until newProcessorsPerListener) {
      val processor = newProcessor(nextProcessorId, dataPlaneRequestChannel, connectionQuotas, listenerName, securityProtocol, memoryPool)
      listenerProcessors += processor
      dataPlaneRequestChannel.addProcessor(processor)
      nextProcessorId += 1
    }
    listenerProcessors.foreach(p => dataPlaneProcessors.put(p.id, p))
    acceptor.addProcessors(listenerProcessors, DataPlaneThreadPrefix)
  }

  /**
   * Stop processing requests and new connections.
   */
  def stopProcessingRequests() = {
    info("Stopping socket server request processors")
    this.synchronized {
      dataPlaneAcceptors.asScala.values.foreach(_.shutdown())
      controlPlaneAcceptorOpt.foreach(_.shutdown())
      dataPlaneProcessors.asScala.values.foreach(_.shutdown())
      controlPlaneProcessorOpt.foreach(_.shutdown())
      dataPlaneRequestChannel.clear()
      controlPlaneRequestChannelOpt.foreach(_.clear())
      stoppedProcessingRequests = true
    }
    info("Stopped socket server request processors")
  }

  def resizeThreadPool(oldNumNetworkThreads: Int, newNumNetworkThreads: Int): Unit = synchronized {
    info(s"Resizing network thread pool size for each data-plane listener from $oldNumNetworkThreads to $newNumNetworkThreads")
    if (newNumNetworkThreads > oldNumNetworkThreads) {
      dataPlaneAcceptors.asScala.foreach { case (endpoint, acceptor) =>
        addDataPlaneProcessors(acceptor, endpoint, newNumNetworkThreads - oldNumNetworkThreads)
      }
    } else if (newNumNetworkThreads < oldNumNetworkThreads)
      dataPlaneAcceptors.asScala.values.foreach(_.removeProcessors(oldNumNetworkThreads - newNumNetworkThreads, dataPlaneRequestChannel))
  }

  /**
   * Shutdown the socket server. If still processing requests, shutdown
   * acceptors and processors first.
   */
  def shutdown() = {
    info("Shutting down socket server")
    this.synchronized {
      if (!stoppedProcessingRequests)
        stopProcessingRequests()
      dataPlaneRequestChannel.shutdown()
      controlPlaneRequestChannelOpt.foreach(_.shutdown())
    }
    info("Shutdown completed")
  }

  def boundPort(listenerName: ListenerName): Int = {
    try {
      val acceptor = dataPlaneAcceptors.get(endpoints(listenerName))
      if (acceptor != null) {
        acceptor.serverChannel.socket.getLocalPort
      } else {
        controlPlaneAcceptorOpt.map (_.serverChannel.socket().getLocalPort).getOrElse(throw new KafkaException("Could not find listenerName : " + listenerName + " in data-plane or control-plane"))
      }
    } catch {
      case e: Exception =>
        throw new KafkaException("Tried to check server's port before server was started or checked for port of non-existing protocol", e)
    }
  }

  def addListeners(listenersAdded: Seq[EndPoint]): Unit = synchronized {
    info(s"Adding data-plane listeners for endpoints $listenersAdded")
    createDataPlaneAcceptorsAndProcessors(config.numNetworkThreads, listenersAdded)
    startDataPlaneProcessors()
  }

  def removeListeners(listenersRemoved: Seq[EndPoint]): Unit = synchronized {
    info(s"Removing data-plane listeners for endpoints $listenersRemoved")
    listenersRemoved.foreach { endpoint =>
      connectionQuotas.removeListener(config, endpoint.listenerName)
      dataPlaneAcceptors.asScala.remove(endpoint).foreach(_.shutdown())
    }
  }

  override def reconfigurableConfigs: Set[String] = SocketServer.ReconfigurableConfigs

  override def validateReconfiguration(newConfig: KafkaConfig): Unit = {

  }

  override def reconfigure(oldConfig: KafkaConfig, newConfig: KafkaConfig): Unit = {
    val maxConnectionsPerIp = newConfig.maxConnectionsPerIp
    if (maxConnectionsPerIp != oldConfig.maxConnectionsPerIp) {
      info(s"Updating maxConnectionsPerIp: $maxConnectionsPerIp")
      connectionQuotas.updateMaxConnectionsPerIp(maxConnectionsPerIp)
    }
    val maxConnectionsPerIpOverrides = newConfig.maxConnectionsPerIpOverrides
    if (maxConnectionsPerIpOverrides != oldConfig.maxConnectionsPerIpOverrides) {
      info(s"Updating maxConnectionsPerIpOverrides: ${maxConnectionsPerIpOverrides.map { case (k, v) => s"$k=$v" }.mkString(",")}")
      connectionQuotas.updateMaxConnectionsPerIpOverride(maxConnectionsPerIpOverrides)
    }
    val maxConnections = newConfig.maxConnections
    if (maxConnections != oldConfig.maxConnections) {
      info(s"Updating broker-wide maxConnections: $maxConnections")
      connectionQuotas.updateBrokerMaxConnections(maxConnections)
    }
  }

  private def waitForAuthorizerFuture(acceptor: Acceptor,
                                      authorizerFutures: Map[Endpoint, CompletableFuture[Void]]): Unit = {
    //we can't rely on authorizerFutures.get() due to ephemeral ports. Get the future using listener name
    authorizerFutures.foreach { case (endpoint, future) =>
      if (endpoint.listenerName == Optional.of(acceptor.endPoint.listenerName.value))
        future.join()
    }
  }

  // `protected` for test usage
  protected[network] def newProcessor(id: Int, requestChannel: RequestChannel, connectionQuotas: ConnectionQuotas, listenerName: ListenerName,
                                      securityProtocol: SecurityProtocol, memoryPool: MemoryPool): Processor = {
    new Processor(id,
      time,
      config.socketRequestMaxBytes,
      requestChannel,
      connectionQuotas,
      config.connectionsMaxIdleMs,
      config.failedAuthenticationDelayMs,
      listenerName,
      securityProtocol,
      config,
      metrics,
      credentialProvider,
      memoryPool,
      logContext
    )
  }

  // For test usage
  private[network] def connectionCount(address: InetAddress): Int =
    Option(connectionQuotas).fold(0)(_.get(address))

  // For test usage
  private[network] def dataPlaneProcessor(index: Int): Processor = dataPlaneProcessors.get(index)

}

object SocketServer {
  val MetricsGroup = "socket-server-metrics"
  val DataPlaneThreadPrefix = "data-plane"
  val ControlPlaneThreadPrefix = "control-plane"
  val DataPlaneMetricPrefix = ""
  val ControlPlaneMetricPrefix = "ControlPlane"

  val ReconfigurableConfigs = Set(
    KafkaConfig.MaxConnectionsPerIpProp,
    KafkaConfig.MaxConnectionsPerIpOverridesProp,
    KafkaConfig.MaxConnectionsProp)

  val ListenerReconfigurableConfigs = Set(KafkaConfig.MaxConnectionsProp)
}

/**
 * A base class with some helper variables and methods
 */
private[kafka] abstract class AbstractServerThread(connectionQuotas: ConnectionQuotas) extends Runnable with Logging {

  private val startupLatch = new CountDownLatch(1)

  // `shutdown()` is invoked before `startupComplete` and `shutdownComplete` if an exception is thrown in the constructor
  // (e.g. if the address is already in use). We want `shutdown` to proceed in such cases, so we first assign an open
  // latch and then replace it in `startupComplete()`.
  @volatile private var shutdownLatch = new CountDownLatch(0)

  private val alive = new AtomicBoolean(true)

  def wakeup(): Unit

  /**
   * Initiates a graceful shutdown by signaling to stop and waiting for the shutdown to complete
   */
  def shutdown(): Unit = {
    if (alive.getAndSet(false))
      wakeup()
    shutdownLatch.await()
  }

  /**
   * Wait for the thread to completely start up
   */
  def awaitStartup(): Unit = startupLatch.await

  /**
   * Record that the thread startup is complete
   */
  protected def startupComplete(): Unit = {
    // Replace the open latch with a closed one
    shutdownLatch = new CountDownLatch(1)
    startupLatch.countDown()
  }

  /**
   * Record that the thread shutdown is complete
   */
  protected def shutdownComplete(): Unit = shutdownLatch.countDown()

  /**
   * Is the server still running?
   */
  protected def isRunning: Boolean = alive.get

  /**
   * Close `channel` and decrement the connection count.
   */
  def close(listenerName: ListenerName, channel: SocketChannel): Unit = {
    if (channel != null) {
      debug(s"Closing connection from ${channel.socket.getRemoteSocketAddress()}")
      connectionQuotas.dec(listenerName, channel.socket.getInetAddress)
      CoreUtils.swallow(channel.socket().close(), this, Level.ERROR)
      CoreUtils.swallow(channel.close(), this, Level.ERROR)
    }
  }
}

/**
 * Thread that accepts and configures new connections. There is one of these per endpoint.
 */
private[kafka] class Acceptor(val endPoint: EndPoint,
                              val sendBufferSize: Int,
                              val recvBufferSize: Int,
                              brokerId: Int,
                              connectionQuotas: ConnectionQuotas,
                              metricPrefix: String) extends AbstractServerThread(connectionQuotas) with KafkaMetricsGroup {
  // 创建底层的NIO Selector对象
  // Selector对象负责执行底层实际I/O操作，如监听连接创建请求、读写请求等
  private val nioSelector = NSelector.open()
  // Broker端创建对应的ServerSocketChannel实例
  // 后续把该Channel向上一步的Selector对象注册
  val serverChannel = openServerSocket(endPoint.host, endPoint.port)
  // 创建Processor线程池，实际上是Processor线程数组
  private val processors = new ArrayBuffer[Processor]()
  private val processorsStarted = new AtomicBoolean
  private val blockedPercentMeter = newMeter(s"${metricPrefix}AcceptorBlockedPercent",
    "blocked time", TimeUnit.NANOSECONDS, Map(ListenerMetricTag -> endPoint.listenerName.value))

  private[network] def addProcessors(newProcessors: Buffer[Processor], processorThreadPrefix: String): Unit = synchronized {
    processors ++= newProcessors// 添加一组新的Processor线程
    if (processorsStarted.get)// 如果Processor线程池已经启动
      startProcessors(newProcessors, processorThreadPrefix)// 启动新的Processor线程
  }

  private[network] def startProcessors(processorThreadPrefix: String): Unit = synchronized {
    if (!processorsStarted.getAndSet(true)) {// 如果Processor线程池未启动
      startProcessors(processors, processorThreadPrefix)// 启动给定的Processor线程
    }
  }

  private def startProcessors(processors: Seq[Processor], processorThreadPrefix: String): Unit = synchronized {
    processors.foreach { processor =>// 依次创建并启动Processor线程
      // 线程命名规范：processor线程前缀-kafka-network-thread-broker序号-监听器名称-安全协议-Processor序号
      // 假设为序号为0的Broker设置PLAINTEXT://localhost:9092作为连接信息，那么3个Processor线程名称分别为：
      // data-plane-kafka-network-thread-0-ListenerName(PLAINTEXT)-PLAINTEXT-0
      // data-plane-kafka-network-thread-0-ListenerName(PLAINTEXT)-PLAINTEXT-1
      // data-plane-kafka-network-thread-0-ListenerName(PLAINTEXT)-PLAINTEXT-2
      KafkaThread.nonDaemon(s"${processorThreadPrefix}-kafka-network-thread-$brokerId-${endPoint.listenerName}-${endPoint.securityProtocol}-${processor.id}",
        processor).start()
    }
  }

  private[network] def removeProcessors(removeCount: Int, requestChannel: RequestChannel): Unit = synchronized {
    // Shutdown `removeCount` processors. Remove them from the processor list first so that no more
    // connections are assigned. Shutdown the removed processors, closing the selector and its connections.
    // The processors are then removed from `requestChannel` and any pending responses to these processors are dropped.
    // 获取Processor线程池中最后removeCount个线程
    val toRemove = processors.takeRight(removeCount)
    // 移除最后removeCount个线程
    processors.remove(processors.size - removeCount, removeCount)
    // 关闭最后removeCount个线程
    toRemove.foreach(_.shutdown())
    // 在RequestChannel中移除这些Processor
    toRemove.foreach(processor => requestChannel.removeProcessor(processor.id))
  }

  override def shutdown(): Unit = {
    super.shutdown()
    synchronized {
      processors.foreach(_.shutdown())
    }
  }

  /**
   * Accept loop that checks for new connection attempts
   */
  def run(): Unit = {
    //注册OP_ACCEPT事件
    serverChannel.register(nioSelector, SelectionKey.OP_ACCEPT)
    // 等待Acceptor线程启动完成
    startupComplete()
    try {
      // 当前使用的Processor序号，从0开始，最大值是num.network.threads - 1
      var currentProcessorIndex = 0
      while (isRunning) {
        try {
          // 每500毫秒获取一次就绪I/O事件
          val ready = nioSelector.select(500)
          if (ready > 0) {// 如果有I/O事件准备就绪
            val keys = nioSelector.selectedKeys()
            val iter = keys.iterator()
            while (iter.hasNext && isRunning) {
              try {
                val key = iter.next
                iter.remove()
                // 测试SelectionKey的底层通道是否能够接受新Socket连接
                if (key.isAcceptable) {
                  // 调用accept方法创建Socket连接
                  //OP_ACCEPT事件发生，获取注册到选择键上的ServerSocketChannel，然后调用其accept方法，建立一个客户端和服务端的连接通道
                  accept(key).foreach { socketChannel =>

                    // Assign the channel to the next processor (using round-robin) to which the
                    // channel can be added without blocking. If newConnections queue is full on
                    // all processors, block until the last one is able to accept a connection.
                    // 重试的次数=该接收器对应的处理器的个数
                    var retriesLeft = synchronized(processors.length)
                    var processor: Processor = null
                    do {
                      // 重试次数减一
                      retriesLeft -= 1
                      // 指定由哪个Processor线程进行处理
                      processor = synchronized {
                        // 采用轮训的方式把客户端的连接通道分配给处理器即每个处理器都会有多个SocketChannel，对应多个客户端的连接
                        // adjust the index (if necessary) and retrieve the processor atomically for
                        // correct behaviour in case the number of processors is reduced dynamically
                        currentProcessorIndex = currentProcessorIndex % processors.length
                        // 根据索引获取对应的处理器
                        processors(currentProcessorIndex)
                      }
                      // 更新Processor线程序号
                      currentProcessorIndex += 1
                      // 如果新连接的队列满了，一直阻塞直到最后一个处理器可以能够接收连接
                      // 将新Socket连接加入到Processor线程待处理连接队列
                      // 等待Processor线程后续处理
                    } while (!assignNewConnection(socketChannel, processor, retriesLeft == 0))
                  }
                } else
                  throw new IllegalStateException("Unrecognized key state for acceptor thread.")
              } catch {
                case e: Throwable => error("Error while accepting connection", e)
              }
            }
          }
        }
        catch {
          // We catch all the throwables to prevent the acceptor thread from exiting on exceptions due
          // to a select operation on a specific channel or a bad request. We don't want
          // the broker to stop responding to requests from other clients in these scenarios.
          case e: ControlThrowable => throw e
          case e: Throwable => error("Error occurred", e)
        }
      }
    } finally {// 执行各种资源关闭逻辑
      debug("Closing server socket and selector.")
      CoreUtils.swallow(serverChannel.close(), this, Level.ERROR)
      CoreUtils.swallow(nioSelector.close(), this, Level.ERROR)
      shutdownComplete()
    }
  }

  /**
  * Create a server socket to listen for connections on.
  */
  private def openServerSocket(host: String, port: Int): ServerSocketChannel = {
    val socketAddress =
      if (host == null || host.trim.isEmpty)
        new InetSocketAddress(port)
      else
        new InetSocketAddress(host, port)
    val serverChannel = ServerSocketChannel.open()
    serverChannel.configureBlocking(false)
    if (recvBufferSize != Selectable.USE_DEFAULT_BUFFER_SIZE)
      serverChannel.socket().setReceiveBufferSize(recvBufferSize)

    try {
      serverChannel.socket.bind(socketAddress)
      info(s"Awaiting socket connections on ${socketAddress.getHostString}:${serverChannel.socket.getLocalPort}.")
    } catch {
      case e: SocketException =>
        throw new KafkaException(s"Socket server failed to bind to ${socketAddress.getHostString}:$port: ${e.getMessage}.", e)
    }
    serverChannel
  }

  /**
   * Accept a new connection
   */
  private def accept(key: SelectionKey): Option[SocketChannel] = {
    val serverSocketChannel = key.channel().asInstanceOf[ServerSocketChannel]
    val socketChannel = serverSocketChannel.accept()
    try {
      connectionQuotas.inc(endPoint.listenerName, socketChannel.socket.getInetAddress, blockedPercentMeter)
      socketChannel.configureBlocking(false)
      socketChannel.socket().setTcpNoDelay(true)
      socketChannel.socket().setKeepAlive(true)
      if (sendBufferSize != Selectable.USE_DEFAULT_BUFFER_SIZE)
        socketChannel.socket().setSendBufferSize(sendBufferSize)
      Some(socketChannel)
    } catch {
      case e: TooManyConnectionsException =>
        info(s"Rejected connection from ${e.ip}, address already has the configured maximum of ${e.count} connections.")
        close(endPoint.listenerName, socketChannel)
        None
    }
  }

  private def assignNewConnection(socketChannel: SocketChannel, processor: Processor, mayBlock: Boolean): Boolean = {
    if (processor.accept(socketChannel, mayBlock, blockedPercentMeter)) {
      debug(s"Accepted connection from ${socketChannel.socket.getRemoteSocketAddress} on" +
        s" ${socketChannel.socket.getLocalSocketAddress} and assigned it to processor ${processor.id}," +
        s" sendBufferSize [actual|requested]: [${socketChannel.socket.getSendBufferSize}|$sendBufferSize]" +
        s" recvBufferSize [actual|requested]: [${socketChannel.socket.getReceiveBufferSize}|$recvBufferSize]")
      true
    } else
      false
  }

  /**
   * Wakeup the thread for selection.
   */
  @Override
  def wakeup = nioSelector.wakeup()

}

private[kafka] object Processor {
  val IdlePercentMetricName = "IdlePercent"
  val NetworkProcessorMetricTag = "networkProcessor"
  val ListenerMetricTag = "listener"

  val ConnectionQueueSize = 20
}

/**
 * Thread that processes all requests from a single connection. There are N of these running in parallel
 * each of which has its own selector
 */
private[kafka] class Processor(val id: Int,
                               time: Time,
                               maxRequestSize: Int,
                               requestChannel: RequestChannel,
                               connectionQuotas: ConnectionQuotas,
                               connectionsMaxIdleMs: Long,
                               failedAuthenticationDelayMs: Int,
                               listenerName: ListenerName,
                               securityProtocol: SecurityProtocol,
                               config: KafkaConfig,
                               metrics: Metrics,
                               credentialProvider: CredentialProvider,
                               memoryPool: MemoryPool,
                               logContext: LogContext,
                               connectionQueueSize: Int = ConnectionQueueSize) extends AbstractServerThread(connectionQuotas) with KafkaMetricsGroup {

  private object ConnectionId {
    def fromString(s: String): Option[ConnectionId] = s.split("-") match {
      case Array(local, remote, index) => BrokerEndPoint.parseHostPort(local).flatMap { case (localHost, localPort) =>
        BrokerEndPoint.parseHostPort(remote).map { case (remoteHost, remotePort) =>
          ConnectionId(localHost, localPort, remoteHost, remotePort, Integer.parseInt(index))
        }
      }
      case _ => None
    }
  }

  private[network] case class ConnectionId(localHost: String, localPort: Int, remoteHost: String, remotePort: Int, index: Int) {
    override def toString: String = s"$localHost:$localPort-$remoteHost:$remotePort-$index"
  }

  private val newConnections = new ArrayBlockingQueue[SocketChannel](connectionQueueSize)
  private val inflightResponses = mutable.Map[String, RequestChannel.Response]()
  private val responseQueue = new LinkedBlockingDeque[RequestChannel.Response]()

  private[kafka] val metricTags = mutable.LinkedHashMap(
    ListenerMetricTag -> listenerName.value,
    NetworkProcessorMetricTag -> id.toString
  ).asJava

  newGauge(IdlePercentMetricName, () => {
    Option(metrics.metric(metrics.metricName("io-wait-ratio", MetricsGroup, metricTags))).fold(0.0)(m =>
      Math.min(m.metricValue.asInstanceOf[Double], 1.0))
    },
    // for compatibility, only add a networkProcessor tag to the Yammer Metrics alias (the equivalent Selector metric
    // also includes the listener name)
    Map(NetworkProcessorMetricTag -> id.toString)
  )

  val expiredConnectionsKilledCount = new CumulativeSum()
  private val expiredConnectionsKilledCountMetricName = metrics.metricName("expired-connections-killed-count", "socket-server-metrics", metricTags)
  metrics.addMetric(expiredConnectionsKilledCountMetricName, expiredConnectionsKilledCount)

  private val selector = createSelector(
    ChannelBuilders.serverChannelBuilder(listenerName,
      listenerName == config.interBrokerListenerName,
      securityProtocol,
      config,
      credentialProvider.credentialCache,
      credentialProvider.tokenCache,
      time,
      logContext))
  // Visible to override for testing
  protected[network] def createSelector(channelBuilder: ChannelBuilder): KSelector = {
    channelBuilder match {
      case reconfigurable: Reconfigurable => config.addReconfigurable(reconfigurable)
      case _ =>
    }
    new KSelector(
      maxRequestSize,
      connectionsMaxIdleMs,
      failedAuthenticationDelayMs,
      metrics,
      time,
      "socket-server",
      metricTags,
      false,
      true,
      channelBuilder,
      memoryPool,
      logContext)
  }

  // Connection ids have the format `localAddr:localPort-remoteAddr:remotePort-index`. The index is a
  // non-negative incrementing value that ensures that even if remotePort is reused after a connection is
  // closed, connection ids are not reused while requests from the closed connection are being processed.
  private var nextConnectionIndex = 0

  override def run(): Unit = {
    startupComplete()// 等待Processor线程启动完成
    try {
      while (isRunning) {
        try {
          // setup any new connections that have been queued up
          configureNewConnections()// 创建新连接// 把新的SocketChannel注册OP_READ事件到选择器上
          // register any new responses for writing
          processNewResponses()// 发送Response，并将Response放入到inflightResponses临时队列 // 注册OP_WRITE事件，用来给客户端写回响应
          poll()// 执行NIO poll，获取对应SocketChannel上准备就绪的I/O操作// 选择器轮训事件，用来读取请求、发送响应，默认超时时间为300ms
          processCompletedReceives()// 将接收到的Request放入Request队列
          processCompletedSends()// 为临时Response队列中的Response执行回调逻辑
          processDisconnected()// 处理因发送失败而导致的连接断开
          closeExcessConnections()// 关闭超过配额限制部分的连接
        } catch {
          // We catch all the throwables here to prevent the processor thread from exiting. We do this because
          // letting a processor exit might cause a bigger impact on the broker. This behavior might need to be
          // reviewed if we see an exception that needs the entire broker to stop. Usually the exceptions thrown would
          // be either associated with a specific socket channel or a bad request. These exceptions are caught and
          // processed by the individual methods above which close the failing channel and continue processing other
          // channels. So this catch block should only ever see ControlThrowables.
          case e: Throwable => processException("Processor got uncaught exception.", e)
        }
      }
    } finally {// 关闭底层资源
      debug(s"Closing selector - processor $id")
      CoreUtils.swallow(closeAll(), this, Level.ERROR)
      shutdownComplete()
    }
  }

  private[network] def processException(errorMessage: String, throwable: Throwable): Unit = {
    throwable match {
      case e: ControlThrowable => throw e
      case e => error(errorMessage, e)
    }
  }

  private def processChannelException(channelId: String, errorMessage: String, throwable: Throwable): Unit = {
    if (openOrClosingChannel(channelId).isDefined) {
      error(s"Closing socket for $channelId because of error", throwable)
      close(channelId)
    }
    processException(errorMessage, throwable)
  }

  private def processNewResponses(): Unit = {
    var currentResponse: RequestChannel.Response = null
    // 处理器还有响应要发送
    while ({currentResponse = dequeueResponse(); currentResponse != null}) {// Response队列中存在待处理Response
      // KafkaChannel唯一标识
      val channelId = currentResponse.request.context.connectionId// 获取连接通道ID
      try {
        currentResponse match {
          case response: NoOpResponse =>// 无需发送Response
            // There is no response to send to the client, we need to read more pipelined requests
            // that are sitting in the server's socket buffer
            updateRequestMetrics(response)
            trace(s"Socket server received empty response to send, registering for read: $response")
            // Try unmuting the channel. If there was no quota violation and the channel has not been throttled,
            // it will be unmuted immediately. If the channel has been throttled, it will be unmuted only if the
            // throttling delay has already passed by now.
            handleChannelMuteEvent(channelId, ChannelMuteEvent.RESPONSE_SENT)
            // 没有响应发送给客户端，需要读取更多请求，注册读事件
            tryUnmuteChannel(channelId)

          case response: SendResponse =>// 发送Response并将Response放入inflightResponses
            // 有响应要发送给客户端，注册写事件
            sendResponse(response, response.responseSend)
          case response: CloseConnectionResponse =>// 关闭对应的连接
            updateRequestMetrics(response)
            trace("Closing socket connection actively according to the response code.")
            close(channelId)
          case _: StartThrottlingResponse =>
            handleChannelMuteEvent(channelId, ChannelMuteEvent.THROTTLE_STARTED)
          case _: EndThrottlingResponse =>
            // Try unmuting the channel. The channel will be unmuted only if the response has already been sent out to
            // the client.
            handleChannelMuteEvent(channelId, ChannelMuteEvent.THROTTLE_ENDED)
            tryUnmuteChannel(channelId)
          case _ =>
            throw new IllegalArgumentException(s"Unknown response type: ${currentResponse.getClass}")
        }
      } catch {
        case e: Throwable =>
          processChannelException(channelId, s"Exception while processing response for $channelId", e)
      }
    }
  }

  // `protected` for test usage
  protected[network] def sendResponse(response: RequestChannel.Response, responseSend: Send): Unit = {
    val connectionId = response.request.context.connectionId
    trace(s"Socket server received response to send to $connectionId, registering for write and sending data: $response")
    // `channel` can be None if the connection was closed remotely or if selector closed it for being idle for too long
    if (channel(connectionId).isEmpty) {
      warn(s"Attempting to send response via channel for which there is no open connection, connection id $connectionId")
      response.request.updateRequestMetrics(0L, response)
    }
    // Invoke send for closingChannel as well so that the send is failed and the channel closed properly and
    // removed from the Selector after discarding any pending staged receives.
    // `openOrClosingChannel` can be None if the selector closed the connection because it was idle for too long
    if (openOrClosingChannel(connectionId).isDefined) {// 如果该连接处于可连接状态
      // 将响应通过Selector标记为Send，实际发送通过poll轮询完成
      selector.send(responseSend)// 发送Response
      // 添加到 inflightResponses 底层是可变的Map  key:connectionId value:response
      inflightResponses += (connectionId -> response)// 将Response加入到inflightResponses队列
    }
  }

  private def nowNanosSupplier = new Supplier[java.lang.Long] {
    override def get(): java.lang.Long = time.nanoseconds()
  }

  private def poll(): Unit = {
    val pollTimeout = if (newConnections.isEmpty) 300 else 0
    try selector.poll(pollTimeout)
    catch {
      case e @ (_: IllegalStateException | _: IOException) =>
        // The exception is not re-thrown and any completed sends/receives/connections/disconnections
        // from this poll will be processed.
        error(s"Processor $id poll failed", e)
    }
  }

  private def processCompletedReceives(): Unit = {
    // 遍历所有已接收的Request
    selector.completedReceives.asScala.foreach { receive =>
      try {
        // 保证对应连接通道已经建立
        openOrClosingChannel(receive.source) match {
          case Some(channel) =>
            // 解析ByteBuffer 到ReuestHeader
            val header = RequestHeader.parse(receive.payload)
            if (header.apiKey == ApiKeys.SASL_HANDSHAKE && channel.maybeBeginServerReauthentication(receive, nowNanosSupplier))
              trace(s"Begin re-authentication: $channel")
            else {
              val nowNanos = time.nanoseconds()
              // 如果认证会话已过期，则关闭连接
              if (channel.serverAuthenticationSessionExpired(nowNanos)) {
                // be sure to decrease connection count and drop any in-flight responses
                debug(s"Disconnecting expired channel: $channel : $header")
                close(channel.id)
                expiredConnectionsKilledCount.record(null, 1, 0)
              } else {
                val connectionId = receive.source
                // 组装RequestContext对象
                val context = new RequestContext(header, connectionId, channel.socketAddress,
                  channel.principal, listenerName, securityProtocol,
                  channel.channelMetadataRegistry.clientInformation)
                // 构建 RequestChannel.Request对象包含了处理器的编号，响应对象中可以获取到，这样就能保证请求和响应的处理都是在同一个处理器中完成
                val req = new RequestChannel.Request(processor = id, context = context,
                  startTimeNanos = nowNanos, memoryPool, receive.payload, requestChannel.metrics)
                // KIP-511: ApiVersionsRequest is intercepted here to catch the client software name
                // and version. It is done here to avoid wiring things up to the api layer.
                if (header.apiKey == ApiKeys.API_VERSIONS) {
                  val apiVersionsRequest = req.body[ApiVersionsRequest]
                  if (apiVersionsRequest.isValid) {
                    channel.channelMetadataRegistry.registerClientInformation(new ClientInformation(
                      apiVersionsRequest.data.clientSoftwareName,
                      apiVersionsRequest.data.clientSoftwareVersion))
                  }
                }
                // 核心代码：将Request添加到Request队列
                requestChannel.sendRequest(req)
                // 移除OP_READ读事件，已经接收到请求了，所以就不用再读了
                selector.mute(connectionId)
                handleChannelMuteEvent(connectionId, ChannelMuteEvent.REQUEST_RECEIVED)
              }
            }
          case None =>
            // This should never happen since completed receives are processed immediately after `poll()`
            throw new IllegalStateException(s"Channel ${receive.source} removed from selector before processing completed receive")
        }
      } catch {
        // note that even though we got an exception, we can assume that receive.source is valid.
        // Issues with constructing a valid receive object were handled earlier
        case e: Throwable =>
          processChannelException(receive.source, s"Exception while processing request from ${receive.source}", e)
      }
    }
    selector.clearCompletedReceives()
  }

  private def processCompletedSends(): Unit = {
    // 遍历底层SocketChannel已发送的Response
    selector.completedSends.asScala.foreach { send =>
      try {
        // 取出对应inflightResponses中的Response
        // 结束的写请求要从inflightResponses移出
        val response = inflightResponses.remove(send.destination).getOrElse {
          throw new IllegalStateException(s"Send for ${send.destination} completed, but not in `inflightResponses`")
        }
        updateRequestMetrics(response)// 更新一些统计指标
        // 执行回调逻辑
        // Invoke send completion callback
        response.onComplete.foreach(onComplete => onComplete(send))

        // Try unmuting the channel. If there was no quota violation and the channel has not been throttled,
        // it will be unmuted immediately. If the channel has been throttled, it will unmuted only if the throttling
        // delay has already passed by now.
        handleChannelMuteEvent(send.destination, ChannelMuteEvent.RESPONSE_SENT)
        // 添加 OP_READ 读事件，这样就可以继续读取客户端发来的请求
        tryUnmuteChannel(send.destination)
      } catch {
        case e: Throwable => processChannelException(send.destination,
          s"Exception while processing completed send to ${send.destination}", e)
      }
    }
    selector.clearCompletedSends()
  }

  private def updateRequestMetrics(response: RequestChannel.Response): Unit = {
    val request = response.request
    val networkThreadTimeNanos = openOrClosingChannel(request.context.connectionId).fold(0L)(_.getAndResetNetworkThreadTimeNanos())
    request.updateRequestMetrics(networkThreadTimeNanos, response)
  }

  private def processDisconnected(): Unit = {
    // 遍历底层SocketChannel的那些已经断开的连接
    selector.disconnected.keySet.asScala.foreach { connectionId =>
      try {
        // 获取断开连接的远端主机名信息
        val remoteHost = ConnectionId.fromString(connectionId).getOrElse {
          throw new IllegalStateException(s"connectionId has unexpected format: $connectionId")
        }.remoteHost
        // 将该连接从inflightResponses中移除，同时更新一些监控指标
        inflightResponses.remove(connectionId).foreach(updateRequestMetrics)
        // the channel has been closed by the selector but the quotas still need to be updated
        // 更新配额数据
        connectionQuotas.dec(listenerName, InetAddress.getByName(remoteHost))
      } catch {
        case e: Throwable => processException(s"Exception while processing disconnection of $connectionId", e)
      }
    }
  }

  private def closeExcessConnections(): Unit = {
    // 如果配额超限了
    if (connectionQuotas.maxConnectionsExceeded(listenerName)) {
      // 找出优先关闭的那个连接
      val channel = selector.lowestPriorityChannel()
      if (channel != null)
        close(channel.id)// 关闭该连接
    }
  }

  /**
   * Close the connection identified by `connectionId` and decrement the connection count.
   * The channel will be immediately removed from the selector's `channels` or `closingChannels`
   * and no further disconnect notifications will be sent for this channel by the selector.
   * If responses are pending for the channel, they are dropped and metrics is updated.
   * If the channel has already been removed from selector, no action is taken.
   */
  private def close(connectionId: String): Unit = {
    openOrClosingChannel(connectionId).foreach { channel =>
      debug(s"Closing selector connection $connectionId")
      val address = channel.socketAddress
      if (address != null)
        connectionQuotas.dec(listenerName, address)
      selector.close(connectionId)

      inflightResponses.remove(connectionId).foreach(response => updateRequestMetrics(response))
    }
  }

  /**
   * Queue up a new connection for reading
   */
  def accept(socketChannel: SocketChannel,
             mayBlock: Boolean,
             acceptorIdlePercentMeter: com.yammer.metrics.core.Meter): Boolean = {
    val accepted = {
      if (newConnections.offer(socketChannel))
        true
      else if (mayBlock) {
        val startNs = time.nanoseconds
        newConnections.put(socketChannel)
        acceptorIdlePercentMeter.mark(time.nanoseconds() - startNs)
        true
      } else
        false
    }
    if (accepted)
      wakeup()
    accepted
  }

  /**
   * Register any new connections that have been queued up. The number of connections processed
   * in each iteration is limited to ensure that traffic and connection close notifications of
   * existing channels are handled promptly.
   */
  private def configureNewConnections(): Unit = {
    var connectionsProcessed = 0 // 当前已配置的连接数计数器
    while (connectionsProcessed < connectionQueueSize && !newConnections.isEmpty) {
      val channel = newConnections.poll()// 从连接队列中取出SocketChannel
      try {
        debug(s"Processor $id listening to new connection from ${channel.socket.getRemoteSocketAddress}")
        // 用给定Selector注册该Channel
        // 底层就是调用Java NIO的SocketChannel.register(selector, SelectionKey.OP_READ)
        selector.register(connectionId(channel.socket), channel)
        connectionsProcessed += 1// 更新计数器
      } catch {
        // We explicitly catch all exceptions and close the socket to avoid a socket leak.
        case e: Throwable =>
          val remoteAddress = channel.socket.getRemoteSocketAddress
          // need to close the channel here to avoid a socket leak.
          close(listenerName, channel)
          processException(s"Processor $id closed connection from $remoteAddress", e)
      }
    }
  }

  /**
   * Close the selector and all open connections
   */
  private def closeAll(): Unit = {
    selector.channels.asScala.foreach { channel =>
      close(channel.id)
    }
    selector.close()
    removeMetric(IdlePercentMetricName, Map(NetworkProcessorMetricTag -> id.toString))
  }

  // 'protected` to allow override for testing
  protected[network] def connectionId(socket: Socket): String = {
    val localHost = socket.getLocalAddress.getHostAddress
    val localPort = socket.getLocalPort
    val remoteHost = socket.getInetAddress.getHostAddress
    val remotePort = socket.getPort
    val connId = ConnectionId(localHost, localPort, remoteHost, remotePort, nextConnectionIndex).toString
    nextConnectionIndex = if (nextConnectionIndex == Int.MaxValue) 0 else nextConnectionIndex + 1
    connId
  }

  private[network] def enqueueResponse(response: RequestChannel.Response): Unit = {
    responseQueue.put(response)
    wakeup()
  }

  private def dequeueResponse(): RequestChannel.Response = {
    val response = responseQueue.poll()
    if (response != null)
      response.request.responseDequeueTimeNanos = Time.SYSTEM.nanoseconds
    response
  }

  private[network] def responseQueueSize = responseQueue.size

  // Only for testing
  private[network] def inflightResponseCount: Int = inflightResponses.size

  // Visible for testing
  // Only methods that are safe to call on a disconnected channel should be invoked on 'openOrClosingChannel'.
  private[network] def openOrClosingChannel(connectionId: String): Option[KafkaChannel] =
    Option(selector.channel(connectionId)).orElse(Option(selector.closingChannel(connectionId)))

  // Indicate the specified channel that the specified channel mute-related event has happened so that it can change its
  // mute state.
  private def handleChannelMuteEvent(connectionId: String, event: ChannelMuteEvent): Unit = {
    openOrClosingChannel(connectionId).foreach(c => c.handleChannelMuteEvent(event))
  }

  private def tryUnmuteChannel(connectionId: String) = {
    openOrClosingChannel(connectionId).foreach(c => selector.unmute(c.id))
  }

  /* For test usage */
  private[network] def channel(connectionId: String): Option[KafkaChannel] =
    Option(selector.channel(connectionId))

  /**
   * Wakeup the thread for selection.
   */
  override def wakeup() = selector.wakeup()

  override def shutdown(): Unit = {
    super.shutdown()
    removeMetric("IdlePercent", Map("networkProcessor" -> id.toString))
    metrics.removeMetric(expiredConnectionsKilledCountMetricName)
  }

}

class ConnectionQuotas(config: KafkaConfig, time: Time) extends Logging {

  @volatile private var defaultMaxConnectionsPerIp: Int = config.maxConnectionsPerIp
  @volatile private var maxConnectionsPerIpOverrides = config.maxConnectionsPerIpOverrides.map { case (host, count) => (InetAddress.getByName(host), count) }
  @volatile private var brokerMaxConnections = config.maxConnections
  private val counts = mutable.Map[InetAddress, Int]()

  // Listener counts and configs are synchronized on `counts`
  private val listenerCounts = mutable.Map[ListenerName, Int]()
  private val maxConnectionsPerListener = mutable.Map[ListenerName, ListenerConnectionQuota]()
  @volatile private var totalCount = 0

  def inc(listenerName: ListenerName, address: InetAddress, acceptorBlockedPercentMeter: com.yammer.metrics.core.Meter): Unit = {
    counts.synchronized {
      waitForConnectionSlot(listenerName, acceptorBlockedPercentMeter)

      val count = counts.getOrElseUpdate(address, 0)
      counts.put(address, count + 1)
      totalCount += 1
      if (listenerCounts.contains(listenerName)) {
        listenerCounts.put(listenerName, listenerCounts(listenerName) + 1)
      }
      val max = maxConnectionsPerIpOverrides.getOrElse(address, defaultMaxConnectionsPerIp)
      if (count >= max)
        throw new TooManyConnectionsException(address, max)
    }
  }

  private[network] def updateMaxConnectionsPerIp(maxConnectionsPerIp: Int): Unit = {
    defaultMaxConnectionsPerIp = maxConnectionsPerIp
  }

  private[network] def updateMaxConnectionsPerIpOverride(overrideQuotas: Map[String, Int]): Unit = {
    maxConnectionsPerIpOverrides = overrideQuotas.map { case (host, count) => (InetAddress.getByName(host), count) }
  }

  private[network] def updateBrokerMaxConnections(maxConnections: Int): Unit = {
    counts.synchronized {
      brokerMaxConnections = maxConnections
      counts.notifyAll()
    }
  }

  private[network] def addListener(config: KafkaConfig, listenerName: ListenerName): Unit = {
    counts.synchronized {
      if (!maxConnectionsPerListener.contains(listenerName)) {
        val newListenerQuota = new ListenerConnectionQuota(counts, listenerName)
        maxConnectionsPerListener.put(listenerName, newListenerQuota)
        listenerCounts.put(listenerName, 0)
        config.addReconfigurable(newListenerQuota)
      }
      counts.notifyAll()
    }
  }

  private[network] def removeListener(config: KafkaConfig, listenerName: ListenerName): Unit = {
    counts.synchronized {
      maxConnectionsPerListener.remove(listenerName).foreach { listenerQuota =>
        listenerCounts.remove(listenerName)
        counts.notifyAll() // wake up any waiting acceptors to close cleanly
        config.removeReconfigurable(listenerQuota)
      }
    }
  }

  def dec(listenerName: ListenerName, address: InetAddress): Unit = {
    counts.synchronized {
      val count = counts.getOrElse(address,
        throw new IllegalArgumentException(s"Attempted to decrease connection count for address with no connections, address: $address"))
      if (count == 1)
        counts.remove(address)
      else
        counts.put(address, count - 1)

      if (totalCount <= 0)
        error(s"Attempted to decrease total connection count for broker with no connections")
      totalCount -= 1

      if (maxConnectionsPerListener.contains(listenerName)) {
        val listenerCount = listenerCounts(listenerName)
        if (listenerCount == 0)
          error(s"Attempted to decrease connection count for listener $listenerName with no connections")
        else
          listenerCounts.put(listenerName, listenerCount - 1)
      }
      counts.notifyAll() // wake up any acceptors waiting to process a new connection since listener connection limit was reached
    }
  }

  def get(address: InetAddress): Int = counts.synchronized {
    counts.getOrElse(address, 0)
  }

  private def waitForConnectionSlot(listenerName: ListenerName,
                                    acceptorBlockedPercentMeter: com.yammer.metrics.core.Meter): Unit = {
    counts.synchronized {
      if (!connectionSlotAvailable(listenerName)) {
        val startNs = time.nanoseconds
        do {
          counts.wait()
        } while (!connectionSlotAvailable(listenerName))
        acceptorBlockedPercentMeter.mark(time.nanoseconds - startNs)
      }
    }
  }

  // This is invoked in every poll iteration and we close one LRU connection in an iteration
  // if necessary
  def maxConnectionsExceeded(listenerName: ListenerName): Boolean = {
    totalCount > brokerMaxConnections && !protectedListener(listenerName)
  }

  private def connectionSlotAvailable(listenerName: ListenerName): Boolean = {
    if (listenerCounts(listenerName) >= maxListenerConnections(listenerName))
      false
    else if (protectedListener(listenerName))
      true
    else
      totalCount < brokerMaxConnections
  }

  private def protectedListener(listenerName: ListenerName): Boolean =
    config.interBrokerListenerName == listenerName && config.listeners.size > 1

  private def maxListenerConnections(listenerName: ListenerName): Int =
    maxConnectionsPerListener.get(listenerName).map(_.maxConnections).getOrElse(Int.MaxValue)

  class ListenerConnectionQuota(lock: Object, listener: ListenerName) extends ListenerReconfigurable {
    @volatile private var _maxConnections = Int.MaxValue

    def maxConnections: Int = _maxConnections

    override def listenerName(): ListenerName = listener

    override def configure(configs: util.Map[String, _]): Unit = {
      _maxConnections = maxConnections(configs)
    }

    override def reconfigurableConfigs(): util.Set[String] = {
      SocketServer.ListenerReconfigurableConfigs.asJava
    }

    override def validateReconfiguration(configs: util.Map[String, _]): Unit = {
      val value = maxConnections(configs)
      if (value <= 0)
        throw new ConfigException("Invalid max.connections $listenerMax")
    }

    override def reconfigure(configs: util.Map[String, _]): Unit = {
      lock.synchronized {
        _maxConnections = maxConnections(configs)
        lock.notifyAll()
      }
    }

    private def maxConnections(configs: util.Map[String, _]): Int = {
      Option(configs.get(KafkaConfig.MaxConnectionsProp)).map(_.toString.toInt).getOrElse(Int.MaxValue)
    }
  }
}

class TooManyConnectionsException(val ip: InetAddress, val count: Int) extends KafkaException(s"Too many connections from $ip (maximum = $count)")
