/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.fn.harness.data;

import com.google.auto.value.AutoValue;
import java.io.Closeable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.apache.beam.fn.harness.HandlesSplits;
import org.apache.beam.model.pipeline.v1.MetricsApi.MonitoringInfo;
import org.apache.beam.runners.core.metrics.ExecutionStateTracker;
import org.apache.beam.runners.core.metrics.MetricsContainerStepMap;
import org.apache.beam.runners.core.metrics.MonitoringInfoConstants;
import org.apache.beam.runners.core.metrics.MonitoringInfoConstants.Labels;
import org.apache.beam.runners.core.metrics.MonitoringInfoConstants.Urns;
import org.apache.beam.runners.core.metrics.MonitoringInfoMetricName;
import org.apache.beam.runners.core.metrics.ShortIdMap;
import org.apache.beam.runners.core.metrics.SimpleExecutionState;
import org.apache.beam.runners.core.metrics.SimpleStateRegistry;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.fn.data.FnDataReceiver;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Distribution;
import org.apache.beam.sdk.metrics.MetricsContainer;
import org.apache.beam.sdk.metrics.MetricsEnvironment;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.util.common.ElementByteSizeObserver;
import org.apache.beam.vendor.grpc.v1p26p0.com.google.protobuf.ByteString;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.annotations.VisibleForTesting;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ArrayListMultimap;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ListMultimap;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Lists;

/**
 * The {@code PCollectionConsumerRegistry} is used to maintain a collection of consuming
 * FnDataReceiver for each pCollectionId. Registering with this class allows inserting an element
 * count counter for every pCollection. A combined MultiplexingConsumer (Wrapped with an
 * ElementCountFnDataReceiver) is returned by calling getMultiplexingConsumer.
 */
@SuppressWarnings({
  "rawtypes", // TODO(https://issues.apache.org/jira/browse/BEAM-10556)
  "nullness" // TODO(https://issues.apache.org/jira/browse/BEAM-10402)
})
public class PCollectionConsumerRegistry {

  /** Stores metadata about each consumer so that the appropriate metrics tracking can occur. */
  @AutoValue
  @AutoValue.CopyAnnotations
  @SuppressWarnings({"rawtypes"})
  abstract static class ConsumerAndMetadata {
    public static ConsumerAndMetadata forConsumer(
        FnDataReceiver consumer,
        String pTransformId,
        SimpleExecutionState state,
        Coder valueCoder,
        MetricsContainer metricsContainer) {
      return new AutoValue_PCollectionConsumerRegistry_ConsumerAndMetadata(
          consumer, pTransformId, state, valueCoder, metricsContainer);
    }

    public abstract FnDataReceiver getConsumer();

    public abstract String getPTransformId();

    public abstract SimpleExecutionState getExecutionState();

    public abstract Coder getValueCoder();

    public abstract MetricsContainer getMetricsContainer();
  }

  private ListMultimap<String, ConsumerAndMetadata> pCollectionIdsToConsumers;
  private Map<String, FnDataReceiver> pCollectionIdsToWrappedConsumer;
  private MetricsContainerStepMap metricsContainerRegistry;
  private ExecutionStateTracker stateTracker;
  private SimpleStateRegistry executionStates = new SimpleStateRegistry();

  public PCollectionConsumerRegistry(
      MetricsContainerStepMap metricsContainerRegistry, ExecutionStateTracker stateTracker) {
    this.metricsContainerRegistry = metricsContainerRegistry;
    this.stateTracker = stateTracker;
    this.pCollectionIdsToConsumers = ArrayListMultimap.create();
    this.pCollectionIdsToWrappedConsumer = new HashMap<>();
  }

  /**
   * Register the specified consumer to handle the elements in the pCollection associated with
   * pCollectionId. All consumers must be registered before extracting the combined consumer by
   * calling getMultiplexingConsumer(), or an exception will be thrown.
   *
   * <p>This will cause both Element Count and Process Bundle Execution time metrics to be
   * collected.
   *
   * @param pCollectionId
   * @param pTransformId
   * @param consumer
   * @param <T> the element type of the PCollection
   * @throws RuntimeException if {@code register()} is called after {@code
   *     getMultiplexingConsumer()} is called.
   */
  public <T> void register(
      String pCollectionId,
      String pTransformId,
      FnDataReceiver<WindowedValue<T>> consumer,
      Coder<T> valueCoder) {
    // Just save these consumers for now, but package them up later with an
    // ElementCountFnDataReceiver and possibly a MultiplexingFnDataReceiver
    // if there are multiple consumers.
    if (pCollectionIdsToWrappedConsumer.containsKey(pCollectionId)) {
      throw new RuntimeException(
          "New consumers for a pCollectionId cannot be register()-d after "
              + "calling getMultiplexingConsumer.");
    }

    HashMap<String, String> labelsMetadata = new HashMap<>();
    labelsMetadata.put(MonitoringInfoConstants.Labels.PTRANSFORM, pTransformId);
    SimpleExecutionState state =
        new SimpleExecutionState(
            ExecutionStateTracker.PROCESS_STATE_NAME,
            MonitoringInfoConstants.Urns.PROCESS_BUNDLE_MSECS,
            labelsMetadata);
    executionStates.register(state);

    pCollectionIdsToConsumers.put(
        pCollectionId,
        ConsumerAndMetadata.forConsumer(
            consumer,
            pTransformId,
            state,
            valueCoder,
            metricsContainerRegistry.getContainer(pTransformId)));
  }

