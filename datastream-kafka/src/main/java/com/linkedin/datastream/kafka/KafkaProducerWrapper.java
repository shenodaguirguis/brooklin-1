/**
 *  Copyright 2019 LinkedIn Corporation. All rights reserved.
 *  Licensed under the BSD 2-Clause License. See the LICENSE file in the project root for license information.
 *  See the NOTICE file in the project root for additional information regarding copyright ownership.
 */
package com.linkedin.datastream.kafka;

import com.linkedin.datastream.kafka.factory.KafkaProducerFactory;
import com.linkedin.datastream.kafka.factory.SimpleKafkaProducerFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.RateLimiter;


import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.datastream.common.DatastreamRuntimeException;
import com.linkedin.datastream.common.DatastreamTransientException;
import com.linkedin.datastream.common.ReflectionUtils;
import com.linkedin.datastream.common.VerifiableProperties;
import com.linkedin.datastream.metrics.BrooklinGaugeInfo;
import com.linkedin.datastream.metrics.BrooklinMeterInfo;
import com.linkedin.datastream.metrics.BrooklinMetricInfo;
import com.linkedin.datastream.metrics.DynamicMetricsManager;
import com.linkedin.datastream.metrics.MetricsAware;
import com.linkedin.datastream.server.DatastreamTask;

import static com.linkedin.datastream.connectors.CommonConnectorMetrics.AGGREGATE;
import static com.linkedin.datastream.kafka.factory.KafkaProducerFactory.*;

public class KafkaProducerWrapper<K, V> {
  private static final String CLASS_NAME = KafkaProducerWrapper.class.getSimpleName();
  public static final String PRODUCER_ERROR = "producerError";
  public static final String PRODUCER_COUNT = "producerCount";

  private static AtomicInteger _producerCount = new AtomicInteger();
  private static final Supplier<Integer> PRODUCER_GAUGE = () -> _producerCount.get();

  private static final int TIME_OUT = 2000;
  private static final int MAX_SEND_ATTEMPTS = 10;
  private final Logger _log;
  private final long _sendFailureRetryWaitTimeMs;

  private final String _clientId;
  private final Properties _props;

  // Set of datastream tasks assigned to the producer
  private Set<DatastreamTask> _tasks = ConcurrentHashMap.newKeySet();

  // Last exception thrown by Kafka producer for that particular task
  private Map<DatastreamTask, RuntimeException> _lastExceptionForTasks = new ConcurrentHashMap<>();

  // Producer is lazy initialize during the first send call.
  // Also, can be nullified for exception and recreated by the subsequent send calls
  // Mark as volatile as it is mutable and used by different threads
  private volatile Producer<K, V> _kafkaProducer;

  private final KafkaProducerFactory<K, V> _producerFactory;

  // Limiter to control how fast producers are re-created after failures.
  // Note that there is not delay the first time the producer is created,
  // but subsequent calls will be limited to 1 every 10 seconds by default.
  // The reason is to give time to the kafka producer to release resources and
  // close threads before creating a new one.
  private static final Double DEFAULT_RATE_LIMITER = 0.1;
  private final RateLimiter _rateLimiter;

  // Default producer configuration for no data loss pipeline.
  private static final String DEFAULT_PRODUCER_ACKS_CONFIG_VALUE = "all";
  private static final String DEFAULT_MAX_BLOCK_MS_CONFIG_VALUE = String.valueOf(Integer.MAX_VALUE);
  private static final String DEFAULT_MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION_VALUE = "1";

  private static final long DEFAULT_SEND_FAILURE_RETRY_WAIT_MS = Duration.ofSeconds(5).toMillis();

  public static final String CFG_SEND_FAILURE_RETRY_WAIT_MS = "send.failure.retry.wait.time.ms";
  public static final String CFG_KAFKA_PRODUCER_FACTORY = "kafkaProducerFactory";
  private static final String CFG_RATE_LIMITER_CFG = "producerRateLimiter";

  private final DynamicMetricsManager _dynamicMetricsManager;
  private final String _metricsNamesPrefix;

  public KafkaProducerWrapper(String logSuffix, Properties props) {
    this(logSuffix, props, null);
  }

