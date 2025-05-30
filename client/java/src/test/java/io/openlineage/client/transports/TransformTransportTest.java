/*
/* Copyright 2018-2025 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.client.transports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.openlineage.client.OpenLineage;
import io.openlineage.client.OpenLineage.DatasetEvent;
import io.openlineage.client.OpenLineage.JobEvent;
import io.openlineage.client.OpenLineage.RunEvent;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransformTransportTest {
  TransformConfig transformConfig;
  TransformTransport transformTransport;
  Transport subTransport;

  @BeforeEach
  void setup() {
    transformConfig = new TransformConfig();
    transformConfig.setTransport(new ConsoleConfig());
    transformConfig.setTransformerProperties(Collections.emptyMap());

    subTransport = mock(Transport.class);
  }

  @Test
  void testTransportWhenTransformClassDoesNotExist() {
    transformConfig.setTransformerClass("io.openlineage.FakeClass");

    assertThrows(
        TransformTransportException.class,
        () -> {
          transformTransport = new TransformTransport(transformConfig);
        });
  }

  @Test
  void testTransportWhenTransformClassNotAnInterface() {
    transformConfig.setTransformerClass(EventTransformerNotImplementingInterface.class.getName());

    assertThrows(
        TransformTransportException.class,
        () -> {
          transformTransport = new TransformTransport(transformConfig);
        });
  }

  @Test
  void testTransportWhenTransformClassDoesNotHaveDefaultConstructor() {
    transformConfig.setTransformerClass(EventTransformerWithoutDefaultConstructor.class.getName());

    assertThrows(
        TransformTransportException.class,
        () -> {
          transformTransport = new TransformTransport(transformConfig);
        });
  }

  @Test
  void testTransportWhenTransformThrowingException() {
    transformConfig.setTransformerClass(EventTransformerThrowingException.class.getName());
    transformTransport = new TransformTransport(transformConfig);

    assertThrows(
        TransformTransportException.class,
        () -> {
          transformTransport.emit(mock(RunEvent.class));
        });

    assertThrows(
        TransformTransportException.class,
        () -> {
          transformTransport.emit(mock(DatasetEvent.class));
        });

    assertThrows(
        TransformTransportException.class,
        () -> {
          transformTransport.emit(mock(JobEvent.class));
        });
  }

  @Test
  void testTransportWhenTransformReturnsNull() {
    transformConfig.setTransformerClass(EventTransformerReturningNull.class.getName());
    transformTransport = new TransformTransport(transformConfig, subTransport);

    RunEvent runEvent = mock(RunEvent.class);
    DatasetEvent datasetEvent = mock(DatasetEvent.class);
    JobEvent jobEvent = mock(JobEvent.class);

    transformTransport.emit(runEvent);
    transformTransport.emit(datasetEvent);
    transformTransport.emit(jobEvent);

    verify(subTransport, times(0)).emit((RunEvent) argThat(event -> event instanceof RunEvent));
    verify(subTransport, times(0)).emit((RunEvent) argThat(event -> event instanceof DatasetEvent));
    verify(subTransport, times(0)).emit((RunEvent) argThat(event -> event instanceof JobEvent));
  }

  @Test
  void testTransportWhenTransformIsSuccessful() {
    transformConfig.setTransformerClass(SuccessfulEventTransformer.class.getName());
    transformTransport = new TransformTransport(transformConfig, subTransport);

    RunEvent runEvent = mock(RunEvent.class);
    DatasetEvent datasetEvent = mock(DatasetEvent.class);
    JobEvent jobEvent = mock(JobEvent.class);

    transformTransport.emit(runEvent);
    transformTransport.emit(datasetEvent);
    transformTransport.emit(jobEvent);

    verify(subTransport, times(1))
        .emit(
            (RunEvent)
                argThat(
                    event -> {
                      assertThat(event).isInstanceOf(RunEvent.class);
                      assertThat(((RunEvent) event).getJob())
                          .extracting("namespace", "name")
                          .isEqualTo(Arrays.asList("modified-namespace", "modified-name"));
                      return true;
                    }));

    verify(subTransport, times(1))
        .emit(
            (DatasetEvent)
                argThat(
                    event -> {
                      assertThat(event).isInstanceOf(DatasetEvent.class);
                      assertThat(((DatasetEvent) event).getDataset())
                          .extracting("namespace", "name")
                          .isEqualTo(Arrays.asList("modified-namespace", "modified-name"));
                      return true;
                    }));

    verify(subTransport, times(1))
        .emit(
            (JobEvent)
                argThat(
                    event -> {
                      assertThat(event).isInstanceOf(JobEvent.class);
                      assertThat(((JobEvent) event).getJob())
                          .extracting("namespace", "name")
                          .isEqualTo(Arrays.asList("modified-namespace", "modified-name"));
                      return true;
                    }));
  }

  @Test
  void testOriginalRunEventNotModifiedByTransform() {
    // Create a real event with specific values that can be modified
    OpenLineage openLineage = new OpenLineage(URI.create("test-producer"));
    RunEvent originalEvent =
        openLineage
            .newRunEventBuilder()
            .job(openLineage.newJob("original-namespace", "original-name", null))
            .eventType(OpenLineage.RunEvent.EventType.START)
            .build();

    transformConfig.setTransformerClass(MutatingEventTransformer.class.getName());
    transformTransport = new TransformTransport(transformConfig, subTransport);

    // Store original values before transformation
    String originalNamespace = originalEvent.getJob().getNamespace();
    String originalName = originalEvent.getJob().getName();

    // Emit the event
    transformTransport.emit(originalEvent);

    // Verify original event was not modified
    assertThat(originalEvent.getJob().getNamespace()).isEqualTo(originalNamespace);
    assertThat(originalEvent.getJob().getName()).isEqualTo(originalName);

    // Verify that the transported event was modified (proving deep copy worked)
    verify(subTransport, times(1))
        .emit(
            (RunEvent)
                argThat(
                    event -> {
                      assertThat(event).isInstanceOf(RunEvent.class);
                      assertThat(((RunEvent) event).getJob())
                          .extracting("namespace", "name")
                          .isEqualTo(Arrays.asList("mutated-namespace", "mutated-name"));
                      return true;
                    }));
  }

  @Test
  void testOriginalDatasetEventNotModifiedByTransform() {
    // Create a real event with specific values that can be modified
    OpenLineage openLineage = new OpenLineage(URI.create("test-producer"));
    DatasetEvent originalEvent =
        openLineage
            .newDatasetEventBuilder()
            .dataset(openLineage.newStaticDataset("original-namespace", "original-name", null))
            .build();

    transformConfig.setTransformerClass(MutatingEventTransformer.class.getName());
    transformTransport = new TransformTransport(transformConfig, subTransport);

    // Store original values before transformation
    String originalNamespace = originalEvent.getDataset().getNamespace();
    String originalName = originalEvent.getDataset().getName();

    // Emit the event
    transformTransport.emit(originalEvent);

    // Verify original event was not modified
    assertThat(originalEvent.getDataset().getNamespace()).isEqualTo(originalNamespace);
    assertThat(originalEvent.getDataset().getName()).isEqualTo(originalName);

    // Verify that the transported event was modified (proving deep copy worked)
    verify(subTransport, times(1))
        .emit(
            (DatasetEvent)
                argThat(
                    event -> {
                      assertThat(event).isInstanceOf(DatasetEvent.class);
                      assertThat(((DatasetEvent) event).getDataset())
                          .extracting("namespace", "name")
                          .isEqualTo(Arrays.asList("mutated-namespace", "mutated-name"));
                      return true;
                    }));
  }

  @Test
  void testOriginalJobEventNotModifiedByTransform() {
    // Create a real event with specific values that can be modified
    OpenLineage openLineage = new OpenLineage(URI.create("test-producer"));
    JobEvent originalEvent =
        openLineage
            .newJobEventBuilder()
            .job(openLineage.newJob("original-namespace", "original-name", null))
            .build();

    transformConfig.setTransformerClass(MutatingEventTransformer.class.getName());
    transformTransport = new TransformTransport(transformConfig, subTransport);

    // Store original values before transformation
    String originalNamespace = originalEvent.getJob().getNamespace();
    String originalName = originalEvent.getJob().getName();

    // Emit the event
    transformTransport.emit(originalEvent);

    // Verify original event was not modified
    assertThat(originalEvent.getJob().getNamespace()).isEqualTo(originalNamespace);
    assertThat(originalEvent.getJob().getName()).isEqualTo(originalName);

    // Verify that the transported event was modified (proving deep copy worked)
    verify(subTransport, times(1))
        .emit(
            (JobEvent)
                argThat(
                    event -> {
                      assertThat(event).isInstanceOf(JobEvent.class);
                      assertThat(((JobEvent) event).getJob())
                          .extracting("namespace", "name")
                          .isEqualTo(Arrays.asList("mutated-namespace", "mutated-name"));
                      return true;
                    }));
  }

  public static class EventTransformerNotImplementingInterface {
    public EventTransformerNotImplementingInterface() {}
  }

  public static class EventTransformerWithoutDefaultConstructor implements EventTransformer {
    private EventTransformerWithoutDefaultConstructor() {}

    @Override
    public RunEvent transform(RunEvent event) {
      return null;
    }

    @Override
    public DatasetEvent transform(DatasetEvent event) {
      return null;
    }

    @Override
    public JobEvent transform(JobEvent event) {
      return null;
    }
  }

  public static class EventTransformerThrowingException implements EventTransformer {

    public EventTransformerThrowingException() {}

    @Override
    @SneakyThrows
    public RunEvent transform(RunEvent event) {
      throw new Exception("whatever");
    }

    @Override
    @SneakyThrows
    public DatasetEvent transform(DatasetEvent event) {
      throw new Exception("whatever");
    }

    @Override
    @SneakyThrows
    public JobEvent transform(JobEvent event) {
      throw new Exception("whatever");
    }
  }

  public static class EventTransformerReturningNull implements EventTransformer {

    @Override
    public RunEvent transform(RunEvent event) {
      return null;
    }

    @Override
    public DatasetEvent transform(DatasetEvent event) {
      return null;
    }

    @Override
    public JobEvent transform(JobEvent event) {
      return null;
    }
  }

  public static class SuccessfulEventTransformer implements EventTransformer {

    OpenLineage openLineage = new OpenLineage(URI.create("producer"));

    @Override
    public RunEvent transform(RunEvent event) {
      return openLineage
          .newRunEventBuilder()
          .job(openLineage.newJob("modified-namespace", "modified-name", null))
          .eventType(event.getEventType())
          .build();
    }

    @Override
    public DatasetEvent transform(DatasetEvent event) {
      return openLineage
          .newDatasetEventBuilder()
          .dataset(openLineage.newStaticDataset("modified-namespace", "modified-name", null))
          .build();
    }

    @Override
    public JobEvent transform(JobEvent event) {
      return openLineage
          .newJobEventBuilder()
          .job(openLineage.newJob("modified-namespace", "modified-name", null))
          .build();
    }
  }

  public static class MutatingEventTransformer implements EventTransformer {

    OpenLineage openLineage = new OpenLineage(URI.create("producer"));

    @Override
    public RunEvent transform(RunEvent event) {
      return openLineage
          .newRunEventBuilder()
          .job(openLineage.newJob("mutated-namespace", "mutated-name", null))
          .eventType(event.getEventType())
          .build();
    }

    @Override
    public DatasetEvent transform(DatasetEvent event) {
      return openLineage
          .newDatasetEventBuilder()
          .dataset(openLineage.newStaticDataset("mutated-namespace", "mutated-name", null))
          .build();
    }

    @Override
    public JobEvent transform(JobEvent event) {
      return openLineage
          .newJobEventBuilder()
          .job(openLineage.newJob("mutated-namespace", "mutated-name", null))
          .build();
    }
  }
}
