/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package kafka.api

import java.util.Properties

import junit.framework.Assert
import kafka.consumer.SimpleConsumer
import kafka.integration.KafkaServerTestHarness
import kafka.server.{KafkaServer, KafkaConfig}
import kafka.utils.TestUtils
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
import org.apache.kafka.clients.producer._
import org.apache.kafka.clients.producer.internals.ErrorLoggingCallback
import org.apache.kafka.common.MetricName
import org.apache.kafka.common.metrics.KafkaMetric
import org.junit.Assert._
import org.junit.{After, Before, Test}
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import scala.collection.mutable

class QuotasTest extends KafkaServerTestHarness {
  private val producerBufferSize = 300000
  private val producerId1 = "QuotasTestProducer-1"
  private val producerId2 = "QuotasTestProducer-2"
  private val consumerId1 = "QuotasTestConsumer-1"
  private val consumerId2 = "QuotasTestConsumer-2"

  val numServers = 2
  val overridingProps = new Properties()

  // Low enough quota that a producer sending a small payload in a tight loop should get throttled
  overridingProps.put(KafkaConfig.ProducerQuotaBytesPerSecondDefaultProp, "8000")
  overridingProps.put(KafkaConfig.ConsumerQuotaBytesPerSecondDefaultProp, "2500")

  // un-throttled
  overridingProps.put(KafkaConfig.ProducerQuotaBytesPerSecondOverridesProp, producerId2 + "=" + Long.MaxValue)
  overridingProps.put(KafkaConfig.ConsumerQuotaBytesPerSecondOverridesProp, consumerId2 + "=" + Long.MaxValue)

  override def generateConfigs() = {
    FixedPortTestUtils.createBrokerConfigs(numServers,
                                           zkConnect,
                                           enableControlledShutdown = false)
            .map(KafkaConfig.fromProps(_, overridingProps))
  }

  var producers = mutable.Buffer[KafkaProducer[Array[Byte], Array[Byte]]]()
  var consumers = mutable.Buffer[KafkaConsumer[Array[Byte], Array[Byte]]]()
  var replicaConsumers = mutable.Buffer[SimpleConsumer]()

  var leaderNode: KafkaServer = null
  var followerNode: KafkaServer = null
  private val topic1 = "topic-1"