  public KafkaProducerWrapper(String logSuffix, Properties props, String metricsNamesPrefix) {
    _log = LoggerFactory.getLogger(String.format("%s:%s", KafkaTransportProvider.class, logSuffix));

    if (!props.containsKey(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)) {
      throw new RuntimeException("Bootstrap servers are not set");
    }

    VerifiableProperties transportProviderProperties = new VerifiableProperties(props);

    _dynamicMetricsManager = DynamicMetricsManager.getInstance();
    _metricsNamesPrefix = metricsNamesPrefix == null ? CLASS_NAME : metricsNamesPrefix + CLASS_NAME;
    _dynamicMetricsManager.registerGauge(_metricsNamesPrefix, AGGREGATE, PRODUCER_COUNT, PRODUCER_GAUGE);

    _clientId = transportProviderProperties.getProperty(ProducerConfig.CLIENT_ID_CONFIG);
    if (_clientId == null || _clientId.isEmpty()) {
      _log.warn("Client Id is either null or empty");
    }

    _sendFailureRetryWaitTimeMs =
        transportProviderProperties.getLong(CFG_SEND_FAILURE_RETRY_WAIT_MS, DEFAULT_SEND_FAILURE_RETRY_WAIT_MS);

    _rateLimiter =
        RateLimiter.create(transportProviderProperties.getDouble(CFG_RATE_LIMITER_CFG, DEFAULT_RATE_LIMITER));

    _props = props;

    String kafkaProducerFactoryName = transportProviderProperties.getString(CFG_KAFKA_PRODUCER_FACTORY,
        SimpleKafkaProducerFactory.class.getCanonicalName());
    _producerFactory = ReflectionUtils.createInstance(kafkaProducerFactoryName);

    populateDefaultProducerConfigs();
  }

  private void populateDefaultProducerConfigs() {
    _props.putIfAbsent(DOMAIN_PRODUCER + "." + ProducerConfig.ACKS_CONFIG, DEFAULT_PRODUCER_ACKS_CONFIG_VALUE);
    _props.putIfAbsent(DOMAIN_PRODUCER + "." + ProducerConfig.MAX_BLOCK_MS_CONFIG, DEFAULT_MAX_BLOCK_MS_CONFIG_VALUE);
    _props.putIfAbsent(DOMAIN_PRODUCER + "." + ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION,
        DEFAULT_MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION_VALUE);
  }

  private Optional<Producer<K, V>> maybeGetKafkaProducer(DatastreamTask task) {
    Producer<K, V> producer = _kafkaProducer;
    if (producer == null) {
      producer = initializeProducer(task);
    }
    return Optional.ofNullable(producer);
  }

  public void assignTask(DatastreamTask task) {
    _tasks.add(task);
  }

  public void unassignTask(DatastreamTask task) {
    _tasks.remove(task);
    _lastExceptionForTasks.remove(task);
  }

  public int getTasksSize() {
    return _tasks.size();
  }

  private void notifyTaskForException(DatastreamTask task) {
    RuntimeException exception = _lastExceptionForTasks.remove(task);
    if (exception != null) {
      throw exception;
    }
  }

  /**
   * Must be synchronized to avoid creating duplicate producers when multiple concurrent
   * sends are in-flight and _kafkaProducer has been set to null as a result of previous
   * producer exception.
   */
  private synchronized Producer<K, V> initializeProducer(DatastreamTask task) {
    if (!_tasks.contains(task)) {
      _log.warn("Task {} has been unassigned for for producer, abort the sending ", task);
      return null;
    } else {
      if (_kafkaProducer == null) {
        _rateLimiter.acquire();
        _kafkaProducer = _producerFactory.createProducer(_props);
        _producerCount.incrementAndGet();
      }
    }
    return _kafkaProducer;
  }

