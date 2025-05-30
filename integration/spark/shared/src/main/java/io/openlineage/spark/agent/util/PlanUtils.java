/*
/* Copyright 2018-2025 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.spark.agent.util;

import static io.openlineage.spark.agent.util.ScalaConversionUtils.asJavaOptional;

import io.openlineage.client.OpenLineage;
import io.openlineage.spark.agent.Versions;
import io.openlineage.spark.api.naming.NameNormalizer;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.spark.rdd.RDD;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.types.ArrayType;
import org.apache.spark.sql.types.MapType;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import scala.PartialFunction;
import scala.PartialFunction$;

/**
 * Utility functions for traversing a {@link
 * org.apache.spark.sql.catalyst.plans.logical.LogicalPlan}.
 */
@Slf4j
public class PlanUtils {
  /**
   * Given a list of {@link PartialFunction}s merge to produce a single function that will test the
   * input against each function one by one until a match is found or {@link
   * PartialFunction$#empty()} is returned.
   *
   * @param fns
   * @param <T>
   * @param <D>
   * @return
   */
  public static <T, D> OpenLineageAbstractPartialFunction<T, Collection<D>> merge(
      Collection<? extends PartialFunction<T, ? extends Collection<D>>> fns) {
    return new OpenLineageAbstractPartialFunction<T, Collection<D>>() {
      String appliedClassName;

      @Override
      public boolean isDefinedAt(T x) {
        return fns.stream()
            .filter(pfn -> PlanUtils.safeIsDefinedAt(pfn, x))
            .findFirst()
            .isPresent();
      }

      private boolean isDefinedAt(T x, PartialFunction<T, ? extends Collection<D>> pfn) {
        return PlanUtils.safeIsDefinedAt(pfn, x);
      }

      @Override
      public Collection<D> apply(T x) {
        return fns.stream()
            .filter(pfn -> PlanUtils.safeIsDefinedAt(pfn, x))
            .map(
                pfn -> {
                  try {
                    Collection<D> collection = pfn.apply(x);
                    if (log.isDebugEnabled()) {
                      log.debug(
                          "Visitor {} visited {}, returned {}",
                          pfn.getClass().getCanonicalName(),
                          x.getClass().getCanonicalName(),
                          collection);
                    }
                    appliedClassName = x.getClass().getName();
                    return collection;
                  } catch (RuntimeException | NoClassDefFoundError | NoSuchMethodError e) {
                    log.error("Apply failed:", e);
                    return null;
                  }
                })
            .filter(Objects::nonNull)
            .flatMap(Collection::stream)
            .collect(Collectors.toList());
      }

      @Override
      String appliedName() {
        return appliedClassName;
      }
    };
  }

  /**
   * Given a schema, construct a valid {@link OpenLineage.SchemaDatasetFacet}.
   *
   * @param structType
   * @return
   */
  public static OpenLineage.SchemaDatasetFacet schemaFacet(
      OpenLineage openLineage, StructType structType) {
    return openLineage
        .newSchemaDatasetFacetBuilder()
        .fields(transformFields(openLineage, structType.fields()))
        .build();
  }

  private static List<OpenLineage.SchemaDatasetFacetFields> transformFields(
      OpenLineage openLineage, StructField... fields) {
    return Arrays.stream(fields)
        .map(field -> transformField(openLineage, field))
        .collect(Collectors.toList());
  }

  private static OpenLineage.SchemaDatasetFacetFields transformField(
      OpenLineage openLineage, StructField field) {
    OpenLineage.SchemaDatasetFacetFieldsBuilder builder =
        openLineage
            .newSchemaDatasetFacetFieldsBuilder()
            .name(field.name())
            .type(field.dataType().typeName());

    if (field.metadata() != null) {
      // field.getComment() actually tries to access field.metadata(),
      // and fails with NullPointerException if it is null instead of expected Metadata.empty()
      builder = builder.description(asJavaOptional(field.getComment()).orElse(null));
    }

    if (field.dataType() instanceof StructType) {
      StructType structField = (StructType) field.dataType();
      return builder
          .type("struct")
          .fields(transformFields(openLineage, structField.fields()))
          .build();
    }

    if (field.dataType() instanceof MapType) {
      MapType mapField = (MapType) field.dataType();
      return builder
          .type("map")
          .fields(
              transformFields(
                  openLineage,
                  new StructField("key", mapField.keyType(), false, Metadata.empty()),
                  new StructField(
                      "value",
                      mapField.valueType(),
                      mapField.valueContainsNull(),
                      Metadata.empty())))
          .build();
    }

    if (field.dataType() instanceof ArrayType) {
      ArrayType arrayField = (ArrayType) field.dataType();
      return builder
          .type("array")
          .fields(
              transformFields(
                  openLineage,
                  new StructField(
                      "_element",
                      arrayField.elementType(),
                      arrayField.containsNull(),
                      Metadata.empty())))
          .build();
    }

    return builder.build();
  }