  @Before
  override def setUp() {
    super.setUp()
    val producerProps = new Properties()
    producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokerList)
    producerProps.put(ProducerConfig.ACKS_CONFIG, "0")
    producerProps.put(ProducerConfig.BLOCK_ON_BUFFER_FULL_CONFIG, "false")
    producerProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG, producerBufferSize.toString)
    producerProps.put(ProducerConfig.CLIENT_ID_CONFIG, producerId1)
    producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                      classOf[org.apache.kafka.common.serialization.ByteArraySerializer])
    producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                      classOf[org.apache.kafka.common.serialization.ByteArraySerializer])
    producers += new KafkaProducer[Array[Byte], Array[Byte]](producerProps)

    producerProps.put(ProducerConfig.CLIENT_ID_CONFIG, producerId2)
    producers += new KafkaProducer[Array[Byte], Array[Byte]](producerProps)

    val numPartitions = 1
    val leaders = TestUtils.createTopic(zkUtils, topic1, numPartitions, numServers, servers)
    leaderNode = if (leaders(0).get == servers.head.config.brokerId) servers.head else servers(1)
    followerNode = if (leaders(0).get != servers.head.config.brokerId) servers.head else servers(1)
    assertTrue("Leader of all partitions of the topic should exist", leaders.values.forall(leader => leader.isDefined))

    // Create consumers
    val consumerProps = new Properties
    consumerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokerList)
    consumerProps.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "QuotasTest")
    consumerProps.setProperty(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 4096.toString)
    consumerProps.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, this.bootstrapUrl)
    consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                      classOf[org.apache.kafka.common.serialization.ByteArrayDeserializer])
    consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                      classOf[org.apache.kafka.common.serialization.ByteArrayDeserializer])
    consumerProps.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, "range")

    consumerProps.put(ConsumerConfig.CLIENT_ID_CONFIG, consumerId1)
    consumers += new KafkaConsumer(consumerProps)
    // Create replica consumers with the same clientId as the high level consumer. These requests should never be throttled
    replicaConsumers += new SimpleConsumer("localhost", leaderNode.boundPort(), 1000000, 64*1024, consumerId1)

    consumerProps.put(ConsumerConfig.CLIENT_ID_CONFIG, consumerId2)
    consumers += new KafkaConsumer(consumerProps)
    replicaConsumers += new SimpleConsumer("localhost", leaderNode.boundPort(), 1000000, 64*1024, consumerId2)

  }

  @After
  override def tearDown() {
    producers.foreach( _.close )
    consumers.foreach( _.close )
    replicaConsumers.foreach( _.close )
    super.tearDown()
  }

  @Test
  def testThrottledProducerConsumer() {
    val allMetrics: mutable.Map[MetricName, KafkaMetric] = leaderNode.metrics.metrics().asScala

    val numRecords = 1000
    produce(producers.head, numRecords)

    val producerMetricName = new MetricName("throttle-time",
                                    RequestKeys.nameForKey(RequestKeys.ProduceKey),
                                    "Tracking throttle-time per client",
                                    "client-id", producerId1)
    Assert.assertTrue("Should have been throttled", allMetrics(producerMetricName).value() > 0)

    // Consumer should read in a bursty manner and get throttled immediately
    consume(consumers.head, numRecords)
    // The replica consumer should not be throttled also. Create a fetch request which will exceed the quota immediately
    val request = new FetchRequestBuilder().addFetch(topic1, 0, 0, 1024*1024).replicaId(followerNode.config.brokerId).build()
    replicaConsumers.head.fetch(request)
    val consumerMetricName = new MetricName("throttle-time",
                                            RequestKeys.nameForKey(RequestKeys.FetchKey),
                                            "Tracking throttle-time per client",
                                            "client-id", consumerId1)
    Assert.assertTrue("Should have been throttled", allMetrics(consumerMetricName).value() > 0)
  }

  @Test
  def testProducerConsumerOverrideUnthrottled() {
    val allMetrics: mutable.Map[MetricName, KafkaMetric] = leaderNode.metrics.metrics().asScala
    val numRecords = 1000
    produce(producers(1), numRecords)
    val producerMetricName = new MetricName("throttle-time",
                                            RequestKeys.nameForKey(RequestKeys.ProduceKey),
                                            "Tracking throttle-time per client",
                                            "client-id", producerId2)
    Assert.assertEquals("Should not have been throttled", 0.0, allMetrics(producerMetricName).value())

    // The "client" consumer does not get throttled.
    consume(consumers(1), numRecords)
    // The replica consumer should not be throttled also. Create a fetch request which will exceed the quota immediately
    val request = new FetchRequestBuilder().addFetch(topic1, 0, 0, 1024*1024).replicaId(followerNode.config.brokerId).build()
    replicaConsumers(1).fetch(request)
    val consumerMetricName = new MetricName("throttle-time",
                                            RequestKeys.nameForKey(RequestKeys.FetchKey),
                                            "Tracking throttle-time per client",
                                            "client-id", consumerId2)
    Assert.assertEquals("Should not have been throttled", 0.0, allMetrics(consumerMetricName).value())
  }

  def produce(p: KafkaProducer[Array[Byte], Array[Byte]], count: Int): Int = {
    var numBytesProduced = 0
    for (i <- 0 to count) {
      val payload = i.toString.getBytes
      numBytesProduced += payload.length
      p.send(new ProducerRecord[Array[Byte], Array[Byte]](topic1, null, null, payload),
             new ErrorLoggingCallback(topic1, null, null, true)).get()
      Thread.sleep(1)
    }
    numBytesProduced
  }

  def consume(consumer: KafkaConsumer[Array[Byte], Array[Byte]], numRecords: Int) {
    consumer.subscribe(List(topic1))
    var numConsumed = 0
    while (numConsumed < numRecords) {
      for (cr <- consumer.poll(100)) {
        numConsumed += 1
      }
    }
  }
}
