/*
/* Copyright 2018-2025 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.spark.agent.util;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.spark.package$;
import org.apache.spark.rdd.HadoopRDD;
import org.apache.spark.rdd.MapPartitionsRDD;
import org.apache.spark.rdd.NewHadoopRDD;
import org.apache.spark.rdd.ParallelCollectionRDD;
import org.apache.spark.rdd.RDD;
import org.apache.spark.rdd.UnionRDD;
import org.apache.spark.sql.execution.datasources.FilePartition;
import org.apache.spark.sql.execution.datasources.FileScanRDD;
import scala.Tuple2;
import scala.collection.immutable.Seq;
import scala.collection.mutable.ArrayBuffer;

/** Utility class to extract paths from RDD nodes. */
@Slf4j
public class RddPathUtils {

  public static Stream<Path> findRDDPaths(RDD rdd) {
    return Stream.<RddPathExtractor>of(
            new HadoopRDDExtractor(),
            new FileScanRDDExtractor(),
            new MapPartitionsRDDExtractor(),
            new UnionRddExctractor(),
            new NewHadoopRDDExtractor(),
            new ParallelCollectionRDDExtractor())
        .filter(e -> e.isDefinedAt(rdd))
        .findFirst()
        .orElse(new UnknownRDDExtractor())
        .extract(rdd)
        .filter(p -> p != null);
  }

  static class UnionRddExctractor implements RddPathExtractor<RDD> {
    @Override
    public boolean isDefinedAt(Object rdd) {
      return rdd instanceof UnionRDD;
    }

    @Override
    public Stream<Path> extract(RDD rdd) {
      return ScalaConversionUtils.<RDD>fromSeq(((UnionRDD) rdd).rdds()).stream()
          .map(RDD.class::cast)
          .flatMap(r -> findRDDPaths((RDD) r));
    }
  }

  static class UnknownRDDExtractor implements RddPathExtractor<RDD> {
    @Override
    public boolean isDefinedAt(Object rdd) {
      return true;
    }

    @Override
    public Stream<Path> extract(RDD rdd) {
      log.warn("Unknown RDD class {}", rdd);
      return Stream.empty();
    }
  }

  static class HadoopRDDExtractor implements RddPathExtractor<HadoopRDD> {
    @Override
    public boolean isDefinedAt(Object rdd) {
      return rdd instanceof HadoopRDD;
    }

    @Override
    public Stream<Path> extract(HadoopRDD rdd) {
      org.apache.hadoop.fs.Path[] inputPaths = FileInputFormat.getInputPaths(rdd.getJobConf());
      Configuration hadoopConf = rdd.getConf();
      if (log.isDebugEnabled()) {
        log.debug("Hadoop RDD class {}", rdd.getClass());
        log.debug("Hadoop RDD input paths {}", Arrays.toString(inputPaths));
        log.debug("Hadoop RDD job conf {}", rdd.getJobConf());
      }
      return Arrays.stream(inputPaths).map(p -> PlanUtils.getDirectoryPath(p, hadoopConf));
    }
  }

  static class NewHadoopRDDExtractor implements RddPathExtractor<NewHadoopRDD> {
    @Override
    public boolean isDefinedAt(Object rdd) {
      return rdd instanceof NewHadoopRDD;
    }

    @Override
    public Stream<Path> extract(NewHadoopRDD rdd) {
      try {
        org.apache.hadoop.fs.Path[] inputPaths =
            org.apache.hadoop.mapreduce.lib.input.FileInputFormat.getInputPaths(
                new Job(((NewHadoopRDD<?, ?>) rdd).getConf()));

        return Arrays.stream(inputPaths).map(p -> PlanUtils.getDirectoryPath(p, rdd.getConf()));
      } catch (IOException e) {
        log.error("Openlineage spark agent could not get input paths", e);
      }
      return Stream.empty();
    }
  }

  static class MapPartitionsRDDExtractor implements RddPathExtractor<MapPartitionsRDD> {