  /** Reset the execution states of the registered functions. */
  public void reset() {
    executionStates.reset();
  }

  /** @return the list of pcollection ids. */
  public Set<String> keySet() {
    return pCollectionIdsToConsumers.keySet();
  }

  /**
   * New consumers should not be register()-ed after calling this method. This will cause a
   * RuntimeException, as this would fail to properly wrap the late-added consumer to track metrics.
   *
   * @return A {@link FnDataReceiver} which directly wraps all the registered consumers.
   */
  public FnDataReceiver<WindowedValue<?>> getMultiplexingConsumer(String pCollectionId) {
    return pCollectionIdsToWrappedConsumer.computeIfAbsent(
        pCollectionId,
        pcId -> {
          List<ConsumerAndMetadata> consumerAndMetadatas = pCollectionIdsToConsumers.get(pcId);
          if (consumerAndMetadatas == null) {
            throw new IllegalArgumentException(
                String.format("Unknown PCollectionId %s", pCollectionId));
          } else if (consumerAndMetadatas.size() == 1) {
            if (consumerAndMetadatas.get(0).getConsumer() instanceof HandlesSplits) {
              return new SplittingMetricTrackingFnDataReceiver(pcId, consumerAndMetadatas.get(0));
            }
            return new MetricTrackingFnDataReceiver(pcId, consumerAndMetadatas.get(0));
          } else {
            /* TODO(SDF), Consider supporting splitting each consumer individually. This would never come up in the existing SDF expansion, but might be useful to support fused SDF nodes. This would require dedicated delivery of the split results to each of the consumers separately. */
            return new MultiplexingMetricTrackingFnDataReceiver(pcId, consumerAndMetadatas);
          }
        });
  }

  /** @return Execution Time MonitoringInfos based on the tracked start or finish function. */
  public List<MonitoringInfo> getExecutionTimeMonitoringInfos() {
    return executionStates.getExecutionTimeMonitoringInfos();
  }

  /** @return Execution Time Monitoring data based on the tracked start or finish function. */
  public Map<String, ByteString> getExecutionTimeMonitoringData(ShortIdMap shortIds) {
    return executionStates.getExecutionTimeMonitoringData(shortIds);
  }

  /** @return the underlying consumers for a pCollectionId, some tests may wish to check this. */
  @VisibleForTesting
  public List<FnDataReceiver> getUnderlyingConsumers(String pCollectionId) {
    return Lists.transform(
        pCollectionIdsToConsumers.get(pCollectionId), input -> input.getConsumer());
  }

  /**
   * A wrapping {@code FnDataReceiver<WindowedValue<T>>} which counts the number of elements
   * consumed by the original {@code FnDataReceiver<WindowedValue<T>> consumer} and sets up metrics
   * for tracking PTransform processing time.
   *
   * @param <T> - The receiving type of the PTransform.
   */
  private class MetricTrackingFnDataReceiver<T> implements FnDataReceiver<WindowedValue<T>> {
    private final FnDataReceiver<WindowedValue<T>> delegate;
    private final String pTransformId;
    private final SimpleExecutionState state;
    private final Counter unboundedElementCountCounter;
    private final SampleByteSizeDistribution<T> unboundedSampledByteSizeDistribution;
    private final Coder<T> coder;
    private final MetricsContainer metricsContainer;