  /**
   * Given a list of attributes, constructs a valid {@link OpenLineage.SchemaDatasetFacet}.
   *
   * @param attributes
   * @return
   */
  public static StructType toStructType(List<Attribute> attributes) {
    return new StructType(
        attributes.stream()
            .map(
                attr ->
                    new StructField(attr.name(), attr.dataType(), attr.nullable(), attr.metadata()))
            .collect(Collectors.toList())
            .toArray(new StructField[0]));
  }

  public static String namespaceUri(URI outputPath) {
    return Optional.ofNullable(outputPath.getAuthority())
        .map(a -> String.format("%s://%s", outputPath.getScheme(), a))
        .orElse(outputPath.getScheme());
  }

  /**
   * Construct a {@link OpenLineage.DatasourceDatasetFacet} given a namespace for the datasource.
   *
   * @param namespaceUri
   * @return
   */
  public static OpenLineage.DatasourceDatasetFacet datasourceFacet(
      OpenLineage openLineage, String namespaceUri) {
    return openLineage
        .newDatasourceDatasetFacetBuilder()
        .uri(URI.create(namespaceUri))
        .name(namespaceUri)
        .build();
  }

  /**
   * Construct a {@link OpenLineage.ParentRunFacet} given the parent job's parentRunId, job name,
   * and namespace.
   *
   * @param parentRunId
   * @param parentJobName
   * @param parentJobNamespace
   * @return
   */
  public static OpenLineage.ParentRunFacet parentRunFacet(
      UUID parentRunId,
      String parentJobName,
      String parentJobNamespace,
      UUID rootParentRunId,
      String rootParentJobName,
      String rootParentJobNamespace) {
    return new OpenLineage(Versions.OPEN_LINEAGE_PRODUCER_URI)
        .newParentRunFacetBuilder()
        .run(new OpenLineage.ParentRunFacetRunBuilder().runId(parentRunId).build())
        .job(
            new OpenLineage.ParentRunFacetJobBuilder()
                .name(NameNormalizer.normalize(parentJobName))
                .namespace(parentJobNamespace)
                .build())
        .root(
            new OpenLineage.ParentRunFacetRootBuilder()
                .run(new OpenLineage.RootRunBuilder().runId(rootParentRunId).build())
                .job(
                    new OpenLineage.RootJobBuilder()
                        .namespace(rootParentJobNamespace)
                        .name(rootParentJobName)
                        .build())
                .build())
        .build();
  }

  public static Path getDirectoryPath(Path p, Configuration hadoopConf) {
    try {
      if (p.getFileSystem(hadoopConf).getFileStatus(p).isFile()) {
        return p.getParent();
      } else {
        return p;
      }
    } catch (IOException e) {
      log.warn("Unable to get file system for path: {}", e.getMessage());
      return p;
    }
  }

  /**
   * Given a list of RDDs, it collects list of data location directories. For each RDD, a parent
   * directory is taken and list of distinct locations is returned.
   *
   * @param fileRdds
   * @return
   */
  public static List<Path> findRDDPaths(List<RDD<?>> fileRdds) {
    return fileRdds.stream()
        .flatMap(RddPathUtils::findRDDPaths)
        .distinct()
        .collect(Collectors.toList());
  }

  /**
   * instanceOf alike implementation which does not fail in case of a missing class.
   *
   * @param instance
   * @param classCanonicalName
   * @return
   */
  public static boolean safeIsInstanceOf(Object instance, String classCanonicalName) {
    try {
      Class c = Class.forName(classCanonicalName);
      return instance.getClass().isAssignableFrom(c);
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  /**
   * isDefinedAt method implementation that should never throw an error or exception
   *
   * @param pfn
   * @param x
   * @return
   */
  public static boolean safeIsDefinedAt(PartialFunction pfn, Object x) {
    try {
      return pfn.isDefinedAt(x);
    } catch (ClassCastException e) {
      // do nothing
      return false;
    } catch (Exception e) {
      if (e != null) {
        log.info("isDefinedAt method failed on {}", e);
      }
      return false;
    } catch (NoClassDefFoundError e) {
      log.info("isDefinedAt method failed on {}", e.getMessage());
      return false;
    }
  }

  /**
   * apply method implementation that should never throw an error or exception
   *
   * @param pfn
   * @param x
   * @return
   */
  public static <T, D> List<T> safeApply(PartialFunction<D, List<T>> pfn, D x) {
    try {
      return pfn.apply(x);
    } catch (Exception | NoClassDefFoundError | NoSuchMethodError e) {
      log.info("apply method failed with", e);
      return Collections.emptyList();
    }
  }
}