    @Override
    public boolean isDefinedAt(Object rdd) {
      return rdd instanceof MapPartitionsRDD;
    }

    @Override
    public Stream<Path> extract(MapPartitionsRDD rdd) {
      if (log.isDebugEnabled()) {
        log.debug("Parent RDD: {}", rdd.prev());
      }
      return findRDDPaths(rdd.prev());
    }
  }

  static class FileScanRDDExtractor implements RddPathExtractor<FileScanRDD> {
    @Override
    public boolean isDefinedAt(Object rdd) {
      return rdd instanceof FileScanRDD;
    }

    @Override
    @SuppressWarnings("PMD.AvoidLiteralsInIfCondition")
    public Stream<Path> extract(FileScanRDD rdd) {
      return ScalaConversionUtils.fromSeq(rdd.filePartitions()).stream()
          .flatMap((FilePartition fp) -> Arrays.stream(fp.files()))
          .map(
              f -> {
                if ("3.4".compareTo(package$.MODULE$.SPARK_VERSION()) <= 0) {
                  // filePath returns SparkPath for Spark 3.4
                  return ReflectionUtils.tryExecuteMethod(f, "filePath")
                      .map(o -> ReflectionUtils.tryExecuteMethod(o, "toPath"))
                      .map(o -> (Path) o.get())
                      .get()
                      .getParent();
                } else {
                  return parentOf(f.filePath());
                }
              });
    }
  }

  static class ParallelCollectionRDDExtractor implements RddPathExtractor<ParallelCollectionRDD> {
    @Override
    public boolean isDefinedAt(Object rdd) {
      return rdd instanceof ParallelCollectionRDD;
    }

    @Override
    public Stream<Path> extract(ParallelCollectionRDD rdd) {
      int SEQ_LIMIT = 1000;
      AtomicBoolean loggingDone = new AtomicBoolean(false);
      try {
        Object data = FieldUtils.readField(rdd, "data", true);
        log.debug("ParallelCollectionRDD data: {}", data);
        if ((data instanceof Seq)
            && (!((Seq<?>) data).isEmpty())
            && ((Seq) data).head() instanceof Tuple2) {
          // exit if the first element is invalid
          Seq data_slice = (Seq) ((Seq) data).slice(0, SEQ_LIMIT);
          return ScalaConversionUtils.fromSeq(data_slice).stream()
              .map(
                  el -> {
                    Path path = null;
                    if (el instanceof Tuple2) {
                      // we're able to extract path
                      path = parentOf(((Tuple2) el)._1.toString());
                      log.debug("Found input {}", path);
                    } else if (!loggingDone.get()) {
                      log.warn("unable to extract Path from {}", el.getClass().getCanonicalName());
                      loggingDone.set(true);
                    }
                    return path;
                  })
              .filter(Objects::nonNull);
        } else if ((data instanceof ArrayBuffer) && !((ArrayBuffer<?>) data).isEmpty()) {
          ArrayBuffer<?> dataBuffer = (ArrayBuffer<?>) data;
          return ScalaConversionUtils.fromSeq(dataBuffer.toSeq()).stream()
              .map(o -> parentOf(o.toString()))
              .filter(Objects::nonNull);
        } else {
          log.warn("Cannot extract path from ParallelCollectionRDD {}", data);
        }
      } catch (IllegalAccessException | IllegalArgumentException e) {
        log.warn("Cannot read data field from ParallelCollectionRDD {}", rdd);
      }
      return Stream.empty();
    }
  }

  private static Path parentOf(String path) {
    try {
      return new Path(path).getParent();
    } catch (Exception e) {
      if (log.isDebugEnabled()) {
        log.debug("Cannot get parent of path {}", path, e);
      }
      return null;
    }
  }

  interface RddPathExtractor<T extends RDD> {
    boolean isDefinedAt(Object rdd);

    Stream<Path> extract(T rdd);
  }
}
