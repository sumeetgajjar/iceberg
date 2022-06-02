/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.spark;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.hadoop.HadoopCatalog;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

public abstract class SparkTestBaseWithCatalog extends SparkTestBase {
  private static File warehouse = null;

  @BeforeClass
  public static void createWarehouse() throws IOException {
    SparkTestBaseWithCatalog.warehouse = File.createTempFile("warehouse", null);
    Assert.assertTrue(warehouse.delete());
  }

  @AfterClass
  public static void dropWarehouse() throws IOException {
    if (warehouse != null && warehouse.exists()) {
      Path warehousePath = new Path(warehouse.getAbsolutePath());
      FileSystem fs = warehousePath.getFileSystem(hiveConf);
      Assert.assertTrue("Failed to delete " + warehousePath, fs.delete(warehousePath, true));
    }
  }

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  protected final String catalogName;
  protected final Catalog validationCatalog;
  protected final SupportsNamespaces validationNamespaceCatalog;
  protected final TableIdentifier tableIdent = TableIdentifier.of(Namespace.of("default"), "table");
  protected final String tableName;

  public SparkTestBaseWithCatalog() {
    this(SparkCatalogConfig.HADOOP);
  }

  public SparkTestBaseWithCatalog(SparkCatalogConfig config) {
    this(config.catalogName(), config.implementation(), config.properties());
  }

  public SparkTestBaseWithCatalog(
      String catalogName, String implementation, Map<String, String> config) {
    this.catalogName = catalogName;
    this.validationCatalog =
        catalogName.equals("testhadoop")
            ? new HadoopCatalog(spark.sessionState().newHadoopConf(), "file:" + warehouse)
            : catalog;
    this.validationNamespaceCatalog = (SupportsNamespaces) validationCatalog;

    spark.conf().set("spark.sql.catalog." + catalogName, implementation);
    config.forEach(
        (key, value) -> spark.conf().set("spark.sql.catalog." + catalogName + "." + key, value));

    boolean isHadoopCatalog = config.get("type").equalsIgnoreCase("hadoop");
    if (isHadoopCatalog) {
      spark.conf().set("spark.sql.catalog." + catalogName + ".warehouse", "file:" + warehouse);
    }

    this.tableName =
        (catalogName.equals("spark_catalog") ? "" : catalogName + ".") + "default.table";

    // "SHOW NAMESPACES IN <NAMESPACE>" command for HiveCatalog considers the catalog name as the first level in the
    // namespace hierarchy. Thus, "SHOW NAMESPACES IN <hiveCatalogName>" returns a valid output.
    // The same command "SHOW NAMESPACES IN <hadoopCatalogName>" fails for HadoopCatalog. This is because it expects
    // "<hadoopCatalogName>" dir to exist and all its sub-dirs would be returned as the result. Since
    // "<hadoopCatalogName>" dir does not exist, the command fails with
    // "org.apache.iceberg.exceptions.NoSuchNamespaceException: Namespace does not exist" error. Thus skipping it for
    // HadoopCatalog.
    boolean createNamespace = isHadoopCatalog || spark.sql("SHOW NAMESPACES IN " + catalogName)
        .filter("namespace = 'default'")
        .isEmpty();
    if (createNamespace) {
      sql("CREATE NAMESPACE IF NOT EXISTS " + catalogName + ".default");
    }
  }

  protected String tableName(String name) {
    return (catalogName.equals("spark_catalog") ? "" : catalogName + ".") + "default." + name;
  }
}