  public void send(DatastreamTask task, ProducerRecord<K, V> producerRecord, Callback onComplete)
      throws InterruptedException {
    notifyTaskForException(task);

    // There are two known cases that lead to IllegalStateException and we should retry:
    //  1) number of brokers is less than minISR
    //  2) producer is closed in handleSendFailure by another thread
    // For either condition, we should retry as broker comes back healthy or producer is recreated
    boolean retry = true;
    int numberOfAttempt = 0;
    while (retry) {
      try {
        ++numberOfAttempt;
        maybeGetKafkaProducer(task).ifPresent(p -> p.send(producerRecord, (metadata, exception) -> {
          if (exception != null) {
            handleSendFailure(task, exception);
          }
          onComplete.onCompletion(metadata, exception);
        }));

        retry = false;
      } catch (IllegalStateException e) {
        _log.warn("Either send is called on a closed producer or broker count is less than minISR, retry in {} ms.",
            _sendFailureRetryWaitTimeMs, e);
        Thread.sleep(_sendFailureRetryWaitTimeMs);
      } catch (TimeoutException e) {
        _log.warn("Kafka producer buffer is full, retry in {} ms.", _sendFailureRetryWaitTimeMs, e);
        Thread.sleep(_sendFailureRetryWaitTimeMs);
      } catch (KafkaException e) {
        Throwable cause = e.getCause();
        while (cause != null && cause instanceof KafkaException) {
          cause = cause.getCause();
        }
        // Set a max_send_attempts for KafkaException as it may be non-recoverable
        if (numberOfAttempt > MAX_SEND_ATTEMPTS || (cause != null && (cause instanceof Error || cause instanceof RuntimeException))) {
          _log.error("Send failed for partition {} with a non retriable exception", producerRecord.partition(), e);
          handleSendFailure(task, e);
          notifyTaskForException(task);
        } else {
          _log.warn("Send failed for partition {} with retriable exception, retry {} out of {} in {} ms.",
              producerRecord.partition(), numberOfAttempt, MAX_SEND_ATTEMPTS, _sendFailureRetryWaitTimeMs, e);
          Thread.sleep(_sendFailureRetryWaitTimeMs);
        }
      } catch (Exception e) {
        _log.error("Send failed for partition {} with an exception", producerRecord.partition(), e);
        handleSendFailure(task, e);
        notifyTaskForException(task);
      }
    }
  }

  private synchronized void shutdownProducer() {
    Producer<K, V> producer = _kafkaProducer;
    // Nullify first to prevent subsequent send() to use
    // the current producer which is being shutdown.
    _kafkaProducer = null;
    if (producer != null) {
      producer.close(TIME_OUT, TimeUnit.MILLISECONDS);
      _producerCount.decrementAndGet();
    }
  }

  private void handleSendFailure(DatastreamTask task, Exception exception) {
    _dynamicMetricsManager.createOrUpdateMeter(_metricsNamesPrefix, AGGREGATE, PRODUCER_ERROR, 1);
    if (exception instanceof IllegalStateException) {
      _log.warn("sent failure transiently due to ", exception);
      _lastExceptionForTasks.putIfAbsent(task, new DatastreamTransientException(exception));
    } else {
      _log.warn("sent failure due to ", exception);
      _lastExceptionForTasks.putIfAbsent(task, new DatastreamRuntimeException(exception));
      shutdownProducer();
    }
  }

  public synchronized void flush(DatastreamTask task) {
    notifyTaskForException(task);

    if (_kafkaProducer != null) {
      _kafkaProducer.flush();
    }
  }

  public synchronized void close(DatastreamTask task) {
    _tasks.remove(task);
    _lastExceptionForTasks.remove(task);
    if (_kafkaProducer != null && _tasks.isEmpty()) {
      shutdownProducer();
    }
  }

  public static List<BrooklinMetricInfo> getMetricDetails(String metricsNamesPrefix) {
    String prefix = metricsNamesPrefix == null ? CLASS_NAME + MetricsAware.KEY_REGEX
        : metricsNamesPrefix + CLASS_NAME + MetricsAware.KEY_REGEX;

    List<BrooklinMetricInfo> metrics = new ArrayList<>();
    metrics.add(new BrooklinMeterInfo(prefix + PRODUCER_ERROR));
    metrics.add(new BrooklinGaugeInfo(prefix + PRODUCER_COUNT));
    return Collections.unmodifiableList(metrics);
  }

  @VisibleForTesting
  public Properties getProperties() {
    Properties props = new Properties();
    props.putAll(_props);
    return props;
  }
}