    public MetricTrackingFnDataReceiver(
        String pCollectionId, ConsumerAndMetadata consumerAndMetadata) {
      this.delegate = consumerAndMetadata.getConsumer();
      this.state = consumerAndMetadata.getExecutionState();
      this.pTransformId = consumerAndMetadata.getPTransformId();
      HashMap<String, String> labels = new HashMap<String, String>();
      labels.put(Labels.PCOLLECTION, pCollectionId);

      // Collect the metric in a metric container which is not bound to the step name.
      // This is required to count elements from impulse steps, which will produce elements outside
      // of a pTransform context.
      MetricsContainer unboundMetricContainer = metricsContainerRegistry.getUnboundContainer();

      MonitoringInfoMetricName elementCountMetricName =
          MonitoringInfoMetricName.named(MonitoringInfoConstants.Urns.ELEMENT_COUNT, labels);
      this.unboundedElementCountCounter = unboundMetricContainer.getCounter(elementCountMetricName);

      MonitoringInfoMetricName sampledByteSizeMetricName =
          MonitoringInfoMetricName.named(Urns.SAMPLED_BYTE_SIZE, labels);
      this.unboundedSampledByteSizeDistribution =
          new SampleByteSizeDistribution<>(
              unboundMetricContainer.getDistribution(sampledByteSizeMetricName));

      this.coder = consumerAndMetadata.getValueCoder();
      this.metricsContainer = consumerAndMetadata.getMetricsContainer();
    }

    @Override
    public void accept(WindowedValue<T> input) throws Exception {
      // Increment the counter for each window the element occurs in.
      this.unboundedElementCountCounter.inc(input.getWindows().size());
      // TODO(BEAM-11879): Consider updating size per window when we have window optimization.
      this.unboundedSampledByteSizeDistribution.tryUpdate(input.getValue(), this.coder);
      // Wrap the consumer with extra logic to set the metric container with the appropriate
      // PTransform context. This ensures that user metrics obtain the pTransform ID when they are
      // created. Also use the ExecutionStateTracker and enter an appropriate state to track the
      // Process Bundle Execution time metric.
      try (Closeable closeable = MetricsEnvironment.scopedMetricsContainer(metricsContainer)) {
        try (Closeable trackerCloseable = stateTracker.enterState(state)) {
          this.delegate.accept(input);
        }
      }
      this.unboundedSampledByteSizeDistribution.finishLazyUpdate();
    }
  }

  /**
   * A wrapping {@code FnDataReceiver<WindowedValue<T>>} which counts the number of elements
   * consumed by the original {@code FnDataReceiver<WindowedValue<T>> consumers} and sets up metrics
   * for tracking PTransform processing time.
   *
   * @param <T> - The receiving type of the PTransform.
   */
  private class MultiplexingMetricTrackingFnDataReceiver<T>
      implements FnDataReceiver<WindowedValue<T>> {
    private final List<ConsumerAndMetadata> consumerAndMetadatas;
    private final Counter unboundedElementCountCounter;
    private final SampleByteSizeDistribution<T> unboundedSampledByteSizeDistribution;

    public MultiplexingMetricTrackingFnDataReceiver(
        String pCollectionId, List<ConsumerAndMetadata> consumerAndMetadatas) {
      this.consumerAndMetadatas = consumerAndMetadatas;
      HashMap<String, String> labels = new HashMap<String, String>();
      labels.put(Labels.PCOLLECTION, pCollectionId);

      // Collect the metric in a metric container which is not bound to the step name.
      // This is required to count elements from impulse steps, which will produce elements outside
      // of a pTransform context.
      MetricsContainer unboundMetricContainer = metricsContainerRegistry.getUnboundContainer();
      MonitoringInfoMetricName elementCountMetricName =
          MonitoringInfoMetricName.named(MonitoringInfoConstants.Urns.ELEMENT_COUNT, labels);
      this.unboundedElementCountCounter = unboundMetricContainer.getCounter(elementCountMetricName);

      MonitoringInfoMetricName sampledByteSizeMetricName =
          MonitoringInfoMetricName.named(Urns.SAMPLED_BYTE_SIZE, labels);
      this.unboundedSampledByteSizeDistribution =
          new SampleByteSizeDistribution<>(
              unboundMetricContainer.getDistribution(sampledByteSizeMetricName));
    }

    @Override
    public void accept(WindowedValue<T> input) throws Exception {
      // Increment the counter for each window the element occurs in.
      this.unboundedElementCountCounter.inc(input.getWindows().size());
      // Wrap the consumer with extra logic to set the metric container with the appropriate
      // PTransform context. This ensures that user metrics obtain the pTransform ID when they are
      // created. Also use the ExecutionStateTracker and enter an appropriate state to track the
      // Process Bundle Execution time metric.
      for (ConsumerAndMetadata consumerAndMetadata : consumerAndMetadatas) {

        if (consumerAndMetadata.getValueCoder() != null) {
          // TODO(BEAM-11879): Consider updating size per window when we have window optimization.
          this.unboundedSampledByteSizeDistribution.tryUpdate(
              input.getValue(), consumerAndMetadata.getValueCoder());
        }
        try (Closeable closeable =
            MetricsEnvironment.scopedMetricsContainer(consumerAndMetadata.getMetricsContainer())) {
          try (Closeable trackerCloseable =
              stateTracker.enterState(consumerAndMetadata.getExecutionState())) {
            consumerAndMetadata.getConsumer().accept(input);
          }
        }
        this.unboundedSampledByteSizeDistribution.finishLazyUpdate();
      }
    }
  }

  /**
   * A wrapping {@code FnDataReceiver<WindowedValue<T>>} which counts the number of elements
   * consumed by the original {@code FnDataReceiver<WindowedValue<T>> consumer} and forwards split
   * and progress requests to the original consumer.
   *
   * @param <T> - The receiving type of the PTransform.
   */
  private class SplittingMetricTrackingFnDataReceiver<T> extends MetricTrackingFnDataReceiver<T>
      implements HandlesSplits {
    private final HandlesSplits delegate;

    public SplittingMetricTrackingFnDataReceiver(
        String pCollection, ConsumerAndMetadata consumerAndMetadata) {
      super(pCollection, consumerAndMetadata);
      this.delegate = (HandlesSplits) consumerAndMetadata.getConsumer();
    }

    @Override
    public SplitResult trySplit(double fractionOfRemainder) {
      return delegate.trySplit(fractionOfRemainder);
    }

    @Override
    public double getProgress() {
      return delegate.getProgress();
    }
  }

  private static class SampleByteSizeDistribution<T> {
    /** Basic implementation of {@link ElementByteSizeObserver} for use in size estimation. */
    private static class ByteSizeObserver extends ElementByteSizeObserver {
      private long observedSize = 0;

      @Override
      protected void reportElementSize(long elementSize) {
        observedSize += elementSize;
      }
    }

    final Distribution distribution;
    ByteSizeObserver byteCountObserver;

    public SampleByteSizeDistribution(Distribution distribution) {
      this.distribution = distribution;
      this.byteCountObserver = null;
    }

    public void tryUpdate(T value, Coder<T> coder) throws Exception {
      if (shouldSampleElement()) {
        // First try using byte size observer
        byteCountObserver = new ByteSizeObserver();
        coder.registerByteSizeObserver(value, byteCountObserver);

        if (!byteCountObserver.getIsLazy()) {
          byteCountObserver.advance();
          this.distribution.update(byteCountObserver.observedSize);
        }
      } else {
        byteCountObserver = null;
      }
    }

    public void finishLazyUpdate() {
      // Advance lazy ElementByteSizeObservers, if any.
      if (byteCountObserver != null && byteCountObserver.getIsLazy()) {
        byteCountObserver.advance();
        this.distribution.update(byteCountObserver.observedSize);
      }
    }

    private static final int RESERVOIR_SIZE = 10;
    private static final int SAMPLING_THRESHOLD = 30;
    private long samplingToken = 0;
    private long nextSamplingToken = 0;
    private Random randomGenerator = new Random();

    private boolean shouldSampleElement() {
      // Sampling probability decreases as the element count is increasing.
      // We unconditionally sample the first samplingCutoff elements. Calculating
      // nextInt(samplingToken) for each element is expensive, so after a threshold, calculate the
      // gap to next sample.
      // https://erikerlandson.github.io/blog/2015/11/20/very-fast-reservoir-sampling/

      // Reset samplingToken if it's going to exceed the max value.
      if (samplingToken + 1 == Long.MAX_VALUE) {
        samplingToken = 0;
        nextSamplingToken = getNextSamplingToken(samplingToken);
      }

      samplingToken++;
      // Use traditional sampling until the threshold of 30
      if (nextSamplingToken == 0) {
        if (samplingToken <= RESERVOIR_SIZE
            || randomGenerator.nextInt((int) samplingToken) < RESERVOIR_SIZE) {
          if (samplingToken > SAMPLING_THRESHOLD) {
            nextSamplingToken = getNextSamplingToken(samplingToken);
          }
          return true;
        }
      } else if (samplingToken >= nextSamplingToken) {
        nextSamplingToken = getNextSamplingToken(samplingToken);
        return true;
      }
      return false;
    }

    private long getNextSamplingToken(long samplingToken) {
      double gap =
          Math.log(1.0 - randomGenerator.nextDouble())
              / Math.log(1.0 - RESERVOIR_SIZE / (double) samplingToken);
      return samplingToken + (int) gap;
    }
  }
}
